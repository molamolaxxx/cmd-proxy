#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SKILL_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
REPO_ROOT="$(cd "$SKILL_DIR/../../.." && pwd)"

IMAGE_NAME="cmd-proxy-agent-runtime:1.0.0"
CLAUDE_VERSION="2.1.158"
CODEX_ACP_VERSION="1.1.14"
CODEX_CLI_VERSION="0.147.0"
DEEPSEEK_ACP_VERSION="0.4.21"
CLAUDE_HOME="${HOME:?HOME is required}"
JAR_PATH="$REPO_ROOT/cmd-proxy-app/target/cmd-proxy-app-1.0.0-jar-with-dependencies.jar"
PACKAGE_PROJECT=1

usage() {
    printf '%s\n' \
        "Usage: $0 [options]" \
        "  --image NAME             Docker image name and tag" \
        "  --claude-version VERSION Claude Code npm version" \
        "  --codex-acp-version VER  Codex ACP npm version" \
        "  --codex-version VERSION  Codex CLI and native package version" \
        "  --deepseek-acp-version V OpenMA DeepSeek Harness ACP npm version" \
        "  --claude-home PATH       Home containing .claude.json and .claude/" \
        "  --jar PATH               Dependency JAR to place in the image" \
        "  --skip-package           Reuse the existing JAR" \
        "  -h, --help               Show this help"
}

while (($#)); do
    case "$1" in
        --image)
            IMAGE_NAME="${2:?--image requires a value}"
            shift 2
            ;;
        --claude-version)
            CLAUDE_VERSION="${2:?--claude-version requires a value}"
            shift 2
            ;;
        --codex-acp-version)
            CODEX_ACP_VERSION="${2:?--codex-acp-version requires a value}"
            shift 2
            ;;
        --codex-version)
            CODEX_CLI_VERSION="${2:?--codex-version requires a value}"
            shift 2
            ;;
        --deepseek-acp-version)
            DEEPSEEK_ACP_VERSION="${2:?--deepseek-acp-version requires a value}"
            shift 2
            ;;
        --claude-home)
            CLAUDE_HOME="${2:?--claude-home requires a value}"
            shift 2
            ;;
        --jar)
            JAR_PATH="${2:?--jar requires a value}"
            shift 2
            ;;
        --skip-package)
            PACKAGE_PROJECT=0
            shift
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

for command_name in docker node; do
    command -v "$command_name" >/dev/null || {
        printf 'Required command not found: %s\n' "$command_name" >&2
        exit 1
    }
done

if ((PACKAGE_PROJECT)); then
    command -v mvn >/dev/null || {
        printf 'Required command not found: mvn\n' >&2
        exit 1
    }
    mvn -f "$REPO_ROOT/pom.xml" -pl cmd-proxy-app -am -DskipTests package
fi

if [[ ! -s "$JAR_PATH" ]]; then
    printf 'Dependency JAR is missing or empty: %s\n' "$JAR_PATH" >&2
    exit 1
fi
if [[ ! -f "$CLAUDE_HOME/.claude.json" ]]; then
    printf 'Claude configuration file not found: %s/.claude.json\n' "$CLAUDE_HOME" >&2
    exit 1
fi
if [[ ! -d "$CLAUDE_HOME/.claude" ]]; then
    printf 'Claude configuration directory not found: %s/.claude\n' "$CLAUDE_HOME" >&2
    exit 1
fi

BUILD_CONTEXT="$(mktemp -d "${TMPDIR:-/tmp}/cmd-proxy-docker.XXXXXXXX")"
cleanup() {
    rm -rf -- "$BUILD_CONTEXT"
}
trap cleanup EXIT

cp "$SKILL_DIR/assets/Dockerfile" "$BUILD_CONTEXT/Dockerfile"
cp "$JAR_PATH" "$BUILD_CONTEXT/cmd-proxy.jar"
node "$SCRIPT_DIR/sanitize-claude-config.js" "$CLAUDE_HOME" "$BUILD_CONTEXT/claude"

printf 'Building %s with Claude Code %s, Codex ACP %s, Codex CLI %s, DeepSeek ACP %s, access keys redacted...\n' \
    "$IMAGE_NAME" "$CLAUDE_VERSION" "$CODEX_ACP_VERSION" "$CODEX_CLI_VERSION" "$DEEPSEEK_ACP_VERSION"
DOCKER_BUILDKIT=1 docker build \
    --build-arg "CLAUDE_CODE_VERSION=$CLAUDE_VERSION" \
    --build-arg "CODEX_ACP_VERSION=$CODEX_ACP_VERSION" \
    --build-arg "CODEX_CLI_VERSION=$CODEX_CLI_VERSION" \
    --build-arg "DEEPSEEK_ACP_VERSION=$DEEPSEEK_ACP_VERSION" \
    --label "org.opencontainers.image.title=cmd-proxy Agent runtime" \
    --label "org.opencontainers.image.version=1.0.0" \
    --label "com.mola.cmd-proxy.claude-code.version=$CLAUDE_VERSION" \
    --label "com.mola.cmd-proxy.codex-acp.version=$CODEX_ACP_VERSION" \
    --label "com.mola.cmd-proxy.codex-cli.version=$CODEX_CLI_VERSION" \
    --label "com.mola.cmd-proxy.deepseek-acp.version=$DEEPSEEK_ACP_VERSION" \
    --label "com.mola.cmd-proxy.configuration-snapshot=access-key-redacted" \
    --tag "$IMAGE_NAME" \
    "$BUILD_CONTEXT"

docker image inspect --format 'Image: {{.RepoTags}} id={{.Id}} size={{.Size}} bytes' "$IMAGE_NAME"
