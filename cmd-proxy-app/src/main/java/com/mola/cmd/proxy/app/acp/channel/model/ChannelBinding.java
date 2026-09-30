package com.mola.cmd.proxy.app.acp.channel.model;

public class ChannelBinding {
    public static final String TYPE_MAIN = "MAIN";
    public static final String TYPE_TEAM_MEMBER = "TEAM_MEMBER";
    public static final String MEMBER_SELECTION_FIXED = "FIXED";
    public static final String MEMBER_SELECTION_RANDOM = "RANDOM";
    public static final String MEMBER_SELECTION_AFFINITY = "AFFINITY";
    public static final String MEMBER_SELECTION_CONVERSATION_MAPPING = "CONVERSATION_MAPPING";

    private String type;
    private String instanceId;
    private String groupId;
    private String teamId;
    private String teamMemberId;
    private String teamMemberSelection;
    private java.util.List<ChannelConversationMapping> conversationMappings = new java.util.ArrayList<>();

    public java.util.List<ChannelConversationMapping> getConversationMappings() { return conversationMappings; }
    public void setConversationMappings(java.util.List<ChannelConversationMapping> mappings) {
        conversationMappings = mappings == null ? new java.util.ArrayList<>() : mappings;
    }

    public boolean usesConversationMapping() {
        return TYPE_TEAM_MEMBER.equals(type)
                && MEMBER_SELECTION_CONVERSATION_MAPPING.equals(effectiveTeamMemberSelection());
    }

    public ChannelConversationMapping findConversationMapping(String chatType, String conversationId) {
        if (conversationId == null || conversationId.trim().isEmpty()) return null;
        for (ChannelConversationMapping mapping : conversationMappings) {
            if (mapping != null && chatType != null && chatType.equals(mapping.getChatType())
                    && conversationId.trim().equals(mapping.getConversationId())) return mapping;
        }
        return null;
    }

    /** Structural validation shared by UI saves and runtime reloads. Empty mappings are valid. */
    public String validateConversationMappings(java.util.Set<String> memberIds) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (ChannelConversationMapping mapping : conversationMappings) {
            if (mapping == null) return "conversation mapping must be an object";
            String type = trim(mapping.getChatType());
            String id = trim(mapping.getConversationId());
            String member = trim(mapping.getTeamMemberId());
            if (!("group".equals(type) || "single".equals(type))) return "conversation mapping chatType is invalid";
            if (id.isEmpty() || id.length() > 512) return "conversation mapping conversationId is invalid";
            if (member.isEmpty()) return "conversation mapping teamMemberId is required";
            if (!keys.add(type + ":" + id)) return "duplicate conversation mapping";
            if (memberIds != null && !memberIds.contains(member)) return "conversation mapping Team member not found";
            mapping.setChatType(type);
            mapping.setConversationId(id);
            mapping.setTeamMemberId(member);
        }
        return null;
    }

    private static String trim(String value) { return value == null ? "" : value.trim(); }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }
    public String getTeamId() { return teamId; }
    public void setTeamId(String teamId) { this.teamId = teamId; }
    public String getTeamMemberId() { return teamMemberId; }
    public void setTeamMemberId(String teamMemberId) { this.teamMemberId = teamMemberId; }
    public String getTeamMemberSelection() { return teamMemberSelection; }
    public void setTeamMemberSelection(String teamMemberSelection) {
        this.teamMemberSelection = teamMemberSelection;
    }

    /** Legacy bindings without a selection field remain fixed-member bindings. */
    public String effectiveTeamMemberSelection() {
        return teamMemberSelection == null || teamMemberSelection.trim().isEmpty()
                ? MEMBER_SELECTION_FIXED : teamMemberSelection.trim();
    }
}
