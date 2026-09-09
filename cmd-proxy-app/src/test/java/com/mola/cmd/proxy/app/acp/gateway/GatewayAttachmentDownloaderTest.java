package com.mola.cmd.proxy.app.acp.gateway;

import org.junit.Test;

import java.net.InetAddress;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GatewayAttachmentDownloaderTest {
    @Test
    public void blocksLoopbackPrivateAndLinkLocalAddresses() throws Exception {
        assertTrue(GatewayAttachmentDownloader.isPrivate(InetAddress.getByName("127.0.0.1")));
        assertTrue(GatewayAttachmentDownloader.isPrivate(InetAddress.getByName("10.2.3.4")));
        assertTrue(GatewayAttachmentDownloader.isPrivate(InetAddress.getByName("169.254.1.2")));
        assertTrue(GatewayAttachmentDownloader.isPrivate(InetAddress.getByName("::1")));
        assertFalse(GatewayAttachmentDownloader.isPrivate(InetAddress.getByName("8.8.8.8")));
    }
}
