#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERIFY_SCRIPT="$SCRIPT_DIR/../build/verify-image.sh"

LOCAL_IMAGE="cmd-proxy-agent-runtime:1.0.0"
DOCKERHUB_NAMESPACE="molamolaxxx"
REPOSITORY="cmd-proxy-agent-runtime"
REMOTE_TAG="1.0.0"
EXPECTED_IMAGE_ID=""
CONFIRM_PUBLIC_METADATA=0
DRY_RUN=0

usage() {
    printf '%s\n' \
        "Usage: $0 [options]" \
        "  --local-image NAME          Local image name and tag" \
        "  --namespace NAME            Docker Hub namespace" \
        "  --repository NAME           Docker Hub repository" \
        "  --tag TAG                   Docker Hub tag" \
        "  --expected-id SHA256        Require this exact local image ID" \
        "  --confirm-public-metadata   Acknowledge retained Claude metadata" \
        "  --dry-run                   Print the resolved plan without pushing" \
        "  -h, --help                  Show this help"
}

while (($#)); do
    case "$1" in
        --local-image)
            LOCAL_IMAGE="${2:?--local-image requires a value}"
            shift 2
            ;;
        --namespace)
            DOCKERHUB_NAMESPACE="${2:?--namespace requires a value}"
            shift 2
            ;;
        --repository)
            REPOSITORY="${2:?--repository requires a value}"
            shift 2
            ;;
        --tag)
            REMOTE_TAG="${2:?--tag requires a value}"
            shift 2
            ;;
        --expected-id)
            EXPECTED_IMAGE_ID="${2:?--expected-id requires a value}"
            shift 2
            ;;
        --confirm-public-metadata)
            CONFIRM_PUBLIC_METADATA=1
            shift
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

REMOTE_IMAGE="$DOCKERHUB_NAMESPACE/$REPOSITORY:$REMOTE_TAG"
if [[ "$LOCAL_IMAGE" == "$REMOTE_IMAGE" ]]; then
    printf 'Local and remote-qualified image names must differ: %s\n' "$LOCAL_IMAGE" >&2
    exit 1
fi

printf '%s\n' \
    "Docker Hub upload plan:" \
    "  localImage=$LOCAL_IMAGE" \
    "  remoteImage=$REMOTE_IMAGE" \
    "  expectedImageId=${EXPECTED_IMAGE_ID:-not-pinned}" \
    "  pushLatest=false" \
    "  removeRemoteLocalTagAfterPush=true"

if ((DRY_RUN)); then
    exit 0
fi

if ((CONFIRM_PUBLIC_METADATA == 0)); then
    printf '%s\n' \
        'Refusing public upload without --confirm-public-metadata.' \
        'API/access-key values are redacted, but Claude settings, plugins, history, and project metadata remain.' >&2
    exit 1
fi

command -v docker >/dev/null || {
    printf 'Required command not found: docker\n' >&2
    exit 1
}

ACTUAL_IMAGE_ID="$(docker image inspect "$LOCAL_IMAGE" --format '{{.Id}}')"
REDACTION_LABEL="$(docker image inspect "$LOCAL_IMAGE" --format '{{index .Config.Labels "com.mola.cmd-proxy.configuration-snapshot"}}')"

if [[ -n "$EXPECTED_IMAGE_ID" && "$ACTUAL_IMAGE_ID" != "$EXPECTED_IMAGE_ID" ]]; then
    printf 'Image ID mismatch. Expected %s, found %s\n' "$EXPECTED_IMAGE_ID" "$ACTUAL_IMAGE_ID" >&2
    exit 1
fi
if [[ "$REDACTION_LABEL" != "access-key-redacted" ]]; then
    printf 'Image lacks the access-key-redacted label: %s\n' "$LOCAL_IMAGE" >&2
    exit 1
fi

"$VERIFY_SCRIPT" --image "$LOCAL_IMAGE"

if docker image inspect "$REMOTE_IMAGE" >/dev/null 2>&1; then
    EXISTING_REMOTE_ID="$(docker image inspect "$REMOTE_IMAGE" --format '{{.Id}}')"
    if [[ "$EXISTING_REMOTE_ID" != "$ACTUAL_IMAGE_ID" ]]; then
        printf 'Refusing to overwrite local tag %s; it points to %s\n' "$REMOTE_IMAGE" "$EXISTING_REMOTE_ID" >&2
        exit 1
    fi
fi

TAG_CREATED=0
cleanup_remote_tag() {
    if ((TAG_CREATED)); then
        docker image rm "$REMOTE_IMAGE" >/dev/null 2>&1 || true
    fi
}
trap cleanup_remote_tag EXIT

docker tag "$LOCAL_IMAGE" "$REMOTE_IMAGE"
TAG_CREATED=1
docker push "$REMOTE_IMAGE"

printf 'Uploaded %s from image ID %s\n' "$REMOTE_IMAGE" "$ACTUAL_IMAGE_ID"
printf 'Canonical local image retained: %s\n' "$LOCAL_IMAGE"
