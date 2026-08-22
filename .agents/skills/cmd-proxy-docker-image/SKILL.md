---
name: cmd-proxy-docker-image
description: Build, verify, replace, clean up, and prepare Docker Hub upload commands for the single Ubuntu-based cmd-proxy Agent runtime image. The image contains the project JAR, Java development tools, Node.js 22+/npm/npx, Claude Code, Codex ACP/CLI with its native Linux package, Python, build and network diagnostics, Vim, and a Claude configuration snapshot whose API/access-key values are redacted. Use when Codex needs to rebuild the image, refresh embedded Agent settings, diagnose Claude or Codex ACP runtime compatibility, safely replace an existing isolated container while preserving its runtime configuration, remove superseded images or tar artifacts, or prepare user-run Docker Hub commands.
---

# Build and refresh the cmd-proxy image

Treat the image as sensitive even after access-key redaction: it intentionally retains the rest of the Claude configuration snapshot, including settings, plugins, history, and project metadata. Push it only when the user explicitly authorizes that exposure.

## Script map

Run scripts from the skill directory or use their absolute paths.

| Category | Script | Purpose |
| --- | --- | --- |
| Build | `scripts/build/build-image.sh` | Package cmd-proxy, sanitize the Claude snapshot, and build the canonical image |
| Build | `scripts/build/verify-image.sh` | Verify tools, versions, native Codex package, configuration presence, and redaction |
| Build | `scripts/build/sanitize-claude-config.js` | Internal helper used by the build script; do not invoke for deployment |
| Deploy | `scripts/deploy/run-container.sh` | Deploy a locally built image with an isolated bind-mounted home |
| Deploy | `scripts/deploy/pull-run-container.sh` | Pull the Docker Hub image and deploy it with a persistent named volume |
| Publish | `scripts/publish/push-dockerhub.sh` | Verify, tag, push, and remove only the temporary Hub-qualified local tag |

## Build

Maintain exactly one canonical local image, `cmd-proxy-agent-runtime:1.0.0`. Do not create separate public/private variants. Do not generate a docker-save tar or checksum file.

Run from the skill directory:

```bash
scripts/build/build-image.sh
```

The script packages the current Maven sources, stages the dependency JAR and Claude configuration in a temporary context, replaces API/access-key field values with `[REDACTED]`, removes exact copies of real key values from other text files in the snapshot, builds `cmd-proxy-agent-runtime:1.0.0`, and removes the temporary context. Preserve `.claude.json` and a nonempty `.claude/`; redact key values only instead of deleting either configuration source.

The final image remains based on Ubuntu 24.04 and includes JDK 17/Maven, Python 3, native build tools, HTTP/JSON/archive utilities, SSH/file synchronization, network/process diagnostics, ShellCheck, and Asia/Shanghai timezone data. It copies Node.js 22 with npm/npx from the official Node runtime image. It also installs pinned Codex ACP and Codex CLI packages plus an explicit architecture-matched `@openai/codex-linux-x64` or `@openai/codex-linux-arm64` package; do not rely only on npm optional-dependency resolution. Keep Node.js at version 22 or newer: current ACP bridge releases reject older engines or use syntax that Node.js 18 cannot parse.

Use `--image`, `--claude-version`, `--codex-acp-version`, `--codex-version`, `--claude-home`, or `--jar` to override defaults. Keep the Codex CLI and explicit native package on the same version. Use `--skip-package` only after confirming the JAR represents the current source tree.

Verify the image:

```bash
scripts/build/verify-image.sh
```

Verification must fail when the image reports a Node.js major version below 22, when `codex` or `codex-acp` is missing, or when the Codex CLI and native platform package versions differ. After deployment, verify the real ACP boundary for both configured providers by checking that logs contain successful ACP initialization and Client-ready messages instead of `EBADENGINE`, `Unexpected token 'with'`, `Missing optional dependency`, or an ACP child-process exit.

It must also verify the `access-key-redacted` image label, the presence of `.claude.json`, a nonempty `.claude/`, and redacted API/access-key fields. Treat any surviving exact key value as a build failure.

## Deploy an isolated Linux environment

Use native Linux Docker bridge networking when the container must reuse the same port numbers as a host process without publishing them. Docker Desktop hosts do not normally route directly to bridge container IPs; use explicit port publishing or another network design there.

Preview the target-specific plan without changing anything:

```bash
scripts/deploy/run-container.sh --dry-run
```

Deploy with defaults derived from the target account's `$HOME`:

```bash
scripts/deploy/run-container.sh
```

Override any machine-specific value instead of editing the script:

```bash
scripts/deploy/run-container.sh \
  --host-home "$HOME/my-cmd-proxy-container-home" \
  --network cmdproxy-isolated \
  --subnet 172.30.0.0/24 \
  --gateway 172.30.0.1 \
  --ip 172.30.0.10 \
  --container-name cmd-proxy-docker
```

The deployment script:

- binds one target-host directory to the image's container home;
- creates independent `.cmd-proxy` and `.cmd-proxy-instances` directories there;
- never copies or mounts the target host's existing `.cmd-proxy`;
- copies `.claude.json` and `.claude/` only when absent, producing an independent snapshot;
- creates or validates a custom bridge network and fixed container IP;
- keeps RPC and ConfigUI on their normal container ports without `-p` host publishing;
- starts with a distinct instance ID and verifies ConfigUI through the container IP;
- uses the deploying account's UID/GID so the bind mount remains writable.

Choose a subnet that does not overlap target-host LAN, VPN, or existing Docker routes. Configure different robot names and a different `chatterIds` set before activating both host and container instances; separate files and IPs do not prevent remote MolaChat identity conflicts.

## Pull and deploy from Docker Hub

Use the named-volume deployment script on a native Linux Docker host when the image has already been published:

```bash
scripts/deploy/pull-run-container.sh --dry-run
scripts/deploy/pull-run-container.sh
```

The script pulls `molamolaxxx/cmd-proxy-agent-runtime:1.0.0`, verifies its redaction label, validates or creates the fixed bridge network, creates the persistent `cmd-proxy-agent-home` volume, starts the container without host port publishing, confirms ConfigUI, and verifies that `.claude.json` and a nonempty `.claude/` survived volume initialization. Override the image, network, IP, volume, container, ports, instance ID, or restart policy through command-line options.

Use a new volume name for a new isolated environment. Reusing a volume deliberately reuses its Claude/Codex credentials and cmd-proxy state. Never delete the volume during ordinary container replacement.

## Refresh an existing container

Replace an existing container transactionally. Do not use the fresh-deployment script against an existing name; it intentionally refuses replacement.

1. Inspect the existing container before changing it. Record its exact image ID, running state, user, restart policy, working directory, environment, mounts, network names, aliases, and fixed IP. Confirm which bind mount or volume contains the independent container home and cmd-proxy data.
2. Build and verify the canonical image before stopping the old container.
3. Stop the old container and rename it to a unique backup name. Keep this backup until the replacement passes all checks.
4. Create the replacement under the original name with the same home/data mounts, user, environment, restart policy, network, aliases, and IP. Change only the image unless the user requested another runtime change.
5. Confirm the replacement is running, its image ID is the newly built ID, ConfigUI returns HTTP 200 through the container IP, and every configured ACP provider reaches successful initialization and Client-ready state. A transient optional-package message is not decisive if the baked native fallback starts and the final provider state is ready.
6. If any check fails, remove only the failed replacement, restore the backup's original name, and start it when it was previously running.
7. Remove the backup container only after all checks succeed.

Prefer protocol-level ACP shutdown or session handling when available. Use container lifecycle operations only for the requested image replacement.

## Clean superseded artifacts

Perform cleanup only after the replacement is healthy:

- Resolve exact candidate container and image IDs first. Keep the ID used by the healthy replacement.
- Remove superseded cmd-proxy tags and image IDs explicitly. Do not use broad cleanup such as `docker image prune -a`.
- If multiple tags reference one image ID, remove the unwanted tags before deciding whether the underlying image is removable.
- Delete old cmd-proxy image tar and checksum files by exact path. Future builds must not recreate them.
- Confirm the end state: one canonical cmd-proxy image tag and ID, no cmd-proxy tar artifact, and one healthy replacement container using that ID.

Do not disturb unrelated dangling images, containers, volumes, networks, or user files.

## Prepare Docker Hub handoff

By default, let the user perform the upload. Do not invoke the publishing script unless the user explicitly asks the current task to upload. Preview the exact target without changing Docker state:

```bash
scripts/publish/push-dockerhub.sh --dry-run
```

After the user authorizes the public metadata exposure, upload with:

```bash
scripts/publish/push-dockerhub.sh --confirm-public-metadata
```

Pass `--expected-id SHA256` when the build's exact image ID has already been recorded. The script checks the redaction label, runs full image verification, refuses to overwrite a conflicting local Hub tag, pushes only `1.0.0`, and removes only the temporary Hub-qualified local tag after completion. It must retain the canonical local image. Before suggesting a public upload, remind the user that access-key values are redacted but the retained Claude settings, plugins, history, and project metadata can still be sensitive.

## Operate and troubleshoot

Use the selected container name with standard Docker commands:

```bash
docker logs --follow cmd-proxy-docker
docker exec -it cmd-proxy-docker bash
docker stop cmd-proxy-docker
docker start cmd-proxy-docker
```

Edit the container environment's `acpConfig.json` under the mounted host-home `.cmd-proxy/` directory, or use Vim inside the container.

If HTTP access to the fixed bridge IP unexpectedly reaches a corporate proxy, bypass proxies for the container IP. For example:

```bash
curl --noproxy '*' http://CONTAINER_IP:CONFIG_UI_PORT/
```

Add the container IP or subnet to the browser/system `NO_PROXY` or direct-connect list. An empty initial configuration starts ConfigUI first; RPC and robot services start after valid robots and chatter IDs are configured and refreshed.
