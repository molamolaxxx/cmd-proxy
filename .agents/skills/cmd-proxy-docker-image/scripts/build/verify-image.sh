#!/usr/bin/env bash
set -euo pipefail

IMAGE_NAME="cmd-proxy-agent-runtime:1.0.0"

usage() {
    printf '%s\n' \
        "Usage: $0 [options]" \
        "  --image NAME  Docker image name and tag" \
        "  -h, --help    Show this help"
}

while (($#)); do
    case "$1" in
        --image)
            IMAGE_NAME="${2:?--image requires a value}"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            printf 'Unknown argument: %s\n' "$1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

docker image inspect "$IMAGE_NAME" >/dev/null
test "$(docker image inspect --format '{{index .Config.Labels "com.mola.cmd-proxy.configuration-snapshot"}}' "$IMAGE_NAME")" = "access-key-redacted"
DEEPSEEK_ACP_VERSION="$(docker image inspect --format '{{index .Config.Labels "com.mola.cmd-proxy.deepseek-acp.version"}}' "$IMAGE_NAME")"
test -n "$DEEPSEEK_ACP_VERSION"

docker run --rm --env "EXPECTED_DEEPSEEK_ACP_VERSION=$DEEPSEEK_ACP_VERSION" --entrypoint /bin/bash "$IMAGE_NAME" -c '
    set -euo pipefail
    test "$(id -u)" = 1000
    test "$HOME" = /home/mola
    test -s /app/cmd-proxy.jar
    test -s /home/mola/.claude.json
    test -d /home/mola/.claude
    test "$(find /home/mola/.claude -type f | wc -l)" -gt 0
    for command_name in \
        javac mvn curl jq python3 pip3 gcc g++ make pkg-config \
        unzip zip xz zstd file less tree patch ssh scp rsync \
        ip ss ping dig nc lsof ps pstree shellcheck codex codex-acp dsh-acp; do
        command -v "$command_name" >/dev/null || {
            printf "Required Agent tool not found: %s\n" "$command_name" >&2
            exit 1
        }
    done
    node - <<"NODE"
const fs = require("fs");
const path = require("path");
const dir = "/home/mola/.claude";
const files = [
    "/home/mola/.claude.json",
    ...fs.readdirSync(dir).filter((name) => /^settings.*\.json$/i.test(name)).map((name) => path.join(dir, name)),
];
const apiKeyName = /api.?key|access.?key/i;
let fields = 0;
function verify(value) {
    if (Array.isArray(value)) return value.forEach(verify);
    if (!value || typeof value !== "object") return;
    for (const [key, child] of Object.entries(value)) {
        if (apiKeyName.test(key) && typeof child === "string") {
            fields++;
            if (child !== "[REDACTED]") throw new Error(`Unredacted API/access key field: ${key}`);
        } else verify(child);
    }
}
for (const file of files) verify(JSON.parse(fs.readFileSync(file, "utf8")));
if (fields === 0) throw new Error("No redacted API/access key fields found");
console.log(`Verified redacted API/access-key fields: ${fields}`);
NODE
    java -version
    javac -version
    mvn -version
    node --version
    node_major="$(node -p "process.versions.node.split(\".\")[0]")"
    if ((node_major < 22)); then
        printf "Node.js 22 or newer is required; found %s\n" "$(node --version)" >&2
        exit 1
    fi
    npm --version
    npx --version
    claude --version
    codex --version
    node - <<"NODE"
const root = "/opt/codex/lib/node_modules";
const acp = require(`${root}/@agentclientprotocol/codex-acp/package.json`);
const cli = require(`${root}/@openai/codex/package.json`);
const platform = process.arch === "x64" ? "linux-x64"
    : process.arch === "arm64" ? "linux-arm64" : null;
if (!platform) throw new Error(`Unsupported Codex architecture: ${process.arch}`);
const nativePackage = require(`${root}/@openai/codex-${platform}/package.json`);
if (nativePackage.version !== `${cli.version}-${platform}`) {
    throw new Error(`Codex native package mismatch: CLI=${cli.version}, native=${nativePackage.version}`);
}
console.log(`Codex ACP ${acp.version}; CLI ${cli.version}; native ${nativePackage.version}`);
NODE
    node - <<"NODE"
const pkg = require("/opt/deepseek/lib/node_modules/@openma/deepseek-harness-acp/package.json");
if (pkg.version !== process.env.EXPECTED_DEEPSEEK_ACP_VERSION) {
    throw new Error(`Unexpected DeepSeek ACP version: ${pkg.version}; expected ${process.env.EXPECTED_DEEPSEEK_ACP_VERSION}`);
}
console.log(`DeepSeek ACP ${pkg.version}`);
NODE
    test "$TZ" = Asia/Shanghai
    test -e /usr/share/zoneinfo/Asia/Shanghai
    vim --version | sed -n "1p"
'

docker image inspect --format 'Entrypoint={{json .Config.Entrypoint}} Cmd={{json .Config.Cmd}} User={{.Config.User}}' "$IMAGE_NAME"
docker image inspect --format 'Claude={{index .Config.Labels "com.mola.cmd-proxy.claude-code.version"}} CodexACP={{index .Config.Labels "com.mola.cmd-proxy.codex-acp.version"}} CodexCLI={{index .Config.Labels "com.mola.cmd-proxy.codex-cli.version"}} DeepSeekACP={{index .Config.Labels "com.mola.cmd-proxy.deepseek-acp.version"}} ConfigSnapshot={{index .Config.Labels "com.mola.cmd-proxy.configuration-snapshot"}}' "$IMAGE_NAME"
printf 'Verified image %s\n' "$IMAGE_NAME"
