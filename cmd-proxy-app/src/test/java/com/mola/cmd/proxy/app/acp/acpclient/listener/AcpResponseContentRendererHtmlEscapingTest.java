package com.mola.cmd.proxy.app.acp.acpclient.listener;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AcpResponseContentRendererHtmlEscapingTest {

    @Test
    public void toolTitleEscapesHtmlAndHeredocWithoutSwallowingFollowingText() {
        List<String> frames = new ArrayList<>();
        AcpResponseContentRenderer renderer =
                new AcpResponseContentRenderer((content, end) ->
                        frames.add(content));
        String title = "Running: <script>alert('&')</script> && "
                + "cat <<'EOF'\n<div>body</div>\nEOF";

        renderer.onToolCall(title, "completed", new JsonObject());
        renderer.onMessage("AFTER_CARD_VISIBLE");

        String rendered = String.join("", frames);
        assertTrue(rendered.contains("&lt;script&gt;alert('&amp;')"
                + "&lt;/script&gt; &amp;&amp; cat &lt;&lt;'EOF'"));
        assertTrue(rendered.contains("&lt;div&gt;body&lt;/div&gt;"));
        assertFalse(rendered.contains("<script>"));
        assertFalse(rendered.contains("</script>"));
        assertFalse(rendered.contains("&amp;lt;script"));
        assertTrue(rendered.indexOf("</details>")
                < rendered.indexOf("AFTER_CARD_VISIBLE"));
    }

    @Test
    public void dynamicSummaryNamesAndFallbackDetailsAreHtmlTextEscaped() {
        List<String> frames = new ArrayList<>();
        AcpResponseContentRenderer renderer =
                new AcpResponseContentRenderer((content, end) ->
                        frames.add(content));

        renderer.onSubAgentEvent("AGENT_START",
                "worker#*</summary><script>&", "task");
        renderer.onSubAgentEvent("DISPATCH_COMPLETE", null,
                "done <style>body{display:none}</style> & ok");
        renderer.onTalkToEvent("TALK_TO_RECEIVE",
                "robot<textarea>hidden</textarea>&", "hello");
        renderer.onScheduleEvent("UNKNOWN",
                "<title>hidden</title> & compact", false);

        String rendered = String.join("", frames);
        assertTrue(rendered.contains("worker&#35;&#42;&lt;/summary&gt;"
                + "&lt;script&gt;&amp;"));
        assertTrue(rendered.contains("done &lt;style&gt;body{display:none}"
                + "&lt;/style&gt; &amp; ok"));
        assertTrue(rendered.contains("robot&lt;textarea&gt;hidden"
                + "&lt;/textarea&gt;&amp;"));
        assertTrue(rendered.contains("&lt;title&gt;hidden&lt;/title&gt;"
                + " &amp; compact"));
        assertFalse(rendered.contains("</summary><script>"));
        assertFalse(rendered.contains("<style>body"));
        assertFalse(rendered.contains("<textarea>hidden"));
        assertFalse(rendered.contains("<title>hidden"));
    }

    @Test
    public void compactionProviderCannotEscapeItsCodeElement() {
        List<String> frames = new ArrayList<>();
        AcpResponseContentRenderer renderer =
                new AcpResponseContentRenderer((content, end) ->
                        frames.add(content));

        renderer.onCompactionEvent("COMPACTION_COMPLETED",
                "codex`</code><script>alert('&')</script>");

        String rendered = String.join("", frames);
        assertTrue(rendered.contains("Provider：<code>codex`&lt;/code&gt;"
                + "&lt;script&gt;alert('&amp;')&lt;/script&gt;</code>"));
        assertFalse(rendered.contains("</code><script>"));
        assertFalse(rendered.contains("&amp;lt;/code"));
    }
}
