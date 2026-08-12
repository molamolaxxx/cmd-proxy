package com.mola.cmd.proxy.app;

import com.mola.cmd.proxy.client.conf.CmdProxyConf;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CmdProxyConfTest {

    @Test
    public void startupArgumentTakesPrecedenceOverEnvironment() {
        assertEquals("203.0.113.10", CmdProxyConf.resolveRemoteHost(
                new String[]{"acp", "--remote-host", "203.0.113.10"},
                "198.51.100.20"));
        assertEquals("203.0.113.11", CmdProxyConf.resolveRemoteHost(
                new String[]{"acp", "--remote-host=203.0.113.11"},
                "198.51.100.20"));
    }

    @Test
    public void environmentTakesPrecedenceOverDefault() {
        assertEquals("198.51.100.20", CmdProxyConf.resolveRemoteHost(
                new String[0], "198.51.100.20"));
        assertEquals(CmdProxyConf.DEFAULT_REMOTE_HOST, CmdProxyConf.resolveRemoteHost(
                new String[0], null));
    }

    @Test(expected = IllegalArgumentException.class)
    public void hostMustNotContainProtocolPathOrPort() {
        CmdProxyConf.resolveRemoteHost(
                new String[]{"--remote-host=https://203.0.113.10:9003/path"}, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void explicitArgumentMustHaveAValue() {
        CmdProxyConf.resolveRemoteHost(new String[]{"acp", "--remote-host"}, null);
    }

}
