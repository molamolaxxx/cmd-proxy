package com.mola.cmd.proxy.app.acp.task.dispatch;

import com.alibaba.fastjson.JSONObject;

import java.util.List;

/**
 * Trusted runtime/configuration view used by task target resolution.
 *
 * <p>The user supplied target is only a lookup request. Implementations must rebuild the
 * returned assignee from authoritative local configuration or a trusted coordinator snapshot;
 * they must never copy an unverified session/client address from the request.</p>
 */
public interface TaskExecutionDirectory {

    /** Returns the stable assignee for an Agent target, or {@code null} when it is unknown. */
    JSONObject resolveAgent(JSONObject target);

    /**
     * Returns the stable Team roster in deterministic order. A member may be temporarily
     * offline or BUSY and still belongs in this list; permanently removed members must not.
     */
    List<JSONObject> listTeamMembers(JSONObject target);
}
