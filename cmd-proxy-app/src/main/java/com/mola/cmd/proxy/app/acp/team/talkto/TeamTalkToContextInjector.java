package com.mola.cmd.proxy.app.acp.team.talkto;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.common.DirectJsonOutputHelper;
import com.mola.cmd.proxy.app.acp.talkto.TalkToContextInjector;
import com.mola.cmd.proxy.app.acp.talkto.model.ContactRef;
import com.mola.cmd.proxy.app.acp.team.model.TeamContactRef;
import com.mola.cmd.proxy.app.acp.team.model.TeamDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberDefinition;
import com.mola.cmd.proxy.app.acp.team.runtime.TeamRuntime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.LinkedHashMap;

/**
 * 只暴露当前 Team 其他 member 的临时通讯录。
 *
 * <p>忽略来源 robot 的普通 contacts/global registry，防止 Team member
 * 通过普通 robotName 或 chatterId 绕过队内白名单。</p>
 */
public final class TeamTalkToContextInjector extends TalkToContextInjector {

    private static final Gson GSON =
            new GsonBuilder().disableHtmlEscaping().create();

    private final TeamRuntime runtime;
    private final String selfTeamMemberId;

    public TeamTalkToContextInjector(TeamRuntime runtime, String selfTeamMemberId) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.selfTeamMemberId = requireText(selfTeamMemberId, "selfTeamMemberId");
        if (findMember(runtime.getDefinition(), selfTeamMemberId) == null) {
            throw new IllegalArgumentException("selfTeamMemberId does not belong to Team");
        }
    }

    @Override
    public String buildContext(List<ContactRef> ignoredContacts,
                               Map<String, AcpRobotParam> ignoredRobotRegistry,
                               String ignoredSelfName) {
        TeamDefinition team = runtime.getDefinition();
        List<TeamContactRef> contacts = contacts();
        StringBuilder sb = new StringBuilder();
        sb.append("\n<agent-team>\n");
        sb.append("你处于 Fast Team「").append(team.getName()).append("」中。");
        sb.append("talk_to 只能发送给下列同队成员，并且 target 必须使用不可变 teamMemberId。");
        sb.append("禁止使用来源 robotName、displayName、acpClientId、chatterId:robotName 或未列出的名称路由。\n");
        sb.append("消息为异步投递：目标忙碌时进入该成员的 Team 专属 inbox；");
        sb.append("已发送或已排队只表示路由层接收，不表示目标已处理。\n\n");
        sb.append("同队联系人卡片（JSON 中 target 是唯一可用于路由的值）：\n");
        for (TeamContactRef contact : contacts) {
            Map<String, String> card = new LinkedHashMap<>();
            card.put("target", contact.getTargetTeamMemberId());
            card.put("displayName", contact.getDisplayName());
            card.put("remark", contact.getRemark());
            sb.append("- ").append(GSON.toJson(card)).append("\n");
        }
        if (!contacts.isEmpty()) {
            sb.append("\n发送格式：\n");
            sb.append("{\"action\":\"talk_to\",\"target\":\"")
                    .append(contacts.get(0).getTargetTeamMemberId())
                    .append("\",\"content\":\"你的消息内容\"}\n");
        }
        sb.append("\nTeam talk_to 限制不适用于 dispatch_subagent、schedule、memory 或其他 ACP 能力；");
        sb.append("这些能力仍按正常模式工作。\n");
        DirectJsonOutputHelper.appendUsageWarning(sb,
                "发送 Team talk_to 消息",
                "拦截该 JSON 并在当前 Team 内按 memberId 路由");
        sb.append("</agent-team>\n");
        return sb.toString();
    }

    public List<TeamContactRef> contacts() {
        List<TeamMemberDefinition> members =
                new ArrayList<>(runtime.getDefinition().getMembers());
        members.sort(Comparator.comparingInt(TeamMemberDefinition::getOrder));
        List<TeamContactRef> result = new ArrayList<>();
        for (TeamMemberDefinition member : members) {
            if (!selfTeamMemberId.equals(member.getTeamMemberId())) {
                result.add(TeamContactRef.from(member));
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static TeamMemberDefinition findMember(TeamDefinition definition,
                                                   String memberId) {
        for (TeamMemberDefinition member : definition.getMembers()) {
            if (member.getTeamMemberId().equals(memberId)) {
                return member;
            }
        }
        return null;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
