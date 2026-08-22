# syntax=docker/dockerfile:1

FROM node:22-bookworm-slim AS node-runtime

FROM ubuntu:24.04

ARG CLAUDE_CODE_VERSION=2.1.158
ARG CODEX_ACP_VERSION=1.1.14
ARG CODEX_CLI_VERSION=0.147.0
ARG DEEPSEEK_ACP_VERSION=0.4.21

ENV DEBIAN_FRONTEND=noninteractive \
    HOME=/home/mola \
    TZ=Asia/Shanghai \
    CMD_PROXY_HOME=/data/cmd-proxy \
    CMD_PROXY_RPC_PORT=10020 \
    CMD_PROXY_CONFIG_UI_PORT=10528 \
    DISABLE_AUTOUPDATER=1 \
    PATH=/opt/deepseek/bin:/opt/codex/bin:/opt/claude/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt/lists,sharing=locked \
    apt-get -o Acquire::Retries=5 -o Acquire::http::Timeout=30 update \
    && apt-get -o Acquire::Retries=5 -o Acquire::http::Timeout=30 install -y --no-install-recommends \
        bash \
        build-essential \
        ca-certificates \
        curl \
        dnsutils \
        file \
        git \
        iproute2 \
        iputils-ping \
        jq \
        less \
        lsof \
        maven \
        netcat-openbsd \
        openjdk-17-jdk-headless \
        openssh-client \
        patch \
        pkg-config \
        procps \
        psmisc \
        python3 \
        python3-pip \
        python3-venv \
        ripgrep \
        rsync \
        shellcheck \
        tini \
        tree \
        tzdata \
        unzip \
        vim \
        xz-utils \
        zip \
        zstd \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
    PATH=/usr/lib/jvm/java-17-openjdk-amd64/bin:${PATH}

COPY --from=node-runtime /usr/local/ /usr/local/

RUN test "$(node -p 'Number(process.versions.node.split(`.`)[0]) >= 22')" = true \
    && npm install --global --prefix /opt/claude "@anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}" \
    && case "$(dpkg --print-architecture)" in \
        amd64) codex_platform="linux-x64" ;; \
        arm64) codex_platform="linux-arm64" ;; \
        *) printf 'Unsupported Codex architecture: %s\n' "$(dpkg --print-architecture)" >&2; exit 1 ;; \
    esac \
    && npm install --global --prefix /opt/codex --include=optional \
        "@agentclientprotocol/codex-acp@${CODEX_ACP_VERSION}" \
        "@openai/codex@${CODEX_CLI_VERSION}" \
        "@openai/codex-${codex_platform}@npm:@openai/codex@${CODEX_CLI_VERSION}-${codex_platform}" \
    && npm install --global --prefix /opt/deepseek \
        "@openma/deepseek-harness-acp@${DEEPSEEK_ACP_VERSION}" \
    && npm cache clean --force \
    && node --version \
    && npm --version \
    && /opt/claude/bin/claude --version \
    && /opt/codex/bin/codex --version \
    && test -x /opt/codex/bin/codex-acp \
    && test "$(node -p "require('/opt/codex/lib/node_modules/@agentclientprotocol/codex-acp/package.json').version")" = "${CODEX_ACP_VERSION}" \
    && test "$(node -p "require('/opt/codex/lib/node_modules/@openai/codex/package.json').version")" = "${CODEX_CLI_VERSION}" \
    && test "$(node -p "require('/opt/codex/lib/node_modules/@openai/codex-${codex_platform}/package.json').version")" = "${CODEX_CLI_VERSION}-${codex_platform}" \
    && test -x /opt/deepseek/bin/dsh-acp \
    && test "$(node -p "require('/opt/deepseek/lib/node_modules/@openma/deepseek-harness-acp/package.json').version")" = "${DEEPSEEK_ACP_VERSION}" \
    && npx --version

RUN if id ubuntu >/dev/null 2>&1; then \
        rm -rf /home/mola/.npm /home/mola/.codex \
        && usermod --login mola --home /home/mola ubuntu \
        && groupmod --new-name mola ubuntu; \
    else \
        useradd --create-home --uid 1000 --shell /bin/bash mola; \
    fi \
    && install -d -o mola -g mola /app /data/cmd-proxy /workspace \
    && chown mola:mola /home/mola

ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

COPY --chown=mola:mola cmd-proxy.jar /app/cmd-proxy.jar
COPY --chown=mola:mola claude/.claude.json /home/mola/.claude.json
COPY --chown=mola:mola claude/.claude/ /home/mola/.claude/

USER mola
WORKDIR /workspace

EXPOSE 10020 10528
VOLUME ["/data/cmd-proxy"]

ENTRYPOINT ["/usr/bin/tini", "--", "java", "-jar", "/app/cmd-proxy.jar"]
CMD ["acp"]
