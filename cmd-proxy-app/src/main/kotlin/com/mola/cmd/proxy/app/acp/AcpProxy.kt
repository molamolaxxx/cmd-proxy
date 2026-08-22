package com.mola.cmd.proxy.app.acp

import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import com.mola.cmd.proxy.app.acp.ability.AbilityReflectionService
import com.mola.cmd.proxy.app.acp.acpclient.AbstractAcpClient
import com.mola.cmd.proxy.app.acp.acpclient.AcpClient
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientFeatureInitializer
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientRegistry
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProviderRouter
import com.mola.cmd.proxy.app.acp.common.PathUtils
import com.mola.cmd.proxy.app.acp.memory.MemoryManager
import com.mola.cmd.proxy.app.acp.memory.MemoryManagerRegistry
import com.mola.cmd.proxy.app.acp.memory.PendingMemoryExtractionRecovery
import com.mola.cmd.proxy.app.acp.memory.model.MemoryConfig
import com.mola.cmd.proxy.app.acp.subagent.SubAgentContextInjector
import com.mola.cmd.proxy.app.acp.subagent.SubAgentDispatcher
import com.mola.cmd.proxy.app.acp.schedule.ScheduleTaskManager
import com.mola.cmd.proxy.app.acp.schedule.ScheduleContextInjector
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduleOwnerKey
import com.mola.cmd.proxy.app.acp.acpclient.PromptOptions
import com.mola.cmd.proxy.app.acp.acpclient.PromptCommandResult
import com.mola.cmd.proxy.app.acp.talkto.TalkToContextInjector
import com.mola.cmd.proxy.app.acp.talkto.TalkToDispatcher
import com.mola.cmd.proxy.app.acp.channel.ChannelManager
import com.mola.cmd.proxy.app.acp.channel.model.ChannelConfig
import com.mola.cmd.proxy.app.acp.channel.model.ChannelStatus
import com.mola.cmd.proxy.app.acp.channel.wecom.WeComChannelAdapter
import com.mola.cmd.proxy.app.acp.team.TeamClientRegistry
import com.mola.cmd.proxy.app.acp.team.TeamCommandHandler
import com.mola.cmd.proxy.app.acp.team.TeamManager
import com.mola.cmd.proxy.app.acp.team.TeamStore
import com.mola.cmd.proxy.app.acp.team.MapTeamSourceRobotResolver
import com.mola.cmd.proxy.app.acp.team.TeamStartupCoordinator
import com.mola.cmd.proxy.app.acp.team.TeamSharedSourceIds
import com.mola.cmd.proxy.app.acp.team.TeamSharingStatusRegistry
import com.mola.cmd.proxy.app.acp.team.event.RpcTeamEventSink
import com.mola.cmd.proxy.app.acp.team.listener.TeamAcpResponseListener
import com.mola.cmd.proxy.app.acp.team.protocol.TeamMemberSourceDescriptor
import com.mola.cmd.proxy.app.acp.team.protocol.RemoteTeamMemberSourceDescriptor
import com.mola.cmd.proxy.app.acp.talkto.ContactRemarkResolver
import com.mola.cmd.proxy.app.acp.team.protocol.TeamTransportDescriptor
import com.mola.cmd.proxy.app.acp.team.protocol.TeamTransportProtocol
import com.mola.cmd.proxy.app.acp.team.talkto.TeamTalkToContextInjector
import com.mola.cmd.proxy.app.utils.CmdProxyHome
import com.mola.cmd.proxy.client.provider.CmdReceiver
import com.mola.cmd.proxy.client.resp.CmdResponseContent
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object AcpProxy {

    private val log: Logger = LoggerFactory.getLogger(AcpProxy::class.java)

    private val registry: AcpClientRegistry = AcpClientRegistry.getInstance()

    /** 普通、Team 与 sub-agent 共用的 manager/存储锁注册表。 */
    private val memoryManagers = MemoryManagerRegistry()

    /** 防止共享 history namespace 的多个 client 在同一代际重复恢复同一 pending。 */
    private val pendingMemoryRecoveryClaims = ConcurrentHashMap<String, String>()

    /** groupId -> AbilityReflectionService */
    private val abilityServices = ConcurrentHashMap<String, AbilityReflectionService>()

    /** 全局 robot 注册表，name -> AcpRobotParam */
    private val globalRobotRegistry = ConcurrentHashMap<String, AcpRobotParam>()

    /** 包含禁用项的完整配置注册表，供记忆执行 Robot 等配置引用解析。 */
    private val configuredRobotRegistry = ConcurrentHashMap<String, AcpRobotParam>()

    /** 定时任务管理器（全局单例） */
    private val scheduleTaskManager = ScheduleTaskManager()

    /** robotName → groupId 反向索引，用于 talkTo 查找目标 client */
    private val robotToGroupIdMap = ConcurrentHashMap<String, String>()

    /** Team 可选 sourceGroupId → robot 配置，只供 Team 创建和恢复。 */
    private val globalGroupRobotRegistry = ConcurrentHashMap<String, AcpRobotParam>()

    /** TalkTo 消息投递器（全局单例） */
    private lateinit var talkToDispatcher: TalkToDispatcher

    /** 当前实例的外部信道生命周期；只绑定普通 MAIN client。 */
    private var channelManager: ChannelManager? = null

    /** 普通与 Team client 共用的完整能力挂点编排器。 */
    private lateinit var featureInitializer: AcpClientFeatureInitializer

    /** Team 权威 runtime；本阶段只恢复定义与负责统一关闭。 */
    private var teamManager: TeamManager? = null

    /** 当前 cmd-proxy 实例的稳定 Team transport 描述。 */
    private lateinit var teamTransportDescriptor: TeamTransportDescriptor

    /** 普通 ACP 与 Team discovery 共用的可重放同步快照。 */
    private lateinit var acpSyncRobotsSnapshot: AcpSyncRobotsSnapshot

    /** MolaChat 重启后用于重新发现每个 cmd-proxy 实例的周期快照重放。 */
    private var acpSyncRobotsHeartbeat: AcpSyncRobotsHeartbeat? = null

    /** 使用对象字段保证热重载后既有 RPC handler 转发到新 TeamManager。 */
    private var teamCommandHandler: TeamCommandHandler? = null

    /** robot 级重载不得悄悄改变全实例 chatter 维度。 */
    private var activeChatterIds: List<String> = emptyList()

    /** 包含禁用项的配置身份集合，用于允许既有 robot 做角色级热重载。 */
    private val configuredRobotNames = ConcurrentHashMap.newKeySet<String>()

    private val shutdownHookRegistered = AtomicBoolean(false)

    private var autoNewSessionExecutor: ScheduledExecutorService? = null
    private val autoNewSessionLastChecks = ConcurrentHashMap<String, Long>()

    @Synchronized
    fun start(
        cmdGroupList: List<String>,
        robotsJson: String? = null,
        chatterIdsJson: String? = null,
        groupWorkDirMap: Map<String, String> = emptyMap(),
        groupRobotMap: Map<String, AcpRobotParam> = emptyMap(),
        teamSourceGroupRobotMap: Map<String, AcpRobotParam> = emptyMap(),
        configuredRobots: Collection<AcpRobotParam> = emptyList(),
        channels: List<ChannelConfig> = emptyList()
    ) {
        ensureShutdownHook()
        if (teamManager != null) {
            throw IllegalStateException(
                "AcpProxy is already started; stop it before starting a new generation")
        }
        activeChatterIds = if (chatterIdsJson.isNullOrBlank()) {
            emptyList()
        } else {
            JSON.parseArray(chatterIdsJson, String::class.java).toList()
        }
        configuredRobots.filter { it.name.isNotBlank() }.forEach {
            configuredRobotRegistry[it.name] = it
            configuredRobotNames.add(it.name)
        }
        // 注册命令
        CmdReceiver.register("acp", "acp") { param ->
            mutableMapOf<String, String>()
        }

        // 构建全局 robot 注册表（在冷加载之前，供子 Agent 派发使用）
        for ((groupId, robot) in groupRobotMap) {
            if (robot != null && robot.name.isNotBlank()) {
                globalRobotRegistry[robot.name] = robot
            }
        }
        globalGroupRobotRegistry.putAll(teamSourceGroupRobotMap)
        registerSharedTeamSources(teamSourceGroupRobotMap.values)

        // 构建 robotName → groupId 反向索引（取第一个）
        for ((groupId, robot) in groupRobotMap) {
            if (robot != null && robot.name.isNotBlank()) {
                robotToGroupIdMap.putIfAbsent(robot.name, groupId)
            }
        }

        // 初始化 TalkTo 消息投递器
        talkToDispatcher = TalkToDispatcher(
            globalRobotRegistry, registry, robotToGroupIdMap
        )
        featureInitializer = createFeatureInitializer()
        initializeTeamTransport()
        acpSyncRobotsSnapshot = AcpSyncRobotsSnapshot(
            robotsJson, chatterIdsJson, teamTransportDescriptor)

        // 同一个命令既建立 callback consumer，也作为 MolaChat 重连后的主动握手。
        // handler 只在 Team 恢复与 13 个 routeTag 注册完成后才暴露，避免首次 sync
        // 观察到 businessCommandsReady=false 的中间态。
        CmdReceiver.register(
            "acpSyncRobots", "acpSyncRobots",
            "Replay ordinary ACP robots and Fast Team discovery after reconnect"
        ) { param ->
            log.info("acpSyncRobots 主动握手, requestId={}", param.cmdId)
            acpSyncRobotsSnapshot.resultMap()
        }

        // 注册 crossTalkTo callback 通道（建立到 MolaChat 的 RPC 连接）
        CmdReceiver.register("crossTalkTo", "crossTalkTo") { param ->
            // dummy handler：MolaChat 不会 send crossTalkTo 到 cmd-proxy
            // 注册的目的是建立 RPC 连接，使 CmdReceiver.callback() 有可用的 consumer
            mutableMapOf<String, String>("result" to "ok")
        }

        // 注册 cmdGroupList 维度的命令处理器
        registerGroupCommands(cmdGroupList)

        // 冷加载：并行启动所有 groupId 的 client
        val latch = CountDownLatch(cmdGroupList.size)
        val executor = if (cmdGroupList.isEmpty()) null else Executors.newFixedThreadPool(
            minOf(cmdGroupList.size, Runtime.getRuntime().availableProcessors() * 2)
        )

        for (groupId in cmdGroupList) {
            executor?.submit {
                try {
                    val workDir = groupWorkDirMap[groupId]
                    val robot = groupRobotMap[groupId]

                    // onlySubAgent 的 robot 不启动 AcpClient，只做 ability 反思
                    if (robot != null && robot.isOnlySubAgent) {
                        initAbilityReflectionStandalone(groupId, robot)
                        log.info("onlySubAgent robot 跳过 client 创建, groupId={}, robot={}", groupId, robot.name)
                        return@submit
                    }

                    registry.createSession(groupId, workDir, robot) { client ->
                        featureInitializer.initialize(
                            AcpClientFeatureInitializer.Context.main(groupId), client, robot)
                    }

                    log.info("ACP client 冷加载完成, groupId={}, robot={}, workDir={}, memory={}, subAgents={}",
                        groupId, robot?.name ?: "unknown", workDir ?: "default",
                        robot?.isMemoryEnabled ?: false,
                        robot?.subAgents?.map { it.name } ?: emptyList<String>())
                } catch (e: Exception) {
                    log.error("ACP client 冷加载失败, groupId={}", groupId, e)
                } finally {
                    latch.countDown()
                }
            }
        }

        // 等待所有 client 启动完成
        latch.await()
        executor?.shutdown()
        log.info("所有 ACP client 冷加载完成, 共 {} 个", cmdGroupList.size)

        // client 和普通 TalkTo dispatcher 均就绪后再开放外部消息入口。
        channelManager = ChannelManager(
            channels,
            CmdProxyHome.instanceId(),
            registry,
            talkToDispatcher,
            teamManager,
            ChannelManager.AdapterFactory { channel, bridge ->
                WeComChannelAdapter(channel, bridge)
            }
        ).also { it.start() }
        log.info("外部信道启动完成, configured={}, statuses={}",
            channels.size, channelManager?.statuses ?: emptyMap<String, ChannelStatus>())

        // 启动定时任务调度器
        startScheduler(groupRobotMap)
        startAutoNewSession()

        // 会话注册完成后，调用 acpSyncRobots 通知服务端同步 robot 信息
        if (!robotsJson.isNullOrBlank() && !chatterIdsJson.isNullOrBlank()) {
            try {
                publishAcpSyncRobots()
                log.info("acpSyncRobots 回调已发送")
            } catch (e: Exception) {
                log.error("acpSyncRobots 回调发送失败", e)
            }
        }
        acpSyncRobotsHeartbeat = AcpSyncRobotsHeartbeat(
            acpSyncRobotsSnapshot,
            { snapshot -> publishAcpSyncRobots(snapshot) },
            20L,
            TimeUnit.SECONDS
        ).also { it.start() }
        log.info("acpSyncRobots heartbeat 已启动, intervalSeconds=20")

        log.info("AcpProxy 命令注册完成")
    }

    /** 发布与主动握手完全相同的权威快照。 */
    private fun publishAcpSyncRobots() {
        publishAcpSyncRobots(acpSyncRobotsSnapshot.resultMap())
    }

    private fun publishAcpSyncRobots(snapshot: Map<String, String>) {
        CmdReceiver.callback(
            "acpSyncRobots", "acpSyncRobots",
            CmdResponseContent(
                UUID.randomUUID().toString(),
                snapshot
            )
        )
    }

    /** 注册实例级稳定 transport，并仅在权威状态恢复成功后开放业务命令。 */
    private fun initializeTeamTransport() {
        teamTransportDescriptor = TeamTransportDescriptor.forInstance(
            CmdProxyHome.instanceId(), teamMemberSourceDescriptors(),
            remoteTeamMemberSourceDescriptors())
        val sourceResolver = MapTeamSourceRobotResolver(globalGroupRobotRegistry)
        val eventSink = RpcTeamEventSink()
        val teamRegistry = TeamClientRegistry()
        val managerHolder = AtomicReference<TeamManager>()
        val startupCoordinator = TeamStartupCoordinator(
            sourceResolver,
            { runtime, member, source, options, onClientCreated ->
                val team = runtime.definition
                val robot = source.copyRobotParam()
                val identity = AcpClientIdentity.team(
                    member.acpClientId,
                    team.transportGroup,
                    "team/${team.teamId}/${member.teamMemberId}",
                    team.ownerChatterId,
                    team.teamId,
                    member.teamMemberId,
                    member.sourceRobotName
                )
                val client = AcpClient(robot.workDir, identity, robot)
                onClientCreated.accept(client)
                try {
                    client.globalListener = TeamAcpResponseListener(
                        runtime, member, eventSink
                    ) { teamId, memberId, state, error ->
                        managerHolder.get()?.onMemberState(teamId, memberId, state, error)
                    }
                    client.setForceNewSession(options.isForceNewSession)
                    if (options.targetRestoreSessionId != null) {
                        client.setTargetRestoreSessionId(options.targetRestoreSessionId)
                    }
                    featureInitializer.initialize(
                        AcpClientFeatureInitializer.Context.team(
                            member.acpClientId, member.sourceGroupId,
                            team.teamId, member.teamMemberId),
                        client, robot
                    )
                    client.start()
                    client
                } catch (e: Exception) {
                    try {
                        client.close()
                    } catch (_: Exception) {
                    }
                    throw e
                }
            },
            teamRegistry,
            minOf(4, maxOf(1, Runtime.getRuntime().availableProcessors())),
            360_000L
        )
        val manager = TeamManager(
            TeamStore(),
            teamRegistry,
            sourceResolver,
            eventSink,
            startupCoordinator
        )
        manager.setScheduleCleanup(scheduleTaskManager::cleanupTeam)
        manager.setScheduleTaskManager(scheduleTaskManager)
        manager.setScheduleOrphanCleanup(
            scheduleTaskManager::cleanupOrphanTeams,
            scheduleTaskManager::teamOwnerCount,
            scheduleTaskManager::teamOwnerCount
        )
        manager.setMemoryDreamTrigger { sourceGroupId, workspacePath ->
            val memoryManager = memoryManagers.get(sourceGroupId).orElse(null)
                ?: return@setMemoryDreamTrigger false
            if (!memoryManager.config.isWriteEnabled) {
                return@setMemoryDreamTrigger false
            }
            memoryManager.triggerDream(workspacePath)
            true
        }
        managerHolder.set(manager)
        TeamSharingStatusRegistry.setSupplier { teamSharingStatuses() }
        // 恢复流程会异步启动成员，initializer 必须在恢复前就能解析到权威 manager。
        teamManager = manager
        var recovered = false
        try {
            val recoveredCount = manager.recoverPersistedDefinitions(
                teamTransportDescriptor.transportGroup)
            manager.reconcileRevokedGrants()
            manager.startResourceReaper()
            recovered = true
            log.info("Team 持久定义恢复完成, count={}", recoveredCount)
        } catch (e: Exception) {
            log.error("Team 持久定义恢复失败；业务命令保持未开放", e)
        }
        teamCommandHandler = null
        if (recovered) {
            teamTransportDescriptor =
                TeamTransportDescriptor.readyForBusiness(
                    CmdProxyHome.instanceId(), teamMemberSourceDescriptors(),
                    remoteTeamMemberSourceDescriptors())
            teamCommandHandler =
                TeamCommandHandler(manager, teamTransportDescriptor.transportGroup)
        }

        CmdReceiver.register(
            TeamTransportProtocol.DESCRIBE_COMMAND,
            teamTransportDescriptor.transportGroup,
            "Fast Team transport discovery"
        ) { param ->
            TeamTransportProtocol.describeResult(
                param.cmdId, teamTransportDescriptor, manager.metricsSnapshot()
            )
        }
        if (recovered) {
            CmdReceiver.register(
                TeamTransportProtocol.CREATE_COMMAND,
                teamTransportDescriptor.transportGroup,
                "Create a persisted Fast Team definition"
            ) { param ->
                teamCommandHandler?.handleCreate(param.cmdId, param.cmdArgs)
                    ?: teamUnavailableResult(param.cmdId)
            }
            CmdReceiver.register(
                TeamTransportProtocol.LIST_COMMAND,
                teamTransportDescriptor.transportGroup,
                "List owner-scoped Fast Team definitions"
            ) { param ->
                teamCommandHandler?.handleList(param.cmdId, param.cmdArgs)
                    ?: teamUnavailableResult(param.cmdId)
            }
            CmdReceiver.register(
                TeamTransportProtocol.GET_COMMAND,
                teamTransportDescriptor.transportGroup,
                "Get one owner-scoped Fast Team definition"
            ) { param ->
                teamCommandHandler?.handleGet(param.cmdId, param.cmdArgs)
                    ?: teamUnavailableResult(param.cmdId)
            }
            CmdReceiver.register(
                TeamTransportProtocol.DELETE_COMMAND,
                teamTransportDescriptor.transportGroup,
                "Delete one Fast Team with an idempotent DELETING barrier"
            ) { param ->
                teamCommandHandler?.handleDelete(param.cmdId, param.cmdArgs)
                    ?: teamUnavailableResult(param.cmdId)
            }
            CmdReceiver.register(
                TeamTransportProtocol.SEND_COMMAND,
                teamTransportDescriptor.transportGroup,
                "Send a message to one READY Team member"
            ) { param ->
                teamCommandHandler?.handleSend(param.cmdId, param.cmdArgs)
                    ?: teamUnavailableResult(param.cmdId)
            }
            CmdReceiver.register(
                TeamTransportProtocol.CANCEL_COMMAND,
                teamTransportDescriptor.transportGroup,
                "Cancel one Team member prompt"
            ) { param ->
                teamCommandHandler?.handleCancel(param.cmdId, param.cmdArgs)
                    ?: teamUnavailableResult(param.cmdId)
            }
            CmdReceiver.register(
                TeamTransportProtocol.NEW_SESSION_COMMAND,
                teamTransportDescriptor.transportGroup,
                "Create a new session for one Team member"
            ) { param ->
                teamCommandHandler?.handleNewSession(param.cmdId, param.cmdArgs)
                    ?: teamUnavailableResult(param.cmdId)
            }
            CmdReceiver.register(
                TeamTransportProtocol.LIST_SESSIONS_COMMAND,
                teamTransportDescriptor.transportGroup,
                "List sessions for one Team member"
            ) { param ->
                teamCommandHandler?.handleListSessions(param.cmdId, param.cmdArgs)
                    ?: teamUnavailableResult(param.cmdId)
            }
            CmdReceiver.register(
                TeamTransportProtocol.RESTORE_SESSION_COMMAND,
                teamTransportDescriptor.transportGroup,
                "Restore one Team member session"
            ) { param ->
                teamCommandHandler?.handleRestoreSession(param.cmdId, param.cmdArgs)
                    ?: teamUnavailableResult(param.cmdId)
            }
            CmdReceiver.register(
                TeamTransportProtocol.GET_STATUS_COMMAND,
                teamTransportDescriptor.transportGroup,
                "Get one Team member status"
            ) { param ->
                teamCommandHandler?.handleGetStatus(param.cmdId, param.cmdArgs)
                    ?: teamUnavailableResult(param.cmdId)
            }
            CmdReceiver.register(
                TeamTransportProtocol.GET_CONTEXT_USAGE_COMMAND,
                teamTransportDescriptor.transportGroup,
                "Get one Team member context usage"
            ) { param ->
                teamCommandHandler?.handleGetContextUsage(param.cmdId, param.cmdArgs)
                    ?: teamUnavailableResult(param.cmdId)
            }
            CmdReceiver.register(
                TeamTransportProtocol.MEMORY_DREAM_COMMAND,
                teamTransportDescriptor.transportGroup,
                "Trigger memory dream for one Team member using its source robot memory owner"
            ) { param ->
                teamCommandHandler?.handleMemoryDream(param.cmdId, param.cmdArgs)
                    ?: teamUnavailableResult(param.cmdId)
            }
            CmdReceiver.register(
                TeamTransportProtocol.TALK_TO_DELIVER_COMMAND,
                teamTransportDescriptor.transportGroup,
                "Deliver one routed mixed-Team talkTo message to a local member"
            ) { param ->
                teamCommandHandler?.handleTalkToDeliver(param.cmdId, param.cmdArgs)
                    ?: teamUnavailableResult(param.cmdId)
            }
        }
        log.info("Fast Team transport 已注册, instanceId={}, transportGroup={}, commands={}",
            teamTransportDescriptor.cmdProxyInstanceId,
            teamTransportDescriptor.transportGroup,
            teamTransportDescriptor.commands)
    }

    private fun teamUnavailableResult(requestId: String): Map<String, String> {
        return linkedMapOf(
            "schemaVersion" to "1",
            "requestId" to requestId,
            "accepted" to "false",
            "code" to "TEAM_NOT_READY",
            "message" to "Fast Team runtime is not ready"
        )
    }

    private fun promptCommandResult(result: PromptCommandResult): Map<String, String> {
        return linkedMapOf(
            "accepted" to result.isAccepted.toString(),
            "code" to result.code,
            // 保留旧调用一直读取的 result 字段。
            "result" to result.result
        )
    }

    /**
     * 为指定 groupId 列表注册所有 cmdGroupList 维度的命令处理器。
     * 此方法在 {@link #start} 和 {@link #reloadRobot} 中复用，
     * 确保新启用的 robot 也能接受 MolaChat 的命令。
     */
    private fun registerGroupCommands(groupIds: List<String>) {
        // 注册 crossTalkToDeliver 命令处理器（接收 MolaChat 转发的跨 chatter 消息）
        CmdReceiver.register("crossTalkToDeliver", groupIds, "接收跨chatter的talkTo消息") { params ->
            val resultMap = mutableMapOf<String, String>()
            try {
                val jsonStr = params.cmdArgs[0]
                val json = com.alibaba.fastjson.JSON.parseObject(jsonStr)
                val senderChatterId = json.getString("senderChatterId") ?: ""
                val senderRobotName = json.getString("senderRobotName") ?: ""
                val targetRobotName = json.getString("targetRobotName") ?: ""
                val content = json.getString("content") ?: ""
                val depth = json.getIntValue("depth")
                val authPrincipalId = json.getString("authPrincipalId")
                val authContext = if (authPrincipalId.isNullOrBlank()) null else
                    com.mola.cmd.proxy.app.acp.mcpauth.AuthPrincipalContext(
                        authPrincipalId,
                        json.getString("authPrincipalName") ?: "",
                        json.getString("authSourceType") ?: "CHANNEL",
                        json.getString("authSourceId") ?: "")

                val targetGroupId = robotToGroupIdMap[targetRobotName]
                val targetClient = if (targetGroupId != null) registry.getClient(targetGroupId) else null
                val targetRobot = targetClient?.robotParam

                if (targetClient == null || targetRobot == null) {
                    log.warn("crossTalkToDeliver 目标不存在: targetRobotName={}", targetRobotName)
                    resultMap["result"] = "目标 robot '$targetRobotName' 不存在或未启动"
                    resultMap["success"] = "false"
                    return@register resultMap
                }

                val senderFullName = "$senderChatterId:$senderRobotName"
                val message = com.mola.cmd.proxy.app.acp.talkto.model.TalkToMessage(
                    senderFullName, content, depth + 1, emptyList(), authContext
                )

                if (targetClient.state == com.mola.cmd.proxy.app.acp.acpclient.AbstractAcpClient.State.READY) {
                    talkToDispatcher.pushIncomingMessageCard(targetClient, message)
                    com.mola.cmd.proxy.app.acp.talkto.TalkToDispatcher.sendInboundMessage(
                        targetClient, message)
                    log.info("crossTalkToDeliver 直接投递: {}:{} → {}", senderChatterId, senderRobotName, targetRobot.name)
                    resultMap["result"] = "已直接投递"
                    resultMap["success"] = "true"
                } else {
                    val delivered = talkToDispatcher.offerToInbox(targetRobot.name, message)
                    if (delivered) {
                        log.info("crossTalkToDeliver 入队: {}:{} → {}", senderChatterId, senderRobotName, targetRobot.name)
                        resultMap["result"] = "目标忙碌，已放入 inbox"
                        resultMap["success"] = "true"
                    } else {
                        log.warn("crossTalkToDeliver inbox 已满: {}:{} → {}", senderChatterId, senderRobotName, targetRobot.name)
                        resultMap["result"] = "目标 inbox 已满"
                        resultMap["success"] = "false"
                    }
                }
            } catch (e: Exception) {
                log.error("crossTalkToDeliver 处理失败", e)
                resultMap["result"] = "处理异常: ${e.message}"
                resultMap["success"] = "false"
            }
            resultMap
        }

        CmdReceiver.register("acpCancelPrompt", groupIds, "取消ACP当前正在进行的prompt turn，groupId必填") { params ->
            val resultMap = mutableMapOf<String, String>()
            try {
                val param: JSONObject = JSON.parse(params.cmdArgs[0]) as JSONObject
                val groupId = param.getString("groupId")
                if (groupId.isNullOrBlank()) {
                    resultMap["result"] = "groupId不能为空"
                    resultMap["accepted"] = "false"
                    resultMap["code"] = "REJECTED_STATE"
                    return@register resultMap
                }
                resultMap.putAll(promptCommandResult(registry.cancelPromptWithResult(groupId)))
            } catch (e: Exception) {
                log.error("acpCancelPrompt 失败", e)
                resultMap["result"] = "取消失败: ${e.message}"
                resultMap["accepted"] = "false"
                resultMap["code"] = "CANCEL_FAILED"
            }
            resultMap
        }

        CmdReceiver.register("acpNewSession", groupIds, "会话上下文清除，groupId必填") { params ->
            val resultMap = mutableMapOf<String, String>()
            try {
                val param: JSONObject = JSON.parse(params.cmdArgs[0]) as JSONObject
                val groupId = param.getString("groupId")
                if (groupId.isNullOrBlank()) {
                    resultMap["result"] = "groupId不能为空"
                    return@register resultMap
                }
                val client = registry.getClient(groupId)
                if (client == null || client.state != AbstractAcpClient.State.READY) {
                    resultMap["result"] = "当前client状态不为READY，不允许清除上下文"
                    return@register resultMap
                }

                val newClient = replaceMainSession(groupId, client, null)
                resultMap["result"] = if (newClient == null) {
                    "会话状态已变化，未重复开启新会话"
                } else {
                    notifyMainSessionChanged(
                        groupId, client.sessionId, newClient.sessionId, "MANUAL")
                    "已开启新会话"
                }
            } catch (e: Exception) {
                log.error("acpNewSession 失败", e)
                resultMap["result"] = "开启新会话失败: ${e.message}"
            }
            resultMap
        }

        CmdReceiver.register("acpSendMessage", groupIds,
            "向ACP会话发送消息，groupId和message必填，files可选，busyPolicy可选INTERRUPT") { params ->
            val resultMap = mutableMapOf<String, String>()
            try {
                val param: JSONObject = JSON.parse(params.cmdArgs[0]) as JSONObject
                val groupId = param.getString("groupId")
                val message = param.getString("message")
                if (groupId.isNullOrBlank()) {
                    resultMap["result"] = "groupId不能为空"
                    resultMap["accepted"] = "false"
                    resultMap["code"] = "REJECTED_STATE"
                    return@register resultMap
                }
                if (message.isNullOrBlank()) {
                    resultMap["result"] = "message不能为空"
                    resultMap["accepted"] = "false"
                    resultMap["code"] = "REJECTED_STATE"
                    return@register resultMap
                }
                val files: MutableList<Map<String, String>>? = if (param.containsKey("files")) {
                    param.getJSONArray("files")?.map { item ->
                        val jsonObj = item as JSONObject
                        val map = mutableMapOf<String, String>()
                        for (key in jsonObj.keys) {
                            map[key] = jsonObj.getString(key)
                        }
                        map as Map<String, String>
                    }?.toMutableList()
                } else null
                resultMap.putAll(registry.sendMessageWithResult(
                    groupId, message, files,
                    param.getString("busyPolicy")).let(::promptCommandResult))
            } catch (e: Exception) {
                log.error("acpSendMessage 失败", e)
                resultMap["result"] = "发送消息失败: ${e.message}"
                resultMap["accepted"] = "false"
                resultMap["code"] = "REJECTED_STATE"
            }
            resultMap
        }

        CmdReceiver.register("acpGetStatus", groupIds, "获取ACP会话状态，groupId必填") { params ->
            val resultMap = mutableMapOf<String, String>()
            try {
                val param: JSONObject = JSON.parse(params.cmdArgs[0]) as JSONObject
                val groupId = param.getString("groupId")
                if (groupId.isNullOrBlank()) {
                    resultMap["result"] = "groupId不能为空"
                    return@register resultMap
                }
                val client = registry.getClient(groupId)
                if (client != null) {
                    resultMap["result"] = client.state.name
                }
            } catch (e: Exception) {
                log.error("acpGetStatus 失败", e)
                resultMap["result"] = "获取状态失败: ${e.message}"
            }
            resultMap
        }

        CmdReceiver.register("acpGetContextUsage", groupIds, "获取ACP会话上下文使用占比，groupId必填") { params ->
            val resultMap = mutableMapOf<String, String>()
            try {
                val param: JSONObject = JSON.parse(params.cmdArgs[0]) as JSONObject
                val groupId = param.getString("groupId")
                val client = registry.getClient(groupId)
                if (client != null) {
                    resultMap["result"] = client.contextUsagePercentage.toString()
                }
            } catch (e: Exception) {
                log.error("acpGetContextUsage 失败", e)
                resultMap["result"] = "-1"
            }
            resultMap
        }

        CmdReceiver.register("acpListSessions", groupIds, "获取最近N个会话列表，groupId必填，limit可选(默认7)") { params ->
            val resultMap = mutableMapOf<String, String>()
            try {
                val param: JSONObject = JSON.parse(params.cmdArgs[0]) as JSONObject
                val groupId = param.getString("groupId")
                if (groupId.isNullOrBlank()) {
                    resultMap["result"] = "groupId不能为空"
                    return@register resultMap
                }
                val limit = param.getIntValue("limit").let { if (it <= 0) 7 else it }
                val client = registry.getClient(groupId)
                if (client == null) {
                    resultMap["result"] = "会话不存在"
                    return@register resultMap
                }
                val currentSessionId = client.sessionId
                val sessions = client.historyManager.listRecentSessions(limit)
                val arr = com.alibaba.fastjson.JSONArray()
                for (s in sessions) {
                    if (arr.size >= limit) break
                    val obj = JSONObject()
                    obj["sessionId"] = s.sessionId
                    val isCurrent = s.sessionId == currentSessionId
                    obj["preview"] = s.preview
                    obj["lastModified"] = s.lastModified
                    obj["current"] = isCurrent
                    arr.add(obj)
                }
                resultMap["result"] = arr.toJSONString()
            } catch (e: Exception) {
                log.error("acpListSessions 失败", e)
                resultMap["result"] = "查询失败: ${e.message}"
            }
            resultMap
        }

        CmdReceiver.register("acpRestoreSession", groupIds, "恢复指定历史会话，groupId和sessionId必填") { params ->
            val resultMap = mutableMapOf<String, String>()
            try {
                val param: JSONObject = JSON.parse(params.cmdArgs[0]) as JSONObject
                val groupId = param.getString("groupId")
                val sessionId = param.getString("sessionId")
                if (groupId.isNullOrBlank()) {
                    resultMap["result"] = "groupId不能为空"
                    return@register resultMap
                }
                if (sessionId.isNullOrBlank()) {
                    resultMap["result"] = "sessionId不能为空"
                    return@register resultMap
                }
                val client = registry.getClient(groupId)
                if (client == null || client.state != AbstractAcpClient.State.READY) {
                    resultMap["result"] = "当前client状态不允许恢复会话"
                    return@register resultMap
                }

                if (sessionId == client.sessionId) {
                    resultMap["result"] = "当前已是该会话，无需切换"
                    return@register resultMap
                }

                registry.restoreSession(groupId, sessionId) { restored ->
                    featureInitializer.initialize(
                        AcpClientFeatureInitializer.Context.main(groupId),
                        restored, restored.robotParam)
                }

                val newClient = registry.getClient(groupId)
                if (newClient != null) {
                    Thread {
                        try {
                            replaySessionSnapshot(groupId, newClient)
                        } catch (e: Exception) {
                            log.error("发送会话快照失败, groupId={}, sessionId={}", groupId, sessionId, e)
                        }
                    }.start()
                }
            } catch (e: Exception) {
                log.error("acpRestoreSession 失败", e)
                resultMap["result"] = "恢复失败: ${e.message}"
            }
            resultMap
        }

        // ==================== 记忆管理命令 ====================

        CmdReceiver.register("acpMemoryList", groupIds, "列出当前项目的所有记忆，groupId必填") { params ->
            val resultMap = mutableMapOf<String, String>()
            try {
                val param: JSONObject = JSON.parse(params.cmdArgs[0]) as JSONObject
                val groupId = param.getString("groupId")
                if (groupId.isNullOrBlank()) {
                    resultMap["result"] = "groupId不能为空"
                    return@register resultMap
                }
                val mgr = memoryManagers.get(groupId).orElse(null)
                if (mgr == null) {
                    resultMap["result"] = "该 robot 未启用记忆系统"
                    return@register resultMap
                }
                val client = registry.getClient(groupId)
                if (client == null) {
                    resultMap["result"] = "会话不存在"
                    return@register resultMap
                }
                val memories = mgr.listMemories(client.workspacePath)
                if (memories.isEmpty()) {
                    resultMap["result"] = "暂无记忆"
                } else {
                    val sb = StringBuilder()
                    for ((i, entry) in memories.withIndex()) {
                        sb.append("${i + 1}. [${entry.type}] ${entry.title}：${entry.summary} (id=${entry.id})\n")
                    }
                    resultMap["result"] = sb.toString()
                }
            } catch (e: Exception) {
                log.error("acpMemoryList 失败", e)
                resultMap["result"] = "查询记忆失败: ${e.message}"
            }
            resultMap
        }

        CmdReceiver.register("acpMemoryDelete", groupIds, "删除指定记忆，groupId和memoryId必填") { params ->
            val resultMap = mutableMapOf<String, String>()
            try {
                val param: JSONObject = JSON.parse(params.cmdArgs[0]) as JSONObject
                val groupId = param.getString("groupId")
                val memoryId = param.getString("memoryId")
                if (groupId.isNullOrBlank()) {
                    resultMap["result"] = "groupId不能为空"
                    return@register resultMap
                }
                if (memoryId.isNullOrBlank()) {
                    resultMap["result"] = "memoryId不能为空"
                    return@register resultMap
                }
                val mgr = memoryManagers.get(groupId).orElse(null)
                if (mgr == null) {
                    resultMap["result"] = "该 robot 未启用记忆系统"
                    return@register resultMap
                }
                val client = registry.getClient(groupId)
                if (client == null) {
                    resultMap["result"] = "会话不存在"
                    return@register resultMap
                }
                val success = mgr.deleteMemory(client.workspacePath, memoryId)
                resultMap["result"] = if (success) "记忆已删除: $memoryId" else "记忆不存在: $memoryId"
            } catch (e: Exception) {
                log.error("acpMemoryDelete 失败", e)
                resultMap["result"] = "删除记忆失败: ${e.message}"
            }
            resultMap
        }

        CmdReceiver.register("acpMemoryClean", groupIds, "清理过期记忆，groupId必填") { params ->
            val resultMap = mutableMapOf<String, String>()
            try {
                val param: JSONObject = JSON.parse(params.cmdArgs[0]) as JSONObject
                val groupId = param.getString("groupId")
                if (groupId.isNullOrBlank()) {
                    resultMap["result"] = "groupId不能为空"
                    return@register resultMap
                }
                val mgr = memoryManagers.get(groupId).orElse(null)
                if (mgr == null) {
                    resultMap["result"] = "该 robot 未启用记忆系统"
                    return@register resultMap
                }
                val client = registry.getClient(groupId)
                if (client == null) {
                    resultMap["result"] = "会话不存在"
                    return@register resultMap
                }
                val count = mgr.cleanExpiredMemories(client.workspacePath)
                resultMap["result"] = "已清理 $count 条过期记忆"
            } catch (e: Exception) {
                log.error("acpMemoryClean 失败", e)
                resultMap["result"] = "清理记忆失败: ${e.message}"
            }
            resultMap
        }

        CmdReceiver.register("acpMemoryDream", groupIds, "手动触发记忆整理（Memory Dream），groupId必填") { params ->
            val resultMap = mutableMapOf<String, String>()
            try {
                val param: JSONObject = JSON.parse(params.cmdArgs[0]) as JSONObject
                val groupId = param.getString("groupId")
                if (groupId.isNullOrBlank()) {
                    resultMap["result"] = "groupId不能为空"
                    return@register resultMap
                }
                val mgr = memoryManagers.get(groupId).orElse(null)
                if (mgr == null) {
                    resultMap["result"] = "该 robot 未启用记忆系统"
                    return@register resultMap
                }
                val client = registry.getClient(groupId)
                if (client == null) {
                    resultMap["result"] = "会话不存在"
                    return@register resultMap
                }
                mgr.triggerDream(client.workspacePath)
                resultMap["result"] = "记忆整理已触发，将在后台执行"
            } catch (e: Exception) {
                log.error("acpMemoryDream 失败", e)
                resultMap["result"] = "触发记忆整理失败: ${e.message}"
            }
            resultMap
        }
    }

    private val DISPATCH_MARKER = "dispatch_subagent"
    private val SUB_AGENT_RESULTS_MARKER = "Sub-Agent Results"
    private val SCHEDULE_RESULT_MARKER = "[定时任务操作结果]"
    private val SCHEDULE_LIST_MARKER = "[定时任务列表]"
    private val DISPATCH_PATTERN = java.util.regex.Pattern.compile(
        "\\{\\s*\"action\"\\s*:\\s*\"dispatch_subagent\".*?\"tasks\"\\s*:\\s*\\[.*?]\\s*}",
        java.util.regex.Pattern.DOTALL
    )

    /**
     * 将恢复的会话历史以快照形式异步推送给 molachat，让用户回忆聊天细节。
     * <p>
     * 回放规则：
     * - USER 消息：普通消息以用户标识展示；sub_agent_results 以子 Agent 结果展示
     * - ASSISTANT 消息：包含 dispatch_subagent 时解析并展示派发任务；否则正常 onMessage
     * - TOOL 消息：通过 onToolCall 展示
     */
    private fun replaySessionSnapshot(groupId: String, client: AcpClient) {
        val listener = client.globalListener ?: return
        val sessionId = client.sessionId ?: return
        val history = client.historyManager.getFullHistory(sessionId)
        if (history.isEmpty()) return

        // 整个回放过程开启缓冲，最后一次性发送
        val bufferListener = listener as? com.mola.cmd.proxy.app.acp.acpclient.listener.DefaultAcpResponseListener
        bufferListener?.beginBuffer()

        for (msg in history) {
            when (msg.role) {
                com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage.Role.USER -> {
                    val content = msg.content ?: continue
                    if (content.contains(SUB_AGENT_RESULTS_MARKER)) {
                        // 子 Agent 结果回传，按每个子 Agent 逐条展示
                        val agentBlockPattern = Regex("### (.+?)\\n状态: (.+?)\\n([\\s\\S]*?)(?=### |请综合以上|$)")
                        val matches = agentBlockPattern.findAll(content)
                        var matched = false
                        for (m in matches) {
                            matched = true
                            val agentName = m.groupValues[1].trim()
                            val status = m.groupValues[2].trim()
                            val detail = m.groupValues[3].trim()
                            if (status == "SUCCESS") {
                                listener.onSubAgentEvent("AGENT_COMPLETE", agentName, detail)
                            } else {
                                listener.onSubAgentEvent("AGENT_ERROR", agentName, detail)
                            }
                        }
                        if (!matched) {
                            // 兜底：无法解析时整体展示
                            listener.onSubAgentEvent("AGENT_COMPLETE", "agent派发结果", content)
                        }
                    } else if (content.contains(SCHEDULE_RESULT_MARKER) || content.contains(SCHEDULE_LIST_MARKER)) {
                        // 定时任务操作结果回传
                        val isCreate = content.contains("操作: create")
                        val eventType = if (isCreate) "SCHEDULE_CREATE" else "SCHEDULE_MANAGE"
                        listener.onScheduleEvent(eventType, content, isCreate)
                    } else {
                        listener.onMessage("**🧑 用户：**\n${content}\n\n---\n\n")
                    }
                }
                com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage.Role.ASSISTANT -> {
                    val content = msg.content ?: continue
                    if (content.contains(DISPATCH_MARKER)) {
                        // 解析 dispatch_subagent JSON，展示派发的任务入参
                        val matcher = DISPATCH_PATTERN.matcher(content)
                        var matchedJson: String? = null
                        if (matcher.find()) {
                            matchedJson = matcher.group()
                            try {
                                val json = com.google.gson.JsonParser.parseString(matchedJson).asJsonObject
                                val tasks = json.getAsJsonArray("tasks")
                                val sb = StringBuilder("子 Agent 派发任务：\n")
                                for (t in tasks) {
                                    val task = t.asJsonObject
                                    val agent = task.get("agent")?.asString ?: "unknown"
                                    val title = task.get("title")?.asString ?: ""
                                    val prompt = task.get("prompt")?.asString ?: ""
                                    sb.append("- [$agent/$title] $prompt\n")
                                }
                                listener.onSubAgentEvent("DISPATCH_START", null, sb.toString())
                            } catch (e: Exception) {
                                listener.onSubAgentEvent("DISPATCH_START", null, "子 Agent 派发（解析失败）")
                            }
                        }
                        // dispatch JSON 之外可能还有正常文本，也展示出来
                        val cleanedContent = if (matchedJson != null) content.replace(matchedJson, "").trim() else content.trim()
                        if (cleanedContent.isNotBlank()) {
                            listener.onMessage("${cleanedContent}\n\n---\n\n")
                        }
                    } else if (content.isNotBlank()) {
                        listener.onMessage("${content}\n\n---\n\n")
                    }
                }
                com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage.Role.TOOL -> {
                    val update = com.google.gson.JsonObject()
                    if (msg.rawInput != null) update.add("rawInput", msg.rawInput)
                    if (msg.rawOutput != null) update.add("rawOutput", msg.rawOutput)
                    listener.onToolCall(
                        msg.toolCallId ?: "",
                        msg.toolName ?: "tool",
                        "completed",
                        update
                    )
                }
            }
        }

        // 回放结束，flush 缓冲后再发终止帧
        bufferListener?.flushBuffer()
        listener.onComplete("")
    }

    /**
     * 为 client 初始化记忆系统（如果该 robot 开启了记忆）。
     */
    private fun initMemoryForClient(groupId: String, client: AcpClient, robot: AcpRobotParam?) {
        if (robot == null || !robot.isMemoryEnabled) return

        val memCfg = robot.memory
        val executionRobot = resolveMemoryExecutionRobot(robot)
        val executionWorkDir = if (memCfg.isRobotExecution) {
            executionRobot?.workDir
        } else {
            null
        }
        val mgr = memoryManagers.getOrCreate(
            groupId, memCfg, robot, executionRobot, executionWorkDir)
        client.setMemoryManager(mgr)
        setupTurnCallback(client, memCfg, mgr)
        client.setAfterSessionReady { recoverPendingMemoryExtractions(client, mgr) }
    }

    /**
     * Client 已启动且 MemoryManager 已注入后，异步补偿上次 stop 留下的全量提取。
     * 提交本身不阻塞 client READY；只有模型任务真正成功后才清除 pending。
     */
    private fun recoverPendingMemoryExtractions(client: AcpClient, manager: MemoryManager) {
        PendingMemoryExtractionRecovery.recover(
            client.historyManager,
            client.clientIdentity.historyNamespace,
            client.workspacePath,
            manager,
            pendingMemoryRecoveryClaims,
            if (client.isRestoredSession) client.sessionId else null,
            if (client.isRestoredSession) client.conversationHistory.size else 0
        )
    }

    /**
     * 为 AcpClient 注册每 N 轮触发记忆提取的回调。
     */
    private fun setupTurnCallback(client: AcpClient, memoryConfig: MemoryConfig, memoryManager: MemoryManager) {
        val interval = memoryConfig.extractIntervalTurns
        if (interval <= 0) return

        val turnCount = AtomicInteger(0)
        client.historyManager.setOnTurnFlushed {
            if (turnCount.incrementAndGet() % interval == 0) {
                log.info("每 {} 轮触发记忆提取, groupId={}", interval, client.groupId)
                memoryManager.submitExtract(
                    client.sessionId,
                    client.workspacePath,
                    client.conversationHistory
                )
            }
        }
    }

    /**
     * 初始化能力反思服务。
     * 触发时机：AcpClient 初始化后发现 ability.md 不存在时立即触发。
     * dream 完成后也会触发（通过 MemoryManager 的回调）。
     */
    private fun initAbilityReflection(groupId: String, client: AcpClient, robot: AcpRobotParam?) {
        if (robot == null || robot.name.isBlank()) return
        if (!robot.isAbilityAutoRefresh) return

        val timeoutSeconds = if (robot.isMemoryEnabled) robot.memory.subClientTimeout else 120
        val mcpConfigPaths = client.mcpConfigPaths
        val memoryManager: MemoryManager? = if (robot.isMemoryEnabled) {
            memoryManagers.get(groupId).orElse(null)
        } else null

        val service = abilityServices.getOrPut(groupId) {
            AbilityReflectionService(
                robot.name, client.workspacePath, robot,
                timeoutSeconds, mcpConfigPaths, memoryManager
            )
        }

        // 统一由 submitReflection 内部判断是否需要执行
        service.submitReflection()
    }

    /**
     * 为 onlySubAgent 的 robot 初始化能力反思（不依赖 AcpClient）。
     */
    private fun initAbilityReflectionStandalone(groupId: String, robot: AcpRobotParam) {
        if (robot.name.isBlank()) return
        if (!robot.isAbilityAutoRefresh) return

        val agentProvider = robot.agentProvider ?: "KIRO_CLI"
        val workDir = robot.workDir ?: return
        val mcpConfigPaths = AgentProviderRouter.getInstance().resolve(agentProvider)
            .getMcpConfigPaths(workDir)
        val timeoutSeconds = if (robot.isMemoryEnabled) robot.memory.subClientTimeout else 120

        val service = abilityServices.getOrPut(groupId) {
            AbilityReflectionService(
                robot.name, workDir, robot,
                timeoutSeconds, mcpConfigPaths, null
            )
        }
        service.submitReflection()
    }

    /**
     * 初始化子 Agent 派发器（如果该 robot 配置了 subAgents）。
     */
    private fun initSubAgentDispatcher(groupId: String, client: AcpClient, robot: AcpRobotParam?) {
        if (robot == null || !robot.hasSubAgents()) return

        // 校验子 Agent 引用的有效性
        val allowedNames = mutableSetOf<String>()
        for (ref in robot.subAgents) {
            if (!globalRobotRegistry.containsKey(ref.name)) {
                log.warn("robot '{}' 引用了不存在的子 Agent '{}'，跳过", robot.name, ref.name)
            } else {
                allowedNames.add(ref.name)
            }
        }

        if (allowedNames.isEmpty()) {
            log.warn("robot '{}' 的所有子 Agent 引用均无效，跳过派发器初始化", robot.name)
            return
        }

        val dispatcher = SubAgentDispatcher(
            globalRobotRegistry,
            allowedNames,
            1080
        )

        // 构建子 Agent 的记忆管理器映射（robot name -> MemoryManager）
        val subAgentMemoryMap = mutableMapOf<String, com.mola.cmd.proxy.app.acp.acpclient.MemoryManagerBridge>()
        for (name in allowedNames) {
            val targetRobot = globalRobotRegistry[name] ?: continue
            if (targetRobot.isMemoryEnabled) {
                val executionRobot = resolveMemoryExecutionRobot(targetRobot)
                val executionWorkDir = if (targetRobot.memory.isRobotExecution) {
                    executionRobot?.workDir
                } else {
                    null
                }
                val memMgr = memoryManagers.getOrCreate(
                    "subagent:$name", targetRobot.memory, targetRobot,
                    executionRobot, executionWorkDir)
                subAgentMemoryMap[name] = memMgr
            }
        }
        if (subAgentMemoryMap.isNotEmpty()) {
            dispatcher.setMemoryManagers(subAgentMemoryMap)
            log.info("子 Agent 记忆管理器注入完成, agents={}", subAgentMemoryMap.keys)
        }

        val injector = SubAgentContextInjector()

        client.setSubAgentSupport(dispatcher, injector, globalRobotRegistry)

        log.info("子 Agent 派发器初始化完成, groupId={}, subAgents={}",
            groupId, allowedNames)
    }

    /**
     * 初始化定时任务支持。
     */
    private fun initScheduleSupport(
        context: AcpClientFeatureInitializer.Context,
        client: AcpClient,
        robot: AcpRobotParam?
    ) {
        val injector = ScheduleContextInjector()
        val owner = if (context.scope == AcpClientFeatureInitializer.Scope.TEAM) {
            val team = teamManager?.getRuntime(context.teamId)
                ?.orElseThrow {
                    IllegalStateException("Team runtime 不存在: ${context.teamId}")
                }?.definition
                ?: throw IllegalStateException("TeamManager 尚未初始化")
            ScheduleOwnerKey.team(
                team.ownerChatterId,
                context.teamId,
                context.teamMemberId,
                robot?.name
            )
        } else {
            ScheduleOwnerKey.main(robot?.name ?: context.featureOwnerKey)
        }
        client.setScheduleSupport(scheduleTaskManager, injector, owner)
        log.info("定时任务支持初始化完成, owner={}, scheduleEnabled={}",
            owner, robot?.isScheduleEnabled ?: true)
    }

    /**
     * 初始化 TalkTo 支持（如果该 robot 配置了通讯录或系统中有多个 robot）。
     */
    private fun initTalkToSupport(groupId: String, client: AcpClient, robot: AcpRobotParam?) {
        // dispatcher 同时提供动态外部通讯录；信道在 client 初始化后注册也能在首轮被发现。
        val injector = TalkToContextInjector(talkToDispatcher, groupId)
        client.setTalkToSupport(talkToDispatcher, injector, globalRobotRegistry)
        log.info("TalkTo 支持初始化完成, groupId={}, contacts={}",
            groupId, robot?.contacts?.map { it.name } ?: emptyList<String>())
    }

    /**
     * 统一能力挂点：Team 的 memory/ability 复用来源 owner，schedule 保持正常能力；
     * Team talkTo 使用严格队内 memberId 路由，绝不进入普通 robot registry。
     */
    private fun createFeatureInitializer(): AcpClientFeatureInitializer {
        return AcpClientFeatureInitializer(
            { context, client, robot ->
                initMemoryForClient(context.sourceGroupId, client, robot)
            },
            { context, client, robot ->
                initAbilityReflection(context.sourceGroupId, client, robot)
            },
            { context, client, robot ->
                initSubAgentDispatcher(context.featureOwnerKey, client, robot)
            },
            { context, client, robot ->
                initScheduleSupport(context, client, robot)
            },
            { context, client, robot ->
                if (context.scope == AcpClientFeatureInitializer.Scope.MAIN) {
                    initTalkToSupport(context.featureOwnerKey, client, robot)
                } else {
                    initTeamTalkToExtension(context, client, robot)
                }
            }
        )
    }

    private fun initTeamTalkToExtension(
        context: AcpClientFeatureInitializer.Context,
        client: AcpClient,
        robot: AcpRobotParam?
    ) {
        val manager = teamManager
            ?: throw IllegalStateException("TeamManager 尚未初始化")
        val runtime = manager.getRuntime(context.teamId)
            .orElseThrow { IllegalStateException("Team runtime 不存在: ${context.teamId}") }
        val dispatcher = manager.getOrCreateTalkToDispatcher(context.teamId)
        val channelOwnerKey = "team:${context.teamId}:${context.teamMemberId}"
        val injector = TeamTalkToContextInjector(
            runtime, context.teamMemberId, dispatcher, channelOwnerKey)
        // emptyMap 刻意阻断普通/跨 chatter registry；Team injector 不读取该参数。
        client.setTalkToSupport(dispatcher, injector, emptyMap())
        log.info("Team talkTo 严格队内支持初始化完成, teamId={}, memberId={}, contacts={}, robot={}",
            context.teamId, context.teamMemberId, injector.contacts().size,
            robot?.name ?: "unknown")
    }

    /**
     * 启动定时任务调度器（在所有 client 初始化完成后调用）。
     */
    fun startScheduler(groupRobotMap: Map<String, AcpRobotParam>) {
        // 设置执行回调：检查 client 状态，空闲则新建 session 并执行
        scheduleTaskManager.setScopedExecutionCallback { owner, taskId, groupName, prompt, authPrincipal, channelDelivery ->
            if (owner.isTeam) {
                val manager = teamManager
                if (manager == null) {
                    log.error("Team 定时任务执行失败：TeamManager 不存在, owner={}", owner)
                    false
                } else {
                    manager.executeScheduledPrompt(
                        owner, taskId, groupName, prompt, authPrincipal, channelDelivery)
                }
            } else {
                val robotName = owner.robotName
                    ?: throw IllegalStateException("MAIN schedule owner 缺少 robotName")
                val targetGroupId = groupRobotMap.entries
                    .firstOrNull { it.value?.name == robotName }?.key

                if (targetGroupId == null) {
                    log.error("定时任务执行失败：找不到 robot '{}' 对应的 groupId", robotName)
                    throw RuntimeException("找不到 robot '$robotName' 对应的 groupId")
                }

                val client = registry.getClient(targetGroupId)
                    ?: throw RuntimeException("client 不存在, groupId=$targetGroupId")
                if (client.state != AbstractAcpClient.State.READY) {
                    log.info("定时任务跳过：client 忙碌, robot={}, state={}",
                        robotName, client.state)
                    false
                } else {
                    val boundSessionId = scheduleTaskManager
                        .findGroupSession(owner, groupName)
                    if (boundSessionId == null) {
                        val replacement = registry.replaceSessionIfCurrent(
                            targetGroupId, client, null
                        ) { replacement ->
                            featureInitializer.initialize(
                                AcpClientFeatureInitializer.Context.main(targetGroupId),
                                replacement, replacement.robotParam)
                        }
                            ?: return@setScopedExecutionCallback false
                        if (!groupName.isNullOrBlank()) {
                            scheduleTaskManager.bindGroupSession(
                                owner, groupName, replacement.sessionId)
                        }
                        notifyMainSessionChanged(
                            targetGroupId, client.sessionId,
                            replacement.sessionId, "SCHEDULE")
                        replacement.send(prompt, null,
                            replacement.promptOptionsForScheduleExecution(
                                authPrincipal, channelDelivery))
                    } else if (boundSessionId == client.sessionId) {
                        registry.sendMessage(targetGroupId,
                            prompt, null, client.promptOptionsForScheduleExecution(
                                authPrincipal, channelDelivery))
                    } else {
                        try {
                            val replacement = registry.replaceSessionIfCurrent(
                                targetGroupId, client, boundSessionId
                            ) { replacement ->
                                featureInitializer.initialize(
                                    AcpClientFeatureInitializer.Context.main(targetGroupId),
                                    replacement, replacement.robotParam)
                            }
                                ?: throw IllegalStateException(
                                    "恢复分组会话后 client 不存在")
                            notifyMainSessionChanged(
                                targetGroupId, client.sessionId,
                                replacement.sessionId, "SCHEDULE_RESTORE")
                            replacement.send(prompt, null,
                                replacement.promptOptionsForScheduleExecution(
                                    authPrincipal, channelDelivery))
                        } catch (e: Exception) {
                            log.error(
                                "定时会话分组恢复失败，等待下一轮重试, owner={}, groupName={}, sessionId={}",
                                owner, groupName, boundSessionId, e)
                            return@setScopedExecutionCallback false
                        }
                    }
                    true
                }
            }
        }

        scheduleTaskManager.start()
        log.info("定时任务调度器已启动")
    }

    private fun replaceMainSession(
        groupId: String,
        expected: AcpClient,
        targetRestoreSessionId: String?
    ): AcpClient? = registry.replaceSessionIfCurrent(
        groupId, expected, targetRestoreSessionId
    ) { replacement ->
        featureInitializer.initialize(
            AcpClientFeatureInitializer.Context.main(groupId),
            replacement, replacement.robotParam)
    }

    private fun startAutoNewSession() {
        autoNewSessionExecutor?.shutdownNow()
        autoNewSessionExecutor = null
        autoNewSessionLastChecks.clear()
        autoNewSessionExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "acp-auto-new-session").apply { isDaemon = true }
        }.also { executor ->
            executor.scheduleWithFixedDelay({
                val now = System.currentTimeMillis()
                var mainRotated = 0
                registry.snapshotClients().forEach { (groupId, observed) ->
                    try {
                        val config = observed.robotParam?.autoNewSession
                        if (config == null || !config.isEnabled) {
                            autoNewSessionLastChecks.remove(groupId)
                            return@forEach
                        }
                        val lastCheck = autoNewSessionLastChecks.putIfAbsent(groupId, now)
                        if (lastCheck == null || now - lastCheck < TimeUnit.MINUTES.toMillis(
                                config.checkIntervalMinutes.toLong())) {
                            return@forEach
                        }
                        if (!autoNewSessionLastChecks.replace(groupId, lastCheck, now)) {
                            return@forEach
                        }
                        val idleMillis = TimeUnit.MINUTES.toMillis(config.idleMinutes.toLong())
                        val replacement = registry.replaceIdleSessionIfCurrent(
                            groupId, observed, now, idleMillis
                        ) { newClient ->
                            featureInitializer.initialize(
                                AcpClientFeatureInitializer.Context.main(groupId),
                                newClient, newClient.robotParam)
                        }
                        if (replacement != null) {
                            mainRotated++
                            notifyMainSessionChanged(
                                groupId, observed.sessionId,
                                replacement.sessionId, "AUTO_IDLE")
                        }
                    } catch (e: Exception) {
                        log.warn("MAIN 空闲会话自动轮转失败, groupId={}", groupId, e)
                    }
                }
                val teamRotated = teamManager?.rotateIdleSessions(now) ?: 0
                log.info("自动新建 session 检查完成, mainRotated={}, teamRotated={}",
                    mainRotated, teamRotated)
            }, 1L, 1L, TimeUnit.MINUTES)
        }
        log.info("实例级自动新建 session 检查器已启动")
    }

    private fun notifyMainSessionChanged(
        groupId: String,
        oldSessionId: String?,
        newSessionId: String?,
        reason: String
    ) {
        if (newSessionId.isNullOrBlank()) return
        val result = linkedMapOf<String, String?>(
            "schemaVersion" to "1",
            "instanceId" to CmdProxyHome.instanceId(),
            "groupId" to groupId,
            "oldSessionId" to oldSessionId,
            "newSessionId" to newSessionId,
            "reason" to reason,
            "timestamp" to System.currentTimeMillis().toString()
        )
        try {
            CmdReceiver.callback(
                "acpSessionChanged", "acp",
                CmdResponseContent(UUID.randomUUID().toString(), result)
            )
            log.info(
                "已通知 MolaChat 会话切换, groupId={}, oldSessionId={}, newSessionId={}, reason={}",
                groupId, oldSessionId, newSessionId, reason)
        } catch (e: Exception) {
            // 通知属于状态投影，不回滚已经成功完成的 session 切换。
            log.warn(
                "通知 MolaChat 会话切换失败, groupId={}, newSessionId={}, reason={}",
                groupId, newSessionId, reason, e)
        }
    }

    /**
     * 停止所有 ACP 服务，用于热重载前清理。
     */
    @Synchronized
    fun stop() {
        log.info("正在停止 ACP 服务...")

        autoNewSessionExecutor?.shutdownNow()
        autoNewSessionExecutor = null
        autoNewSessionLastChecks.clear()

        // 先关闭外部入口、心跳和重连，避免 ACP client 关闭后仍收到新事件。
        try {
            channelManager?.close()
        } catch (e: Exception) {
            log.warn("停止外部信道失败", e)
        }
        channelManager = null

        // 先停止周期发布，避免 stop 撤销 ready 后旧代际 heartbeat 继续重放。
        acpSyncRobotsHeartbeat?.close()
        acpSyncRobotsHeartbeat = null

        // 热重载窗口内先撤销 ready 宣告。RPC provider 是进程级常驻对象，不能继续
        // 用上一代 ready snapshot 误导 MolaChat。
        if (::teamTransportDescriptor.isInitialized) {
            teamTransportDescriptor =
                TeamTransportDescriptor.forInstance(
                    CmdProxyHome.instanceId(), teamMemberSourceDescriptors(),
                    remoteTeamMemberSourceDescriptors())
            if (::acpSyncRobotsSnapshot.isInitialized) {
                acpSyncRobotsSnapshot.updateTeamDescriptor(teamTransportDescriptor)
            }
            CmdReceiver.register(
                TeamTransportProtocol.DESCRIBE_COMMAND,
                teamTransportDescriptor.transportGroup,
                "Fast Team transport discovery"
            ) { param ->
                TeamTransportProtocol.describeResult(param.cmdId, teamTransportDescriptor)
            }
        }

        // 停止定时任务调度器
        try {
            scheduleTaskManager.stop()
        } catch (e: Exception) {
            log.warn("停止定时任务调度器失败", e)
        }

        // 关闭所有 AcpClient
        registry.closeAllForShutdown()

        // Team client 使用独立 registry，不能混入普通 registry 的 closeAll。
        teamManager?.closeForShutdown()
        teamManager = null
        teamCommandHandler = null
        TeamSharingStatusRegistry.clear()

        // 清理内部状态
        memoryManagers.shutdownAllNow()
        pendingMemoryRecoveryClaims.clear()
        abilityServices.clear()
        globalRobotRegistry.clear()
        configuredRobotRegistry.clear()
        globalGroupRobotRegistry.clear()
        robotToGroupIdMap.clear()
        activeChatterIds = emptyList()
        configuredRobotNames.clear()

        log.info("ACP 服务已停止")
    }

    @JvmStatic
    fun channelStatuses(): Map<String, ChannelStatus> =
        channelManager?.statuses ?: emptyMap()

    @JvmStatic
    fun channelErrors(): Map<String, String> =
        channelManager?.errors ?: emptyMap()

    @JvmStatic
    fun channelTeamBindingTargets(): List<Map<String, Any>> {
        val manager = teamManager ?: return emptyList()
        return manager.snapshotDefinitions()
            .filter { team ->
                team.state == com.mola.cmd.proxy.app.acp.team.model.TeamState.CREATING
                        || team.state == com.mola.cmd.proxy.app.acp.team.model.TeamState.READY
                        || team.state == com.mola.cmd.proxy.app.acp.team.model.TeamState.RECOVERING
            }
            .sortedBy { it.teamId }
            .map { team ->
                linkedMapOf<String, Any>(
                    "id" to team.teamId,
                    "name" to team.name,
                    "state" to team.state.name,
                    "members" to team.members
                        .filter { member ->
                            member.state != com.mola.cmd.proxy.app.acp.team.model.TeamMemberState.CLOSING
                                    && member.state != com.mola.cmd.proxy.app.acp.team.model.TeamMemberState.CLOSED
                        }
                        .sortedWith(compareBy({ it.order }, { it.teamMemberId }))
                        .map { member ->
                            linkedMapOf<String, Any>(
                                "id" to member.teamMemberId,
                                "name" to member.displayName,
                                "state" to member.state.name
                            )
                        }
                )
            }
    }

    @JvmStatic
    fun teamSharingStatuses(): List<Map<String, Any>> {
        val definitions = teamManager?.snapshotDefinitions() ?: emptyList()
        return globalGroupRobotRegistry.values
            .filter { it.isEnabled && !it.isOnlySubAgent }
            .distinctBy { it.name }.sortedBy { it.name }
            .flatMap { robot ->
                val sourceRobotId = "acp-" + robot.name.replace(" ", "_")
                    .replace("\u3000", "_")
                val sharedGroup = TeamSharedSourceIds.groupId(
                    CmdProxyHome.instanceId(), sourceRobotId)
                val grantedOwners = robot.teamSharedWithChatterIds.map { it.trim() }
                    .filter { it.isNotEmpty() }.toSet()
                val fragmentOwners = definitions.filter { team ->
                    team.isMixedPlacement
                            && team.members.any { it.sourceGroupId == sharedGroup }
                }.map { it.ownerChatterId }.toSet()
                (grantedOwners + fragmentOwners).sorted().map { owner ->
                        val teams = definitions.filter { team ->
                            team.isMixedPlacement && team.ownerChatterId == owner
                                    && team.members.any { it.sourceGroupId == sharedGroup }
                        }
                        linkedMapOf<String, Any>(
                            "granteeOwnerChatterId" to owner,
                            "robotName" to robot.name,
                            "teamCount" to teams.size,
                            "memberCount" to teams.sumOf { team ->
                                team.members.count { it.sourceGroupId == sharedGroup }
                            },
                            "state" to if (!grantedOwners.contains(owner)) "REVOKED_CLEANUP"
                                else if (teams.isEmpty()) "GRANTED" else "RUNNING",
                            "cleanupPending" to (teams.any {
                                it.state == com.mola.cmd.proxy.app.acp.team.model.TeamState.DELETING
                            } || teams.any {
                                teamManager?.isRevokedGrantCleanupPending(it.teamId) == true
                            })
                        )
                    }
            }
    }

    @JvmStatic
    fun setChannelInboundEnabled(channelId: String, enabled: Boolean): Boolean =
        channelManager?.setInboundEnabled(channelId, enabled)
            ?: throw IllegalStateException("channel service is not running")

    @JvmStatic
    fun setChannelPrivateChatEnabled(channelId: String, enabled: Boolean): Boolean =
        channelManager?.setPrivateChatEnabled(channelId, enabled)
            ?: throw IllegalStateException("channel service is not running")

    @JvmStatic
    @Synchronized
    fun reloadChannel(previousChannelId: String, config: ChannelConfig?) {
        channelManager?.reloadChannel(previousChannelId, config)
            ?: throw IllegalStateException("channel service is not running")
    }

    private fun ensureShutdownHook() {
        if (shutdownHookRegistered.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(
                Thread({
                    try {
                        stop()
                    } catch (e: Exception) {
                        log.warn("JVM shutdown 时关闭 ACP/Team 资源失败", e)
                    }
                }, "acp-team-shutdown")
            )
        }
    }

    /**
     * 按 robot 维度热重载：只重建指定 robot 的 ACP 进程，不影响其他 robot。
     */
    @Synchronized
    fun reloadRobot(robotName: String, robot: AcpRobotParam,
                    chatterIds: List<String>,
                    configuredRobots: Collection<AcpRobotParam>) {
        log.info("开始 robot 级热重载: robot={}, enabled={}, onlySubAgent={}, onlyTeamMember={}",
            robotName, robot.isEnabled, robot.isOnlySubAgent, robot.isOnlyTeamMember)

        require(!(robot.isOnlySubAgent && robot.isOnlyTeamMember)) {
            "onlySubAgent and onlyTeamMember cannot both be true"
        }

        if (chatterIds != activeChatterIds) {
            throw IllegalStateException(
                "chatterIds changed; use full ACP service refresh instead of robot refresh")
        }
        if (!configuredRobotNames.contains(robotName)) {
            throw IllegalStateException(
                "robot identity changed or is not active; use full ACP service refresh")
        }

        // 单 Robot 刷新也同步完整配置快照，使记忆所有者能立即读取被引用
        // Robot 的最新配置；这里只更新配置对象，不额外重建其它 Robot。
        val latestConfiguredRobots = configuredRobots
            .filter { it.name.isNotBlank() }
            .associateBy { it.name }
        configuredRobotRegistry.entries.removeIf {
            !latestConfiguredRobots.containsKey(it.key)
        }
        configuredRobotRegistry.putAll(latestConfiguredRobots)
        configuredRobotNames.clear()
        configuredRobotNames.addAll(latestConfiguredRobots.keys)

        // 1. 找到该 robot 的所有旧 groupId，逐一关闭
        val oldGroupIds = registry.getGroupIdsByRobot(robotName)
        val remainsTeamSource = robot.isEnabled && !robot.isOnlySubAgent
        for (groupId in oldGroupIds) {
            registry.closeByGroupId(groupId)
            // 新 client 必须按最新记忆执行方式重建 manager；旧 Team 引用由
            // MemoryManagerRegistry 延迟到全局 stop 再关闭。
            memoryManagers.remove(groupId)
            // MAIN → Team-only 时 Team client 仍按 sourceGroupId 共用这些服务。
            if (!remainsTeamSource) {
                abilityServices.remove(groupId)
            }
            log.info("robot '{}' 旧 client 已关闭, groupId={}", robotName, groupId)
        }
        globalGroupRobotRegistry.entries.removeIf { it.value.name == robotName }
        robotToGroupIdMap.remove(robotName)
        memoryManagers.remove("subagent:$robotName")

        // 2. 更新全局 robot 注册表
        configuredRobotRegistry[robotName] = robot
        if (robot.isEnabled && !robot.isOnlyTeamMember) {
            globalRobotRegistry[robotName] = robot
        } else {
            globalRobotRegistry.remove(robotName)
        }

        val acpId = "acp-" + robot.name.replace(" ", "_")
            .replace("\u3000", "_")
        val sourceGroupIds = chatterIds.map { chatterId ->
            listOf(chatterId, acpId).sorted().joinToString("")
        }
        if (robot.isEnabled && !robot.isOnlySubAgent) {
            sourceGroupIds.forEach { groupId -> globalGroupRobotRegistry[groupId] = robot }
            registerSharedTeamSources(listOf(robot))
        } else {
            teamManager?.disableSourceRobot(acpId)
        }
        teamManager?.reconcileRevokedGrants()

        // 3. 只有普通 MAIN robot 才重建 client；Team-only 仅保留来源配置。
        if (robot.isEnabled && !robot.isOnlySubAgent && !robot.isOnlyTeamMember) {
            val newGroupIds = sourceGroupIds

            for (groupId in newGroupIds) {
                try {
                    registry.createSession(groupId, robot.workDir, robot) { created ->
                        featureInitializer.initialize(
                            AcpClientFeatureInitializer.Context.main(groupId), created, robot)
                    }
                    if (registry.getClient(groupId) == null) continue

                    // 更新 robotToGroupIdMap（取第一个新 groupId）
                    robotToGroupIdMap.putIfAbsent(robotName, groupId)

                    log.info("robot '{}' 新 client 已创建, groupId={}", robotName, groupId)
                } catch (e: Exception) {
                    log.error("robot '{}' client 重建失败, groupId={}", robotName, groupId, e)
                }
            }

            // 为新 groupId 注册命令处理器，确保 MolaChat 能发送命令到此 robot
            registerGroupCommands(newGroupIds)
        } else {
            // 被禁用、Team-only 或 onlySubAgent：不启动 MAIN client。
            if (robot.isEnabled && robot.isOnlySubAgent) {
                val groupId = chatterIds.firstOrNull()?.let { chatterId ->
                    listOf(chatterId, acpId).sorted().joinToString("")
                }
                if (groupId != null && robot.workDir.isNotBlank()) {
                    initAbilityReflectionStandalone(groupId, robot)
                }
            }
        }

        // 4. 触发 acpSyncRobots 通知 MolaChat 更新 robot 信息
        try {
            val allRobots = globalRobotRegistry.values.filter {
                it.isEnabled && !it.isOnlySubAgent && !it.isOnlyTeamMember
            }
            val robotsJson = JSON.toJSONString(allRobots)
            val chatterIdsJson = JSON.toJSONString(chatterIds)
            acpSyncRobotsSnapshot.updateOrdinary(robotsJson, chatterIdsJson)
            teamTransportDescriptor = TeamTransportDescriptor.readyForBusiness(
                CmdProxyHome.instanceId(), teamMemberSourceDescriptors(),
                remoteTeamMemberSourceDescriptors())
            acpSyncRobotsSnapshot.updateTeamDescriptor(teamTransportDescriptor)
            publishAcpSyncRobots()
            log.info("acpSyncRobots 回调已发送 (robot级重载后)")
        } catch (e: Exception) {
            log.error("acpSyncRobots 回调发送失败 (robot级重载)", e)
        }

        log.info("robot 级热重载完成: robot={}", robotName)
    }

    /**
     * 解析记忆模型真正使用的 Robot。完整配置注册表故意包含禁用 Robot；
     * 引用不存在时禁止静默回退，避免记忆跑到错误的供应商。
     */
    private fun resolveMemoryExecutionRobot(ownerRobot: AcpRobotParam): AcpRobotParam? {
        val memory = ownerRobot.memory
        if (!memory.isRobotExecution) return ownerRobot
        val targetName = memory.robotName?.trim().orEmpty()
        val target = configuredRobotRegistry[targetName]
        if (target == null) {
            log.error(
                "robot '{}' 的记忆执行 Robot 不存在: '{}'，本实例记忆功能不初始化",
                ownerRobot.name, targetName)
        }
        return target
    }

    private fun teamMemberSourceDescriptors(): List<TeamMemberSourceDescriptor> {
        val result = mutableListOf<TeamMemberSourceDescriptor>()
        val robotsByName = globalGroupRobotRegistry.values
            .filter { it.isEnabled && !it.isOnlySubAgent }
            .associateBy { it.name }
        for (chatterId in activeChatterIds.sorted()) {
            for (robot in robotsByName.values.sortedBy { it.name }) {
                val sourceRobotId = "acp-" + robot.name.replace(" ", "_")
                    .replace("\u3000", "_")
                val sourceGroupId = listOf(chatterId, sourceRobotId)
                    .sorted().joinToString("")
                if (globalGroupRobotRegistry[sourceGroupId] !== robot
                    && globalGroupRobotRegistry[sourceGroupId]?.name != robot.name) {
                    continue
                }
                result.add(TeamMemberSourceDescriptor(
                    chatterId, sourceGroupId, sourceRobotId, robot.name,
                    robot.name, robot.avatar,
                    ContactRemarkResolver.resolve(null, robot), robot.isOnlyTeamMember
                ))
            }
        }
        return result
    }

    private fun registerSharedTeamSources(robots: Collection<AcpRobotParam>) {
        val instanceId = CmdProxyHome.instanceId()
        robots.filter { it.isEnabled && !it.isOnlySubAgent }
            .distinctBy { it.name }
            .forEach { robot ->
                val sourceRobotId = "acp-" + robot.name.replace(" ", "_")
                    .replace("\u3000", "_")
                if (robot.teamSharedWithChatterIds.isNotEmpty()) {
                    globalGroupRobotRegistry[
                        TeamSharedSourceIds.groupId(instanceId, sourceRobotId)
                    ] = robot
                }
            }
    }

    private fun remoteTeamMemberSourceDescriptors(): List<RemoteTeamMemberSourceDescriptor> {
        val instanceId = CmdProxyHome.instanceId()
        val result = mutableListOf<RemoteTeamMemberSourceDescriptor>()
        globalGroupRobotRegistry.values
            .filter { it.isEnabled && !it.isOnlySubAgent }
            .distinctBy { it.name }
            .sortedBy { it.name }
            .forEach { robot ->
                val sourceRobotId = "acp-" + robot.name.replace(" ", "_")
                    .replace("\u3000", "_")
                val sourceGroupId = TeamSharedSourceIds.groupId(instanceId, sourceRobotId)
                robot.teamSharedWithChatterIds.map { it.trim() }
                    .filter { it.isNotEmpty() }.distinct().sorted()
                    .forEach { owner -> result.add(RemoteTeamMemberSourceDescriptor(
                        owner, instanceId, sourceGroupId, sourceRobotId,
                        robot.name, robot.name, robot.avatar,
                        ContactRemarkResolver.resolve(null, robot)
                    )) }
            }
        return result
    }

    /**
     * 从 crossTalkToDeliver 命令参数中找到目标 client。
     * MolaChat 通过 CmdSender.send(cmdName, targetGroupId, args) 路由到正确的 group，
     * 但 CmdReceiver 的 handler 是按 cmdGroupList 注册的，需要从 groupRobotMap 中匹配。
     */
    private fun findClientForDelivery(
        params: com.mola.cmd.proxy.client.param.CmdInvokeParam,
        groupRobotMap: Map<String, AcpRobotParam>
    ): AcpClient? {
        // CmdReceiver 的 invoke 会通过 routeTag 路由到正确的 group
        // 但 handler 内部无法直接获取 group 信息，需要通过 cmdArgs 中的信息反查
        // 实际上 MolaChat send 时指定了 targetGroupId，RPC 框架会路由到对应 group 的 provider
        // 这里遍历所有 client 找到匹配的（单 cmd-proxy 实例内 robot 数量有限，性能无问题）
        for ((groupId, robot) in groupRobotMap) {
            if (robot == null) continue
            val client = registry.getClient(groupId) ?: continue
            // 返回第一个匹配的 client（单 chatter 场景下每个 robot 只有一个 client）
            return client
        }
        return null
    }

}
