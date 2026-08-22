# Troubleshooting and development

## Evidence-first triage

Capture these facts before changing code:

- exact command/mode (`mcp` or `acp`) and first causal exception;
- resolved `CMD_PROXY_HOME`, instance ID, RPC port, and ConfigUI port from startup logs;
- relevant `acpConfig.json` section with credentials and secrets redacted;
- source group ID, robot name, chatter ID, team ID/member ID, request ID, and remote instance ID as applicable;
- whether the symptom occurs before registration, during routing, in callback delivery, or only in UI/state projection;
- current Git status and the closest focused test result.

Do not assume repository `logs/` represents the active process. Follow the actual launch output or configured service logs.

## Symptom routing

| Symptom | Inspect first | Common boundary |
|---|---|---|
| Process refuses to start | `Main.kt`, `CmdProxyHome`, `InstanceRegistry`, port allocator | duplicate environment ownership, identity/config conflict, occupied port |
| ConfigUI starts but no robots run | `acpConfig.json`, `Main.kt`, `AcpRobotParam` | empty/disabled robots or chatter IDs, incompatible role flags |
| ACP prompt/session failure | provider router, `AbstractAcpClient`, registry, response listeners | provider process/protocol, session identity, lifecycle guard |
| TalkTo missing or duplicated | dispatcher, context injector, gateway, callback registration | target resolution, admission, inbox/route semantics, retry |
| Team create/delete/list timeout | `TeamCommandHandler`, `TeamManager`, `RpcTeamEventSink` | callback in critical path, owner lock crossing RPC, lifecycle race |
| Team disappears or returns after restart | `TeamStore`, startup coordinator, source snapshots | runtime/persisted/projection divergence, recovery ordering |
| Remote/mixed Team failure | transport descriptor/protocol, source descriptors, capability snapshot | trust/placement mismatch, remote-only request, stale discovery |
| Channel inbound/outbound issue | `ChannelManager`, channel config store, adapter/gateway | enablement, reload boundary, message parsing, attachment handling |
| Memory lost or injected incorrectly | memory registry/manager/store/loader and pending recovery | wrong scope/home, shared namespace, interrupted extraction |
| Scheduled task wrong owner/context | schedule manager/model/context injector | owner isolation, context capability injection, reload/recovery |
| UI behavior or save issue | `ConfigUiServer`, `configui/index.html` | endpoint contract drift, inline JS syntax, save vs reload semantics |

## Concurrency and callback checks

For Team, TalkTo, reload, and shutdown defects, write down the participating threads and locks. Confirm:

1. which component owns authoritative state;
2. where a command becomes committed;
3. whether a callback occurs before the command response;
4. whether any remote call is made while holding an owner/manager lock;
5. queue capacity, FIFO guarantees, rejection behavior, retries, and close behavior;
6. whether the event is rebuildable projection data or requires explicit delivery admission.

Slow callback, queue-full, close-while-submit, repeated shutdown, and create/delete prompt-return tests are high-value regression cases.

## Persistent-state repair

Prefer the application delete/recovery API. For any manual repair:

1. Identify the actual runtime home and persistence technology from source.
2. Stop all writers and verify locks are released.
3. Make a recoverable backup.
4. Inspect a copy or use a read-only path first.
5. Resolve the exact team/session/task/memory ID and exact key/file.
6. Ask for confirmation before a destructive mutation when scope is not already explicit.
7. Remove only the confirmed record, then restart and validate discovery, list/home/capability, and routing behavior.

Never clear the entire cmd-proxy home or a whole LevelDB/database to fix one record.

## Implementation checklist

- Keep request/response and protocol changes backward-aware across cmd-proxy and its consumers.
- Update equality/serialization/model tests when fields change.
- Keep config field migrations simple when the intended contract replaces an old field; do not add compatibility bridges without a real consumer need.
- Separate saving configuration from refreshing a running external channel unless the feature explicitly requires automatic reload.
- Keep Team remarks/display labels separate from identity and routing fallback values.
- Sanitize external outbound messages and honor private/group message boundaries.
- Make cleanup exact and idempotent; unregister callbacks, close executors, and release clients in ownership order.

## Validation ladder

Choose the lowest sufficient tier, then expand when failures or shared boundaries warrant it.

### Focused

```bash
mvn -pl cmd-proxy-app -Dtest=ClassName test
mvn -pl cmd-proxy-app -Dtest=ClassName#methodName test
```

Use for a local bug fix or a small behavior change. Add focused tests for both success and the discovered failure mode.

### Module regression

```bash
mvn -pl cmd-proxy-app test
```

Use for shared ACP lifecycle, Team, TalkTo, persistence, scheduling, channel, or protocol changes. Verify Surefire did not report zero tests.

### Packaging and patch hygiene

```bash
mvn -pl cmd-proxy-app -DskipTests package
git diff --check
git status --short
```

Confirm both the ordinary JAR and dependency assembly are produced. Inspect the diff without reverting or overwriting pre-existing changes.

### ConfigUI JavaScript

For inline script edits, isolate the affected script and compile it with Node `new Function(scriptText)` or an equivalent syntax-only check. Also validate the related server endpoint and user flow; syntax success alone is insufficient.

## Handoff format

Lead with the outcome. Then state:

- root cause or implemented behavior;
- key files changed;
- tests/checks with counts or pass/fail status;
- checks not run and the reason;
- remaining runtime or cross-project verification needed;
- confirmation that unrelated working-tree changes were preserved.
