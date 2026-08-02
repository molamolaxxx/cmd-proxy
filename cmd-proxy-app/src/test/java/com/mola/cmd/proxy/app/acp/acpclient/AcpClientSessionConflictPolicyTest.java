package com.mola.cmd.proxy.app.acp.acpclient;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

public class AcpClientSessionConflictPolicyTest {

    @Test
    public void detectsActiveSessionConflictWithoutDependingOnPidParsing() {
        IOException withPid = new IOException(
                "Session is active in another process PID 12345");
        IOException withoutPid = new IOException(
                "Session is active in another process");

        assertTrue(AcpClient.isActiveSessionConflict(withPid));
        assertTrue(AcpClient.isActiveSessionConflict(withoutPid));
    }

    @Test
    public void ignoresUnrelatedLoadFailure() {
        assertFalse(AcpClient.isActiveSessionConflict(
                new IOException("session/load returned invalid state")));
        assertFalse(AcpClient.isActiveSessionConflict(
                new IOException((String) null)));
    }
}
