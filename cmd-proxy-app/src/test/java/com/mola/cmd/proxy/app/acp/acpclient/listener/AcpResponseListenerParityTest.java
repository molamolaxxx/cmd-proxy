package com.mola.cmd.proxy.app.acp.acpclient.listener;

import com.google.gson.JsonObject;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventEnvelope;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventType;
import com.mola.cmd.proxy.app.acp.team.listener.TeamAcpResponseListener;
import com.mola.cmd.proxy.app.acp.team.model.TeamDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberDefinition;
import com.mola.cmd.proxy.app.acp.team.runtime.TeamRuntime;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 同一个 AcpResponseListener 输入在普通 ACP 和 Team 中必须产生相同的
 * 用户可见 content/end 帧。Team 额外的 identity envelope 与结构化观测
 * 事件不参与比较。
 */
@RunWith(Parameterized.class)
public class AcpResponseListenerParityTest {

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> cases() {
        JsonObject tool = new JsonObject();
        JsonObject input = new JsonObject();
        input.addProperty("path", "<work>/a.png");
        JsonObject output = new JsonObject();
        output.addProperty("contentType", "image/png");
        output.addProperty("cardHtml", "<img src=\"asset.png\">");
        tool.add("rawInput", input);
        tool.add("rawOutput", output);
        tool.addProperty("kind", "mcp");

        return Arrays.asList(new Object[][]{
                {"text", action(l -> l.onMessage("hello"))},
                {"tool begin is not visible",
                        action(l -> l.onToolCall("t1", "MCP", "pending", tool))},
                {"tool update is not visible",
                        action(l -> l.onToolCall("t1", "", "in_progress", tool))},
                {"tool result card",
                        action(l -> l.onToolCall("t1", "MCP #image", "completed", tool))},
                {"subagent start",
                        action(l -> l.onSubAgentEvent("AGENT_START", "worker#1", "task"))},
                {"subagent result",
                        action(l -> l.onSubAgentEvent("AGENT_COMPLETE", "worker", "result"))},
                {"schedule card",
                        action(l -> l.onScheduleEvent("SCHEDULE_CREATE", "{\"id\":1}", true))},
                {"schedule execution card",
                        action(l -> l.onScheduleEvent("SCHEDULE_EXECUTE",
                                "[定时任务触发] 任务: report", true))},
                {"compaction card",
                        action(l -> l.onCompactionEvent("COMPACTION_COMPLETED", "codex"))},
                {"unknown compaction is ignored",
                        action(l -> l.onCompactionEvent("COMPACTION_STARTED", "codex"))},
                {"complete", action(l -> l.onComplete("full"))},
                {"error", action(l -> l.onError(new IllegalStateException("failed")))},
                {"text then tool preserves newline and order", action(l -> {
                    l.onMessage("prefix");
                    l.onToolCall("t2", "Read", "completed", tool);
                    l.onComplete("prefix");
                })}
        });
    }

    private final Consumer<AcpResponseListener> action;

    public AcpResponseListenerParityTest(
            String ignoredName, Consumer<AcpResponseListener> action) {
        this.action = action;
    }

    @Test
    public void normalAndTeamHaveIdenticalVisibleFrames() {
        List<Frame> normal = new ArrayList<>();
        DefaultAcpResponseListener normalListener =
                new DefaultAcpResponseListener("main", (content, end) ->
                        normal.add(new Frame(content, end)));

        TeamMemberDefinition member = member("member-1", 0);
        TeamRuntime runtime = new TeamRuntime(TeamDefinition.creating(
                "team-1", "owner-1", "Team", "team-acp-instance",
                "request-1", Arrays.asList(member, member("member-2", 1)),
                100L));
        List<TeamEventEnvelope> events = new ArrayList<>();
        TeamAcpResponseListener teamListener =
                new TeamAcpResponseListener(runtime, member, events::add);

        action.accept(normalListener);
        action.accept(teamListener);

        assertEquals(normal, visibleFrames(events));
    }

    @Test
    public void structuredEventsNeverReplaceRenderedCardChunks() {
        List<Frame> normal = new ArrayList<>();
        DefaultAcpResponseListener normalListener =
                new DefaultAcpResponseListener("main", (content, end) ->
                        normal.add(new Frame(content, end)));
        action.accept(normalListener);
        if (normal.stream().anyMatch(frame ->
                !frame.end && frame.content.contains("<details"))) {
            TeamMemberDefinition member = member("member-1", 0);
            TeamRuntime runtime = new TeamRuntime(TeamDefinition.creating(
                    "team-1", "owner-1", "Team", "team-acp-instance",
                    "request-1", Arrays.asList(member, member("member-2", 1)),
                    100L));
            List<TeamEventEnvelope> events = new ArrayList<>();
            action.accept(new TeamAcpResponseListener(
                    runtime, member, events::add));
            assertTrue(events.stream().anyMatch(event ->
                    event.getType() == TeamEventType.MESSAGE_CHUNK
                            && String.valueOf(((Map<?, ?>) event.getData())
                            .get("content")).contains("<details")));
        }
    }

    private static Consumer<AcpResponseListener> action(
            Consumer<AcpResponseListener> value) {
        return value;
    }

    private static List<Frame> visibleFrames(List<TeamEventEnvelope> events) {
        List<Frame> frames = new ArrayList<>();
        for (TeamEventEnvelope event : events) {
            if (event.getType() == TeamEventType.MESSAGE_CHUNK) {
                frames.add(new Frame(String.valueOf(
                        ((Map<?, ?>) event.getData()).get("content")), false));
            } else if (event.getType() == TeamEventType.MESSAGE_COMPLETE) {
                frames.add(new Frame("", true));
            } else if (event.getType() == TeamEventType.MESSAGE_ERROR) {
                frames.add(new Frame(String.valueOf(
                        ((Map<?, ?>) event.getData()).get("content")), true));
            }
        }
        return frames;
    }

    private static TeamMemberDefinition member(String id, int order) {
        return new TeamMemberDefinition(
                id, "acp-Robot_" + order, "source-" + id,
                "Robot " + id, "Robot " + id, "", order,
                "remark", "fingerprint-" + id);
    }

    private static final class Frame {
        private final String content;
        private final boolean end;

        private Frame(String content, boolean end) {
            this.content = content;
            this.end = end;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Frame)) return false;
            Frame that = (Frame) other;
            return end == that.end && content.equals(that.content);
        }

        @Override
        public int hashCode() {
            return 31 * content.hashCode() + (end ? 1 : 0);
        }

        @Override
        public String toString() {
            return "Frame{end=" + end + ", content='" + content + "'}";
        }
    }
}
