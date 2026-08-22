#!/usr/bin/env bash
set -euo pipefail

IMAGE_NAME="cmd-proxy-agent-runtime:1.0.0"
CONTAINER_NAME="cmd-proxy-docker"
NETWORK_NAME="cmdproxy-net"
NETWORK_SUBNET="172.30.0.0/24"
NETWORK_GATEWAY="172.30.0.1"
CONTAINER_IP="172.30.0.10"
HOST_HOME_DIR="${HOME:?HOME is required}/cmd-proxy-docker-home"
CLAUDE_SOURCE_HOME="${HOME:?HOME is required}"
CONTAINER_HOME="/home/mola"
RPC_PORT="10020"
CONFIG_UI_PORT="10528"
INSTANCE_ID=""
RESTART_POLICY="no"
DRY_RUN=0

usage() {
    printf '%s\n' \
        "Usage: $0 [options]" \
        "  --image NAME              Image name and tag" \
        "  --container-name NAME     Container and default instance name" \
        "  --host-home PATH          Target-host directory mounted as container home" \
        "  --claude-home PATH        Source home containing .claude.json and .claude/" \
        "  --network NAME            Custom Docker bridge network" \
        "  --subnet CIDR             Network subnet" \
        "  --gateway IP              Network gateway" \
        "  --ip IP                   Fixed container IP" \
        "  --rpc-port PORT           RPC port inside the container" \
        "  --config-ui-port PORT     ConfigUI port inside the container" \
        "  --instance-id ID          cmd-proxy instance ID" \
        "  --restart POLICY          Docker restart policy (default: no)" \
        "  --dry-run                 Print the resolved plan without changing anything" \
        "  -h, --help                Show this help"
}

while (($#)); do
    case "$1" in
        --image)
            IMAGE_NAME="${2:?--image requires a value}"
            shift 2
            ;;
        --container-name)
            CONTAINER_NAME="${2:?--container-name requires a value}"
            shift 2
            ;;
        --host-home)
            HOST_HOME_DIR="${2:?--host-home requires a value}"
            shift 2
            ;;
        --claude-home)
            CLAUDE_SOURCE_HOME="${2:?--claude-home requires a value}"
            shift 2
            ;;
        --network)
            NETWORK_NAME="${2:?--network requires a value}"
            shift 2
            ;;
        --subnet)
            NETWORK_SUBNET="${2:?--subnet requires a value}"
            shift 2
            ;;
        --gateway)
            NETWORK_GATEWAY="${2:?--gateway requires a value}"
            shift 2
            ;;
        --ip)
            CONTAINER_IP="${2:?--ip requires a value}"
            shift 2
            ;;
        --rpc-port)
            RPC_PORT="${2:?--rpc-port requires a value}"
            shift 2
            ;;
        --config-ui-port)
            CONFIG_UI_PORT="${2:?--config-ui-port requires a value}"
            shift 2
            ;;
        --instance-id)
            INSTANCE_ID="${2:?--instance-id requires a value}"
            shift 2
            ;;
        --restart)
            RESTART_POLICY="${2:?--restart requires a value}"
            shift 2
            ;;
        --dry-run)
            DRY_RUN=1
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

INSTANCE_ID="${INSTANCE_ID:-$CONTAINER_NAME}"
HOST_UID="$(id -u)"
HOST_GID="$(id -g)"

case "$HOST_HOME_DIR" in
    /*) ;;
    *)
        printf 'Host home must be an absolute path: %s\n' "$HOST_HOME_DIR" >&2
        exit 1
        ;;
esac

case "$CLAUDE_SOURCE_HOME" in
    /*) ;;
    *)
        printf 'Claude source home must be an absolute path: %s\n' "$CLAUDE_SOURCE_HOME" >&2
        exit 1
        ;;
esac

if [[ "$HOST_HOME_DIR" == "/" || "$HOST_HOME_DIR" == "$CLAUDE_SOURCE_HOME" ]]; then
    printf 'Refusing unsafe or non-isolated host home: %s\n' "$HOST_HOME_DIR" >&2
    exit 1
fi

if [[ ! "$RPC_PORT" =~ ^[0-9]+$ ]] || ((RPC_PORT < 1 || RPC_PORT > 65535)); then
    printf 'Invalid RPC port: %s\n' "$RPC_PORT" >&2
    exit 1
fi
if [[ ! "$CONFIG_UI_PORT" =~ ^[0-9]+$ ]] || ((CONFIG_UI_PORT < 1 || CONFIG_UI_PORT > 65535)); then
    printf 'Invalid ConfigUI port: %s\n' "$CONFIG_UI_PORT" >&2
    exit 1
fi

printf '%s\n' \
    "Deployment plan:" \
    "  image=$IMAGE_NAME" \
    "  container=$CONTAINER_NAME" \
    "  instanceId=$INSTANCE_ID" \
    "  hostHome=$HOST_HOME_DIR" \
    "  claudeSourceHome=$CLAUDE_SOURCE_HOME" \
    "  runtimeUidGid=$HOST_UID:$HOST_GID" \
    "  network=$NETWORK_NAME subnet=$NETWORK_SUBNET gateway=$NETWORK_GATEWAY" \
    "  containerIp=$CONTAINER_IP" \
    "  rpc=$CONTAINER_IP:$RPC_PORT" \
    "  configUi=http://$CONTAINER_IP:$CONFIG_UI_PORT" \
    "  hostPortPublishing=disabled" \
    "  restartPolicy=$RESTART_POLICY"

if ((DRY_RUN)); then
    exit 0
fi

for command_name in docker install cp chmod id curl grep seq sleep uname; do
    command -v "$command_name" >/dev/null || {
        printf 'Required command not found: %s\n' "$command_name" >&2
        exit 1
    }
done

if [[ "$(uname -s)" != "Linux" ]]; then
    printf 'Direct host access to a bridge container IP requires a native Linux Docker host.\n' >&2
    exit 1
fi

docker image inspect "$IMAGE_NAME" >/dev/null
if docker container inspect "$CONTAINER_NAME" >/dev/null 2>&1; then
    printf 'Container already exists; refusing to replace it: %s\n' "$CONTAINER_NAME" >&2
    exit 1
fi

if [[ ! -f "$CLAUDE_SOURCE_HOME/.claude.json" ]]; then
    printf 'Claude configuration file not found: %s/.claude.json\n' "$CLAUDE_SOURCE_HOME" >&2
    exit 1
fi
if [[ ! -d "$CLAUDE_SOURCE_HOME/.claude" ]]; then
    printf 'Claude configuration directory not found: %s/.claude\n' "$CLAUDE_SOURCE_HOME" >&2
    exit 1
fi

install -d -m 700 "$HOST_HOME_DIR"
install -d -m 700 "$HOST_HOME_DIR/.cmd-proxy" "$HOST_HOME_DIR/.cmd-proxy-instances"

if [[ ! -e "$HOST_HOME_DIR/.claude.json" ]]; then
    cp -a "$CLAUDE_SOURCE_HOME/.claude.json" "$HOST_HOME_DIR/.claude.json"
    chmod 600 "$HOST_HOME_DIR/.claude.json"
fi
if [[ ! -e "$HOST_HOME_DIR/.claude" ]]; then
    cp -a "$CLAUDE_SOURCE_HOME/.claude" "$HOST_HOME_DIR/.claude"
    chmod 700 "$HOST_HOME_DIR/.claude"
fi

if docker network inspect "$NETWORK_NAME" >/dev/null 2>&1; then
    EXISTING_SUBNETS="$(docker network inspect "$NETWORK_NAME" --format '{{range .IPAM.Config}}{{println .Subnet}}{{end}}')"
    EXISTING_GATEWAYS="$(docker network inspect "$NETWORK_NAME" --format '{{range .IPAM.Config}}{{println .Gateway}}{{end}}')"
    if ! grep -Fxq "$NETWORK_SUBNET" <<<"$EXISTING_SUBNETS"; then
        printf 'Existing network %s does not use requested subnet %s\n' "$NETWORK_NAME" "$NETWORK_SUBNET" >&2
        exit 1
    fi
    if ! grep -Fxq "$NETWORK_GATEWAY" <<<"$EXISTING_GATEWAYS"; then
        printf 'Existing network %s does not use requested gateway %s\n' "$NETWORK_NAME" "$NETWORK_GATEWAY" >&2
        exit 1
    fi
else
    docker network create \
        --driver bridge \
        --subnet "$NETWORK_SUBNET" \
        --gateway "$NETWORK_GATEWAY" \
        "$NETWORK_NAME" >/dev/null
fi

docker run -d \
    --name "$CONTAINER_NAME" \
    --hostname "$CONTAINER_NAME" \
    --network "$NETWORK_NAME" \
    --ip "$CONTAINER_IP" \
    --user "$HOST_UID:$HOST_GID" \
    --restart "$RESTART_POLICY" \
    --workdir "$CONTAINER_HOME" \
    --env HOME="$CONTAINER_HOME" \
    --env CMD_PROXY_HOME="$CONTAINER_HOME/.cmd-proxy" \
    --env CMD_PROXY_INSTANCE_REGISTRY="$CONTAINER_HOME/.cmd-proxy-instances" \
    --env CMD_PROXY_INSTANCE_ID="$INSTANCE_ID" \
    --env CMD_PROXY_RPC_PORT="$RPC_PORT" \
    --env CMD_PROXY_CONFIG_UI_PORT="$CONFIG_UI_PORT" \
    --mount "type=bind,src=$HOST_HOME_DIR,dst=$CONTAINER_HOME" \
    "$IMAGE_NAME" >/dev/null

for attempt in $(seq 1 20); do
    if [[ "$(docker inspect "$CONTAINER_NAME" --format '{{.State.Running}}')" != "true" ]]; then
        docker logs --tail 80 "$CONTAINER_NAME" >&2
        printf 'Container stopped during startup: %s\n' "$CONTAINER_NAME" >&2
        exit 1
    fi
    if command -v curl >/dev/null \
        && curl --noproxy '*' --connect-timeout 1 --max-time 2 --fail --silent \
            --output /dev/null "http://$CONTAINER_IP:$CONFIG_UI_PORT/"; then
        printf 'ConfigUI ready: http://%s:%s\n' "$CONTAINER_IP" "$CONFIG_UI_PORT"
        break
    fi
    if ((attempt == 20)); then
        printf 'Container is running, but ConfigUI was not confirmed within the startup window.\n' >&2
        printf 'Inspect logs with: docker logs %s\n' "$CONTAINER_NAME" >&2
        exit 1
    fi
    sleep 1
done

docker ps --filter "name=^/$CONTAINER_NAME$" \
    --format 'name={{.Names}} status={{.Status}} networks={{.Networks}} ports={{.Ports}}'
printf 'Host configuration: %s/.cmd-proxy/acpConfig.json\n' "$HOST_HOME_DIR"
printf 'Enter container: docker exec -it %s bash\n' "$CONTAINER_NAME"
