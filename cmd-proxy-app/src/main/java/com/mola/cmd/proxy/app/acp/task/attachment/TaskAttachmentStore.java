package com.mola.cmd.proxy.app.acp.task.attachment;

import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.task.model.TaskException;
import com.mola.cmd.proxy.app.acp.task.store.TaskRepository;
import java.io.*;
import java.nio.file.*;
import java.nio.channels.Channels;
import java.security.*;
import java.time.Instant;
import java.util.*;
import static com.mola.cmd.proxy.app.acp.task.store.TaskRepository.*;

/** Immutable generated filenames, bounded streaming uploads and reference-aware cleanup. */
public final class TaskAttachmentStore {
    public static final long MAX_BYTES=20L*1024*1024;
    private final Path root;
    private final TaskRepository repository;
    public TaskAttachmentStore(Path directory,TaskRepository repository){this.repository=repository;try{Files.createDirectories(directory);root=directory.toRealPath();}catch(IOException e){throw storage(e);}}
    public JSONObject upload(String fileName,String mimeType,InputStream input){
        if(fileName==null||fileName.trim().isEmpty()||fileName.length()>255||fileName.indexOf('\r')>=0||fileName.indexOf('\n')>=0||fileName.indexOf('\0')>=0)throw new TaskException("INVALID_ARGUMENT","Invalid fileName",400);
        String name=fileName.replace('\\','/');name=name.substring(name.lastIndexOf('/')+1);if(name.isEmpty())throw new TaskException("INVALID_ARGUMENT","Invalid fileName",400);
        String id=UUID.randomUUID().toString();Path file=root.resolve(id);long size=0;
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(OutputStream output=Files.newOutputStream(file,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
                byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1){size+=count;if(size>MAX_BYTES)throw new TaskException("RESOURCE_LIMIT","Attachment exceeds 20 MiB",413);digest.update(buffer,0,count);output.write(buffer,0,count);}
            }
            try(java.nio.channels.FileChannel channel=java.nio.channels.FileChannel.open(file,StandardOpenOption.WRITE)){channel.force(true);}
            JSONObject metadata=new JSONObject(true);metadata.put("id",id);metadata.put("fileName",name);metadata.put("mimeType",mimeType!=null&&mimeType.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")?mimeType:"application/octet-stream");metadata.put("size",size);StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format("%02x",b&255));metadata.put("sha256",hash.toString());metadata.put("createdAt",Instant.now().toString());metadata.put("downloadUrl","/api/starweave/v1/tasks/attachments/"+id+"/download");
            repository.transaction(c->{execute(c,"INSERT INTO task_attachment VALUES(?,?,?,?)",id,id,metadata.getString("createdAt"),com.alibaba.fastjson.JSON.toJSONString(metadata, com.alibaba.fastjson.serializer.SerializerFeature.WriteMapNullValue));return null;});return metadata;
        }catch(Exception e){try{Files.deleteIfExists(file);}catch(IOException ignored){}if(e instanceof TaskException)throw(TaskException)e;throw storage(e);}
    }
    public JSONObject metadata(String id){validateId(id);return repository.transaction(c->{List<JSONObject> values=snapshots(c,"SELECT snapshot FROM task_attachment WHERE id=?",id);if(values.isEmpty())throw new TaskException("NOT_FOUND","Attachment not found",404);return values.get(0);});}
    public InputStream open(String id){metadata(id);Path file=root.resolve(id);try{if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!file.toRealPath().startsWith(root))throw new TaskException("NOT_FOUND","Attachment unavailable",404);return Channels.newInputStream(Files.newByteChannel(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS));}catch(IOException e){throw storage(e);}}
    public int cleanupStaged(long nowMillis){return repository.transaction(c->{List<String> ids=new ArrayList<>();try(java.sql.PreparedStatement s=c.prepareStatement("SELECT id FROM task_attachment a WHERE created_at<? AND NOT EXISTS(SELECT 1 FROM task_content_attachment b WHERE b.attachment_id=a.id) AND NOT EXISTS(SELECT 1 FROM task_comment_attachment b WHERE b.attachment_id=a.id)")){s.setString(1,Instant.ofEpochMilli(nowMillis-24L*60*60*1000).toString());try(java.sql.ResultSet r=s.executeQuery()){while(r.next())ids.add(r.getString(1));}}int count=0;for(String id:ids){validateId(id);try{Files.deleteIfExists(root.resolve(id));execute(c,"DELETE FROM task_attachment WHERE id=?",id);count++;}catch(IOException ignored){/* Windows occupied files are retried on next cleanup. */}}return count;});}
    /** Only remove the deleted task's files if no other task/history/comment references them. */
    public int cleanupUnreferenced(Collection<String> ids) {
        return repository.transaction(c -> {
            int count = 0;
            for (String id : ids) {
                validateId(id);
                try (java.sql.PreparedStatement statement = c.prepareStatement(
                        "SELECT id FROM task_attachment a WHERE id=? AND NOT EXISTS(SELECT 1 FROM task_content_attachment b WHERE b.attachment_id=a.id) AND NOT EXISTS(SELECT 1 FROM task_comment_attachment b WHERE b.attachment_id=a.id)")) {
                    statement.setString(1, id);
                    try (java.sql.ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) continue;
                    }
                }
                try {
                    Files.deleteIfExists(root.resolve(id));
                    execute(c, "DELETE FROM task_attachment WHERE id=?", id);
                    count++;
                } catch (IOException occupied) {
                    // Retain metadata for the existing staged-file cleanup to retry on Windows.
                    execute(c, "UPDATE task_attachment SET created_at=? WHERE id=?", Instant.EPOCH.toString(), id);
                }
            }
            return count;
        });
    }
    private static void validateId(String id){if(id==null||!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new TaskException("INVALID_ARGUMENT","Invalid attachment ID",400);}
}
