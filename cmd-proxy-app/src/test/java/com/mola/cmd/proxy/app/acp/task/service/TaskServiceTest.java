package com.mola.cmd.proxy.app.acp.task.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.task.model.TaskException;
import com.mola.cmd.proxy.app.acp.task.store.TaskRepository;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class TaskServiceTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private TaskService service;private Path path;
    @Before public void setup()throws Exception{path=temp.newFolder().toPath();service=new TaskService(path,"instance-1");}
    @After public void close(){service.close();}
    private JSONObject object(String value){return JSON.parseObject(value);}
    private JSONObject create(){return service.create(object("{name:'Task',contentMarkdown:'v1',target:{type:'AGENT',instanceId:'instance-1',agentId:'stable-agent'},creatorName:'Human',requestId:'create-1'}")).getJSONObject("task");}
    private JSONObject current(String id){return service.get(id,new JSONObject()).getJSONObject("task");}
    private JSONObject status(JSONObject task,String state,String request){JSONObject r=new JSONObject();r.put("status",state);r.put("expectedRevision",task.getLongValue("revision"));r.put("observedContentVersion",task.getLongValue("contentVersion"));r.put("requestId",request);r.put("actorName","Agent");return r;}
    private void fails(String code,Runnable action){try{action.run();fail("Expected "+code);}catch(TaskException e){assertEquals(code,e.getCode());}}
    @Test public void targetPolicyRejectsMixedTeamBeforePersistence(){service.close();service=new TaskService(path,"instance-1",target->{if("TEAM".equals(target.getString("type")))throw new TaskException("MIXED_TEAM_NOT_SUPPORTED","mixed Team",400);});fails("MIXED_TEAM_NOT_SUPPORTED",()->service.create(object("{name:'Mixed',target:{type:'TEAM',teamId:'mixed'},creatorName:'Human',requestId:'mixed-1'}")));assertEquals(0,service.list(new JSONObject()).getLongValue("total"));assertTrue(service.getRepository().claimOutbox("worker",10,System.currentTimeMillis(),10000).isEmpty());}
    @Test public void permanentDeleteRemovesOwnedDataAndFencesPendingDelivery() throws Exception {
        JSONObject t=create(); String id=t.getString("id"); TaskRepository repo=service.getRepository();
        service.addComment(id,object("{contentMarkdown:'private comment',observedContentVersion:1,authorName:'Human',requestId:'delete-comment'}"));
        JSONObject leased=repo.claimOutbox("delete-worker",1,System.currentTimeMillis(),10000).get(0);
        JSONObject card=new JSONObject();card.put("taskId",id);card.put("eventId",leased.getString("eventId"));
        JSONObject payload=new JSONObject();payload.put("card",card);
        repo.acceptReceipt(leased.getString("eventId"),payload,true);
        fails("VERSION_CONFLICT",()->service.delete(id,object("{expectedRevision:99}")));
        assertEquals(id,current(id).getString("id"));
        assertTrue(service.delete(id,object("{expectedRevision:1}")).getBooleanValue("deleted"));
        fails("NOT_FOUND",()->current(id));
        fails("NOT_FOUND",()->service.history(id,new JSONObject()));
        assertTrue(repo.pendingReceipts(20).isEmpty());
        assertTrue(repo.claimOutbox("after-delete",20,System.currentTimeMillis(),10000).isEmpty());
        assertFalse(repo.retry(leased.getString("eventId"),leased.getString("leaseToken"),0,"stale"));
        fails("NOT_FOUND",()->repo.acceptReceipt(leased.getString("eventId"),payload,true));
        repo.transaction(c->{for(String table:Arrays.asList("task_history","task_comment","task_request_dedup")) {
            try(java.sql.Statement statement=c.createStatement();java.sql.ResultSet rows=statement.executeQuery("SELECT COUNT(*) FROM "+table)){assertTrue(rows.next());assertEquals(table,0,rows.getInt(1));}
        }return null;});
    }
    @Test public void deletingSharedAttachmentKeepsOtherTaskHistory() throws Exception {
        JSONObject file=service.getAttachments().upload("shared.txt","text/plain",new ByteArrayInputStream(new byte[]{1,2,3}));
        JSONObject request=object("{name:'one',contentMarkdown:'body',target:{type:'AGENT'},creatorName:'Human',requestId:'file-task-1'}");
        JSONArray ids=new JSONArray();ids.add(file.getString("id"));request.put("attachmentIds",ids);
        JSONObject first=service.create(request).getJSONObject("task");
        request.put("requestId","file-task-2");request.put("name","two");
        JSONObject second=service.create(request).getJSONObject("task");
        service.delete(first.getString("id"),object("{expectedRevision:1}"));
        assertEquals(file.getString("id"),service.getAttachments().metadata(file.getString("id")).getString("id"));
        assertEquals(1,service.history(second.getString("id"),new JSONObject()).getIntValue("total"));
        service.delete(second.getString("id"),object("{expectedRevision:1}"));
        fails("NOT_FOUND",()->service.getAttachments().metadata(file.getString("id")));
        assertFalse(Files.exists(path.resolve("attachments").resolve(file.getString("id"))));
    }
    @Test public void dualVersionsAndOldContentCompletion(){JSONObject t=create();String id=t.getString("id");assertEquals(1,t.getLongValue("revision"));assertEquals(0,service.comments(id,new JSONObject()).getLongValue("total"));
        service.updateStatus(id,status(t,"IN_PROGRESS","s1"));JSONObject started=current(id);assertEquals(2,started.getLongValue("revision"));assertEquals(1,started.getLongValue("contentVersion"));
        JSONObject edit=object("{contentMarkdown:'v2',expectedRevision:2,requestId:'edit-1'}");service.edit(id,edit);JSONObject latest=current(id);assertEquals(3,latest.getLongValue("revision"));assertEquals(2,latest.getLongValue("contentVersion"));
        JSONObject stale=status(latest,"COMPLETED","s2");stale.put("observedContentVersion",1);fails("LATEST_CONTENT_REQUIRED",()->service.updateStatus(id,stale));
        service.addComment(id,object("{contentMarkdown:'Feedback about v1',observedContentVersion:1,authorName:'Agent',authorType:'AGENT',requestId:'c1'}"));assertEquals(3,current(id).getLongValue("revision"));
        JSONObject old=service.get(id,object("{revision:1}"));assertEquals("v1",old.getJSONObject("task").getString("contentMarkdown"));assertEquals(1,old.getJSONObject("comments").getLongValue("total"));assertEquals(2,old.getLongValue("currentContentVersion"));
        service.updateStatus(id,status(latest,"COMPLETED","s3"));fails("REOPEN_REQUIRED",()->service.edit(id,object("{contentMarkdown:'v3',expectedRevision:4,requestId:'edit-2'}")));
        service.edit(id,object("{contentMarkdown:'v3',expectedRevision:4,reopen:true,requestId:'edit-3'}"));assertEquals("START",current(id).getString("status"));assertEquals(3,current(id).getLongValue("contentVersion"));
    }
    @Test public void persistentIdempotencyAndParameterConflict(){JSONObject t=create();String id=t.getString("id");assertEquals(id,create().getString("id"));service.close();service=new TaskService(path,"instance-1");assertEquals(id,create().getString("id"));
        fails("IDEMPOTENCY_CONFLICT",()->service.create(object("{name:'Different',target:{type:'AGENT'},creatorName:'Human',requestId:'create-1'}")));
        JSONObject change=status(t,"IN_PROGRESS","s1");JSONObject first=service.updateStatus(id,change);assertEquals(JSON.parseObject(JSON.toJSONString(first,com.alibaba.fastjson.serializer.SerializerFeature.WriteMapNullValue)),service.updateStatus(id,change));assertEquals(2,service.history(id,new JSONObject()).getLongValue("total"));
    }
    @Test public void compareAndSwapAcrossConnections()throws Exception{JSONObject t=create();String id=t.getString("id");TaskService other=new TaskService(path,"instance-1");ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{List<Future<Boolean>> results=new ArrayList<>();for(int i=0;i<2;i++){final int n=i;results.add(pool.submit(()->{start.await();try{(n==0?service:other).edit(id,object("{contentMarkdown:'change"+n+"',expectedRevision:1,requestId:'edit"+n+"'}"));return true;}catch(TaskException e){assertEquals("VERSION_CONFLICT",e.getCode());return false;}}));}start.countDown();int successes=0;for(Future<Boolean> r:results)if(r.get(10,TimeUnit.SECONDS))successes++;assertEquals(1,successes);assertEquals(2,current(id).getLongValue("revision"));}finally{pool.shutdownNow();other.close();}
    }
    @Test public void outboxLeaseOrderAndAssignmentSurviveRestart(){JSONObject t=create();String id=t.getString("id");TaskRepository r=service.getRepository();long now=System.currentTimeMillis();JSONObject first=r.claimOutbox("w1",10,now,10000).get(0);assertEquals("TASK_CREATED",first.getString("eventType"));assertTrue(r.claimOutbox("w2",10,now,10000).isEmpty());
        JSONObject assignee=object("{instanceId:'instance-1',agentId:'a1'}");r.assign(id,assignee);assertEquals(assignee,r.assign(id,object("{agentId:'different'}")).getJSONObject("assignee"));assertTrue(r.claimOutbox("w2",10,now,10000).isEmpty());
        assertFalse(r.completeAssignment(first.getString("eventId"),"wrong-token"));assertTrue(r.completeAssignment(first.getString("eventId"),first.getString("leaseToken")));
        JSONObject assigned=r.claimOutbox("w1",10,now,10000).get(0);assertEquals("TASK_ASSIGNED",assigned.getString("eventType"));service.close();service=new TaskService(path,"instance-1");r=service.getRepository();JSONObject reclaimed=r.claimOutbox("w2",10,now+10001,10000).get(0);assertNotEquals(assigned.getString("leaseToken"),reclaimed.getString("leaseToken"));assertFalse(r.acknowledge(assigned.getString("eventId"),assigned.getString("leaseToken"),"old"));assertTrue(r.acknowledge(reclaimed.getString("eventId"),reclaimed.getString("leaseToken"),"durable"));assertEquals(assignee,current(id).getJSONObject("assignee"));
    }
    @Test public void receiptDedupAndRecovery(){TaskRepository r=service.getRepository();JSONObject payload=object("{taskId:'t',text:'work'}");JSONObject first=r.acceptReceipt("e",payload);assertFalse(first.getBooleanValue("duplicate"));assertTrue(r.acceptReceipt("e",payload).getBooleanValue("duplicate"));fails("IDEMPOTENCY_CONFLICT",()->service.getRepository().acceptReceipt("e",object("{taskId:'other'}")));service.close();service=new TaskService(path,"instance-1");List<JSONObject> claimed=service.getRepository().claimReceipts("receiver",10,System.currentTimeMillis(),10000);assertEquals(1,claimed.size());assertFalse(service.getRepository().completeReceipt("e","wrong-token"));assertTrue(service.getRepository().completeReceipt("e",claimed.get(0).getString("leaseToken")));assertTrue(service.getRepository().claimReceipts("receiver",10,System.currentTimeMillis()+10001,10000).isEmpty());}
    @Test public void attachmentsRemainAvailableForHistoricalContent()throws Exception{JSONObject a=service.getAttachments().upload("hello.txt","text/plain",new ByteArrayInputStream("hello".getBytes("UTF-8")));JSONObject request=object("{name:'Task',target:{type:'AGENT'},creatorName:'Human',requestId:'c'}");request.put("attachmentIds",Collections.singletonList(a.getString("id")));JSONObject t=service.create(request).getJSONObject("task");String id=t.getString("id");service.edit(id,object("{attachmentIds:[],expectedRevision:1,requestId:'edit'}"));assertEquals(0,service.getAttachments().cleanupStaged(System.currentTimeMillis()+48L*3600*1000));assertEquals(1,service.get(id,object("{revision:1}")).getJSONObject("task").getJSONArray("attachments").size());try(InputStream in=service.getAttachments().open(a.getString("id"))){assertEquals('h',in.read());}fails("INVALID_ARGUMENT",()->service.getAttachments().open("../../etc/passwd"));}
    @Test public void invalidMutationRollsBackAndStatsIgnoreStatus(){JSONObject t=create();String id=t.getString("id");fails("NOT_FOUND",()->service.edit(id,object("{attachmentIds:['missing'],expectedRevision:1,requestId:'bad'}")));assertEquals(1,current(id).getLongValue("revision"));assertEquals(1,service.history(id,new JSONObject()).getLongValue("total"));assertEquals(1,service.stats(object("{status:'COMPLETED'}")).getLongValue("START"));assertEquals(0,service.list(object("{status:'COMPLETED'}")).getLongValue("total"));fails("INVALID_ARGUMENT",()->service.edit(id,object("{name:'Rename',expectedRevision:1,requestId:'bad2'}")));}
    @Test public void suspendedTaskSuppressesPendingExecution(){JSONObject t=create();String id=t.getString("id");service.getRepository().assign(id,object("{agentId:'agent'}"));service.updateStatus(id,status(current(id),"SUSPENDED","s"));JSONArray events=service.get(id,new JSONObject()).getJSONArray("delivery");assertEquals("SUPERSEDED",events.getJSONObject(1).getString("state"));assertEquals(1,service.getRepository().activeAssignments().size());}

    @Test public void exposesTransitionsAndPersistsStatusControlMetadata(){JSONObject t=create();String id=t.getString("id");JSONObject detail=service.get(id,new JSONObject());assertEquals(Arrays.asList("IN_PROGRESS","CANCELLED","SUSPENDED"),detail.getJSONArray("agentAllowedTransitions"));JSONObject request=status(t,"SUSPENDED","suspend-with-reason");request.put("reason","Waiting for API decision");request.put("actorType","HUMAN");JSONObject changed=service.updateStatus(id,request);assertTrue(changed.getJSONArray("agentAllowedTransitions").contains("IN_PROGRESS"));JSONObject last=changed.getJSONObject("task").getJSONObject("lastStatusChange");assertEquals("START",last.getString("previousStatus"));assertEquals("Waiting for API decision",last.getString("reason"));JSONArray delivery=changed.getJSONArray("delivery");JSONObject event=delivery.getJSONObject(delivery.size()-1);assertEquals("SUSPENDED",event.getJSONObject("payload").getString("status"));assertEquals("HUMAN",event.getJSONObject("payload").getString("actorType"));}

    @Test public void affinitySelectsFreeMembersAndReusesPersistentBinding(){
        JSONObject first=create();JSONObject request=object("{name:'Second',target:{type:'TEAM'},creatorName:'Human',requestId:'second'}");JSONObject second=service.create(request).getJSONObject("task");
        List<JSONObject> candidates=Arrays.asList(object("{instanceId:'i',teamId:'t',teamMemberId:'a'}"),object("{instanceId:'i',teamId:'t',teamMemberId:'b'}"));
        JSONObject a=service.getRepository().assign(first.getString("id"),candidates,"AFFINITY").getJSONObject("assignee");
        JSONObject b=service.getRepository().assign(second.getString("id"),candidates,"AFFINITY").getJSONObject("assignee");assertNotEquals(a,b);
        assertEquals(a,service.getRepository().assign(first.getString("id"),candidates,"RANDOM").getJSONObject("assignee"));
    }
    @Test public void commentPaginationAndNoOpEditPreserveVersions(){JSONObject t=create();String id=t.getString("id");for(int i=0;i<3;i++)service.addComment(id,object("{contentMarkdown:'comment',observedContentVersion:1,authorName:'Human',requestId:'c"+i+"'}"));
        JSONObject first=service.get(id,object("{limit:2}"));assertEquals(2,first.getJSONObject("comments").getJSONArray("items").size());assertEquals("2",first.getJSONObject("comments").getString("nextCursor"));JSONObject tail=service.comments(id,object("{cursor:'2',limit:2}"));assertEquals(1,tail.getJSONArray("items").size());assertNull(tail.getString("nextCursor"));
        service.edit(id,object("{contentMarkdown:'v1',expectedRevision:1,requestId:'noop'}"));assertEquals(1,current(id).getLongValue("revision"));
    }
    @Test public void oversizedUploadLeavesNoMetadataOrFile()throws Exception{
        InputStream oversized=new InputStream(){private long remaining=20L*1024*1024+1;public int read(){return remaining-->0?0:-1;}public int read(byte[] buffer,int offset,int length){if(remaining<=0)return -1;int n=(int)Math.min(remaining,length);remaining-=n;return n;}};
        fails("RESOURCE_LIMIT",()->service.getAttachments().upload("large.bin","application/octet-stream",oversized));
        try(java.util.stream.Stream<Path> files=Files.list(path.resolve("attachments"))){assertEquals(0,files.count());}
    }
}
