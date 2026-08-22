# Project map

## Build and modules

- Root `pom.xml`: Maven aggregator, Java 8 target, Kotlin 1.9.0, modules `cmd-proxy-client` and `cmd-proxy-app`.
- `cmd-proxy-client`: lightweight shared RPC configuration, request/response models, receiver/provider API.
- `cmd-proxy-app`: executable application, runtime integrations, ACP/MCP logic, ConfigUI, and nearly all tests.
- Main artifact: `cmd-proxy-app/target/cmd-proxy-app-1.0.0.jar` plus the assembly `jar-with-dependencies` after package.

## Entrypoints and runtime data

- `cmd-proxy-app/src/main/kotlin/com/mola/cmd/proxy/app/Main.kt`: selects `mcp` or `acp`, allocates ports, initializes config, starts ConfigUI, and wires ACP services.
- `cmd-proxy-app/src/main/kotlin/com/mola/cmd/proxy/app/acp/AcpProxy.kt`: ACP composition root and command registration.
- `CmdProxyHome`: resolves environment root from `CMD_PROXY_HOME`, then `-Dcmd.proxy.home`, then `~/.cmd-proxy`; also resolves stable instance ID and port overrides.
- Default ports: RPC `10020`, ConfigUI `10528`; actual ConfigUI port may be persisted in `acpConfig.json`.
- Runtime data belongs under the resolved cmd-proxy home, not the Git repository. Confirm the resolved home and instance ID from startup logs before inspecting state.

## Source ownership map

| Concern | Primary location | Start with |
|---|---|---|
| ACP client lifecycle | `acp/acpclient/` | `AbstractAcpClient`, `AcpClient`, `AcpClientRegistry`, `AcpClientFeatureInitializer` |
| Agent providers | `acp/acpclient/agent/` | `AgentProviderRouter`, provider-specific implementations |
| Fast Team | `acp/team/` | `TeamManager`, `TeamCommandHandler`, `TeamStore`, `TeamStartupCoordinator` |
| Team contracts | `acp/team/protocol/`, `model/`, `event/` | `TeamTransportProtocol`, command/model types, `RpcTeamEventSink` |
| TalkTo | `acp/talkto/`, `acp/team/talkto/` | dispatcher, context injector, gateway/card renderer |
| External channels | `acp/channel/` | `ChannelManager`, config store, gateway; `wecom/` for WeCom |
| Memory | `acp/memory/` | `MemoryManager`, file store, loader, registry, recovery |
| Schedules | `acp/schedule/` | `ScheduleTaskManager`, `ScheduleContextInjector` |
| Sub-agents | `acp/subagent/` | dispatcher, ACP client, context injector |
| Ability reflection | `acp/ability/` | reflection service/client/prompt |
| ConfigUI | `acp/configui/ConfigUiServer.java`, `resources/configui/index.html` | HTTP endpoints plus inline UI script |
| MCP mode | Kotlin `app/mcp/`, Java `app/mcpclient/` | `McpProxy`, extension engine, transport clients |
| Shared RPC API | `cmd-proxy-client/src/main/kotlin/.../client/` | `CmdReceiver`, params, responses, config |

Tests mirror these packages under `cmd-proxy-app/src/test/java`. Search by class, route tag, event type, or user-visible behavior to find the closest regression suite.

## High-value documents

- `docs/fast-team-event-delivery-contract.md`: event ordering, admission, and delivery semantics.
- `docs/fast-team-remote-mixed-mvp.md`: local/remote mixed Team scope.
- `docs/fast-team-implementation-cmdproxy-section.md`: cmd-proxy Team implementation plan.
- `docs/acp-talkto-design.md` and `docs/acp-cross-chatter-talkto-design.md`: TalkTo routing.
- `docs/channel-acp-talkto-mvp-design.md`: external channel bridge.
- `docs/acp-memory-system-design.md`: memory ownership and storage.
- `docs/acp-schedule-task-design.md`: schedule ownership and injection.
- `docs/acp-subagent-dispatch-design.md`: sub-agent path.

Treat documents as intent, not proof of current behavior. Verify every important claim against source and tests.

## Useful discovery searches

```bash
rg -n 'CmdReceiver\.register|routeTag|callback' cmd-proxy-app/src/main
rg -n 'class .*Test|@Test' cmd-proxy-app/src/test
rg -n 'ERROR_TEXT_OR_CONFIG_KEY' cmd-proxy-app docs
rg -n 'shutdown|close|reload|retry|queue|lock' cmd-proxy-app/src/main
```
