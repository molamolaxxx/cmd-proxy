package com.mola.cmd.proxy.app.acp.team.talkto;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.talkto.model.ExternalTalkToContact;
import com.mola.cmd.proxy.app.acp.talkto.model.ContactRef;
import com.mola.cmd.proxy.app.acp.team.model.TeamContactRef;
import com.mola.cmd.proxy.app.acp.team.model.TeamDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberDefinition;
import com.mola.cmd.proxy.app.acp.team.runtime.TeamRuntime;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class TeamTalkToContextInjectorTest {

    @Test
    public void buildsStrictMemberIdOnlyContactBookFromPersistedRemarks() {
        TeamRuntime runtime = runtime();
        TeamTalkToContextInjector injector =
                new TeamTalkToContextInjector(runtime, "member-1");
        ContactRef hostileOrdinaryContact = new ContactRef("outside-robot", "outside");

        String context = injector.buildContext(
                Collections.singletonList(hostileOrdinaryContact),
                Collections.singletonMap("outside-robot", new AcpRobotParam()),
                "source-robot");
        List<TeamContactRef> contacts = injector.contacts();

        assertEquals(1, contacts.size());
        assertEquals("member-2", contacts.get(0).getTargetTeamMemberId());
        assertEquals("team-acp-member-2", contacts.get(0).getTargetAcpClientId());
        assertEquals("signature for member-2", contacts.get(0).getRemark());
        assertTrue(context.contains("\"target\":\"member-2\""));
        assertTrue(context.contains("\"displayName\":\"Display member-2\""));
        assertTrue(context.contains("\"remark\":\"signature for member-2\""));
        assertTrue(context.contains("同队联系人卡片"));
        assertTrue(context.contains("target 必须使用不可变 teamMemberId"));
        assertTrue(context.contains("限制不适用于 dispatch_subagent"));
        assertTrue(context.contains("重要运行时约束：发出 talk_to 后"));
        assertTrue(context.contains("不要使用 Bash、PowerShell、Python"));
        assertTrue(context.contains("wait、sleep、while 循环"));
        assertTrue(context.contains("不表示你会在当前 turn 内获得回复"));
        assertFalse(context.contains("outside-robot"));
        assertFalse(context.contains("team-acp-member-2"));
        assertFalse(context.contains("source-robot-member-2"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsSelfOutsideTeam() {
        new TeamTalkToContextInjector(runtime(), "foreign-member");
    }

    @Test
    public void appendsBoundExternalChannelContactsForCurrentTeamMember() {
        String ownerKey = "team:team-1:member-1";
        TeamTalkToContextInjector injector = new TeamTalkToContextInjector(
                runtime(), "member-1",
                requestedOwner -> ownerKey.equals(requestedOwner)
                        ? Collections.singletonList(new ExternalTalkToContact(
                                "channel:wecom-main", "wecom-main", "外部信道"))
                        : Collections.emptyList(),
                ownerKey);

        String context = injector.buildContext(
                Collections.emptyList(), Collections.emptyMap(), "source-robot");

        assertTrue(context.contains("绑定的外部信道联系人"));
        assertTrue(context.contains("wecom-main（target: channel:wecom-main）: 外部信道"));
        assertTrue(context.contains("target: channel:wecom-main"));
        assertTrue(context.contains("\"target\":\"member-2\""));
        assertTrue(context.contains("talk_to MCP 工具"));
        assertFalse(context.contains("\"action\""));
    }

    @Test
    public void omitsExternalChannelSectionWhenNoStableTargetIsDiscoverable() {
        TeamTalkToContextInjector injector = new TeamTalkToContextInjector(
                runtime(), "member-1", requestedOwner -> Collections.emptyList(),
                "team:team-1:member-1");

        String context = injector.buildContext(
                Collections.emptyList(), Collections.emptyMap(), "source-robot");

        assertFalse(context.contains("绑定的外部信道联系人"));
        assertFalse(context.contains("channel:"));
    }

    @Test
    public void singleMemberTeamExplainsThatTeamTalkToIsUnavailable() {
        TeamTalkToContextInjector injector =
                new TeamTalkToContextInjector(singleMemberRuntime(), "member-1");

        String context = injector.buildContext(
                Collections.emptyList(), Collections.emptyMap(), "source-robot");

        assertTrue(injector.contacts().isEmpty());
        assertTrue(context.contains("当前 Team 没有其他队员，队内 talk_to 不可用"));
        assertFalse(context.contains("发送给同队成员的格式"));
    }

    private static TeamRuntime runtime() {
        TeamMemberDefinition first = member("member-1", 0);
        TeamMemberDefinition second = member("member-2", 1);
        TeamDefinition creating = TeamDefinition.creating(
                "team-1", "owner-1", "Fast Team", "team-acp-instance", "request-1",
                Arrays.asList(first, second), 100L);
        return new TeamRuntime(creating);
    }

    private static TeamRuntime singleMemberRuntime() {
        TeamDefinition creating = TeamDefinition.creating(
                "team-1", "owner-1", "Fast Team", "team-acp-instance", "request-1",
                Collections.singletonList(member("member-1", 0)), 100L);
        return new TeamRuntime(creating);
    }

    private static TeamMemberDefinition member(String id, int order) {
        return new TeamMemberDefinition(
                id, "acp-source-" + id, "source-group-" + id,
                "source-robot-" + id, "Display " + id, "", order,
                "signature for " + id, "fingerprint-" + id);
    }
}
