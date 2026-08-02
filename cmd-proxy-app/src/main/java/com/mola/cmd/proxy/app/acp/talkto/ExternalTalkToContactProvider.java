package com.mola.cmd.proxy.app.acp.talkto;

import com.mola.cmd.proxy.app.acp.talkto.model.ExternalTalkToContact;

import java.util.List;

public interface ExternalTalkToContactProvider {
    List<ExternalTalkToContact> contactsForGroup(String groupId);
}
