---
name: cmd-proxy
description: Understand, diagnose, modify, and validate the cmd-proxy repository. Use for project onboarding, architecture or code-flow questions, ACP/MCP/Fast Team/TalkTo/channel/memory/schedule/ConfigUI troubleshooting, log and test failure analysis, Java or Kotlin implementation work, refactoring, bug fixes, configuration changes, and release-readiness checks in this repository.
---

# cmd-proxy

Work from repository evidence. Re-check the current tree before relying on this guide because the project evolves quickly.

## Start every task

1. Run `git status --short` and preserve unrelated or pre-existing changes.
2. Read the root `pom.xml` and the relevant module `pom.xml` when build behavior matters.
3. Use `rg` to locate symbols, route tags, configuration keys, log messages, and matching tests.
4. Trace the full boundary before changing code: entrypoint -> registry/manager -> transport or persistence -> callback/UI projection -> shutdown.
5. Read only the references needed for the task:
   - Read [project-map.md](references/project-map.md) for onboarding, architecture, ownership, or file discovery.
   - Read [troubleshooting-and-development.md](references/troubleshooting-and-development.md) for diagnosis, implementation, validation, or operational cleanup.

## Choose the workflow

### Understand the project

Build a current map from source rather than summarizing filenames alone.

- Start at `Main.kt` for process modes and bootstrap.
- Start at `AcpProxy.kt` for ACP composition, RPC command registration, lifecycle, and feature wiring.
- Follow interfaces and registries to distinguish authoritative state from client/UI projections.
- Pair implementation files with nearby tests and relevant documents under `docs/`.
- Explain both control flow and ownership: who creates, stores, routes, retries, and closes each resource.

### Diagnose a problem

1. Restate the observable symptom and establish the failing boundary.
2. Collect exact evidence from logs, configuration, runtime identity, ports, persisted state, and focused tests.
3. Search exact error text and identifiers before forming a hypothesis.
4. Trace concurrency and lifecycle explicitly for ACP, Team, callback, TalkTo, reload, and shutdown issues.
5. Separate root cause from downstream symptoms; report evidence and uncertainty.
6. Diagnose only unless the user also asks to fix the problem.

### Implement a change

1. Identify the smallest coherent ownership boundary.
2. Preserve Java 8 compatibility and existing Java/Kotlin interop.
3. Update protocol models, runtime behavior, persistence, UI/config, and tests together when a contract crosses those layers.
4. Keep remote callbacks out of owner locks and command critical paths.
5. Make lifecycle operations idempotent and test failure, retry, queue-full, reload, and shutdown paths where applicable.
6. Prefer precise edits; do not overwrite unrelated dirty-worktree changes.

## Architectural invariants

- Treat cmd-proxy runtime/manager state as authoritative unless a design document explicitly assigns ownership elsewhere; treat callbacks and ConfigUI state as projections.
- Do not perform cross-RPC calls while holding owner or manager locks.
- Do not let slow callbacks block create, delete, compensation, TalkTo, reload, or shutdown commands.
- Distinguish rebuildable lifecycle/UI events from delivery-semantic events. Delivery admission must return an explicit result when a queue is full or closed.
- Preserve FIFO ordering where event order is part of the contract; bound queues and expose rejection/closure behavior to tests.
- Prefer protocol-level ACP session shutdown/cancel capabilities over OS process killing.
- For Fast Team, keep local-only and trusted-local-plus-remote mixed teams distinct; reject remote-only construction unless the current contract has deliberately changed.
- Never repair persistent stores by deleting broad directories or databases. Stop writers, back up, resolve exact IDs/keys, and mutate only the confirmed record as a last resort.

## Validation

Validate in proportion to risk, starting with the smallest relevant test.

```bash
mvn -pl cmd-proxy-app -Dtest=RelevantTest test
mvn -pl cmd-proxy-app test
mvn -pl cmd-proxy-app -DskipTests package
git diff --check
git status --short
```

- Use one or a few focused tests for a simple local change.
- Add module-wide tests for shared lifecycle, protocol, concurrency, persistence, or ConfigUI changes.
- Confirm Surefire reports a nonzero test count; this project pins the JUnit provider to avoid silent zero-test runs.
- For `configui/index.html` inline JavaScript changes, extract or evaluate the affected script with Node `new Function(...)` in addition to exercising the relevant behavior.
- Review the final diff for accidental generated files, secrets, runtime data, and unrelated edits.
- In the handoff, state exactly what ran, what passed, what was skipped, and why.
