#!/usr/bin/env bash
set -euo pipefail

IMAGE_NAME="molamolaxxx/cmd-proxy-agent-runtime:1.0.0"
CONTAINER_NAME="cmd-proxy-docker"
NETWORK_NAME="cmdproxy-net"
NETWORK_SUBNET="172.30.0.0/24"
NETWORK_GATEWAY="172.30.0.1"
CONTAINER_IP="172.30.0.10"
HOME_VOLUME="cmd-proxy-agent-home"
RPC_PORT="10020"
CONFIG_UI_PORT="10528"
INSTANCE_ID=""
RESTART_POLICY="unless-stopped"
DRY_RUN=0

usage() {
    printf '%s\n' \
        "Usage: $0 [options]" \
        "  --image NAME              Docker Hub image name and tag" \
        "  --container-name NAME     Container and default instance name" \
        "  --network NAME            Custom Docker bridge network" \
        "  --subnet CIDR             Network subnet" \
        "  --gateway IP              Network gateway" \
        "  --ip IP                   Fixed container IP" \
        "  --home-volume NAME        Persistent volume mounted at /home/mola" \
        "  --rpc-port PORT           RPC port inside the container" \
        "  --config-ui-port PORT     ConfigUI port inside the container" \
        "  --instance-id ID          cmd-proxy instance ID" \
        "  --restart POLICY          Docker restart policy" \
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
        --home-volume)
            HOME_VOLUME="${2:?--home-volume requires a value}"
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

for port_value in "$RPC_PORT" "$CONFIG_UI_PORT"; do
    if [[ ! "$port_value" =~ ^[0-9]+$ ]] || ((port_value < 1 || port_value > 65535)); then
        printf 'Invalid port: %s\n' "$port_value" >&2
        exit 1
    fi
done

printf '%s\n' \
    "Docker Hub deployment plan:" \
    "  image=$IMAGE_NAME" \
    "  container=$CONTAINER_NAME" \
    "  instanceId=$INSTANCE_ID" \
    "  homeVolume=$HOME_VOLUME" \
    "  network=$NETWORK_NAME subnet=$NETWORK_SUBNET gateway=$NETWORK_GATEWAY" \
    "  containerIp=$CONTAINER_IP" \
    "  rpc=$CONTAINER_IP:$RPC_PORT" \
    "  configUi=http://$CONTAINER_IP:$CONFIG_UI_PORT" \
    "  hostPortPublishing=disabled" \
    "  restartPolicy=$RESTART_POLICY"

if ((DRY_RUN)); then
    exit 0
fi

for command_name in docker curl grep seq sleep uname; do
    command -v "$command_name" >/dev/null || {
        printf 'Required command not found: %s\n' "$command_name" >&2
        exit 1
    }
done

if [[ "$(uname -s)" != "Linux" ]]; then
    printf 'Direct host access to a bridge container IP requires a native Linux Docker host.\n' >&2
    exit 1
fi
if docker container inspect "$CONTAINER_NAME" >/dev/null 2>&1; then
    printf 'Container already exists; refusing to replace it: %s\n' "$CONTAINER_NAME" >&2
    exit 1
fi

docker pull "$IMAGE_NAME"
if [[ "$(docker image inspect "$IMAGE_NAME" --format '{{index .Config.Labels "com.mola.cmd-proxy.configuration-snapshot"}}')" != "access-key-redacted" ]]; then
    printf 'Pulled image lacks the expected access-key-redacted label: %s\n' "$IMAGE_NAME" >&2
    exit 1
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

docker volume create "$HOME_VOLUME" >/dev/null

docker run -d \
    --name "$CONTAINER_NAME" \
    --hostname "$CONTAINER_NAME" \
    --network "$NETWORK_NAME" \
    --ip "$CONTAINER_IP" \
    --restart "$RESTART_POLICY" \
    --user 1000:1000 \
    --workdir /home/mola \
    --env HOME=/home/mola \
    --env CMD_PROXY_HOME=/home/mola/.cmd-proxy \
    --env CMD_PROXY_INSTANCE_REGISTRY=/home/mola/.cmd-proxy-instances \
    --env CMD_PROXY_INSTANCE_ID="$INSTANCE_ID" \
    --env CMD_PROXY_RPC_PORT="$RPC_PORT" \
    --env CMD_PROXY_CONFIG_UI_PORT="$CONFIG_UI_PORT" \
    --mount "type=volume,src=$HOME_VOLUME,dst=/home/mola" \
    "$IMAGE_NAME" >/dev/null

for attempt in $(seq 1 30); do
    if [[ "$(docker inspect "$CONTAINER_NAME" --format '{{.State.Running}}')" != "true" ]]; then
        docker logs --tail 100 "$CONTAINER_NAME" >&2
        printf 'Container stopped during startup: %s\n' "$CONTAINER_NAME" >&2
        exit 1
    fi
    if curl --noproxy '*' --connect-timeout 1 --max-time 2 --fail --silent \
        --output /dev/null "http://$CONTAINER_IP:$CONFIG_UI_PORT/"; then
        break
    fi
    if ((attempt == 30)); then
        docker logs --tail 100 "$CONTAINER_NAME" >&2
        printf 'Container is running, but ConfigUI was not confirmed within the startup window.\n' >&2
        exit 1
    fi
    sleep 1
done

docker exec "$CONTAINER_NAME" /bin/bash -c \
    'test -s /home/mola/.claude.json && test -d /home/mola/.claude && test "$(find /home/mola/.claude -type f | wc -l)" -gt 0'

docker ps --filter "name=^/$CONTAINER_NAME$" \
    --format 'name={{.Names}} status={{.Status}} image={{.Image}} networks={{.Networks}}'
printf 'ConfigUI ready: http://%s:%s/\n' "$CONTAINER_IP" "$CONFIG_UI_PORT"
printf 'Enter container: docker exec -it %s bash\n' "$CONTAINER_NAME"
printf 'Configure Claude/Codex credentials inside the persistent volume before using authenticated ACP providers.\n'
