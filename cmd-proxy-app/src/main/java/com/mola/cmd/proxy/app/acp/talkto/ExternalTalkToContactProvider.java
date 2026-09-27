package com.mola.cmd.proxy.app.acp.talkto;

import com.mola.cmd.proxy.app.acp.talkto.model.ExternalTalkToContact;

import java.util.List;

public interface ExternalTalkToContactProvider {
    List<ExternalTalkToContact> contactsForGroup(String groupId);

    /**
     * 当前 owner 是否绑定了已启用的外部信道。
     *
     * <p>默认实现兼容只提供主动通知目标的实现；信道网关会覆盖此方法，
     * 从而区分“未绑定信道”和“已绑定信道但没有主动通知目标”。</p>
     */
    default boolean hasEnabledChannelForGroup(String groupId) {
        List<ExternalTalkToContact> contacts = contactsForGroup(groupId);
        return contacts != null && !contacts.isEmpty();
    }
}
