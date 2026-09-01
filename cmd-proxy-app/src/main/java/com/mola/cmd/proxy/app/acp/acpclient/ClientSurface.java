package com.mola.cmd.proxy.app.acp.acpclient;

/**
 * User-facing surface that owns an ACP client.
 *
 * <p>Scope describes what the client is (MAIN, TEAM, sub-client); surface
 * describes which product/runtime projection owns it. Keeping them separate
 * prevents a Starweave MAIN client from being published through MolaChat
 * callbacks merely because both are MAIN clients.</p>
 */
public enum ClientSurface {
    MOLACHAT,
    STARWEAVE,
    TEAM,
    INTERNAL
}
