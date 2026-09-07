# 开发1进度：数据与业务

关联：[工作分配与更新规则](starweave-task-work-allocation.md) · [总进度](starweave-task-progress.md)

## 当前状态
- 状态：契约v1已交付待审修；v2修订进行中
- 更新时间：2026-09-05 11:03 CST（Asia/Shanghai）
- 已完成：服务/REST/outbox契约、7个生产类、2个测试类、SQLite JDBC依赖；13项隔离Java8/JUnit验证通过
- 当前工作：落实队长六项v2审查，修正结构化筛选、内部事件与接收租约、恢复通知
- 下一步：配合契约审查、模块联调和最终干净Reactor/package验证
- 阻塞/依赖：服务/REST/outbox/卡片契约待协同确认
- 验证：Java8隔离编译与JUnit共13项通过；git diff --check通过。全量与打包尚未完成。

## 更新记录
由本工作包负责人追加，格式：
- 时间：
- 完成内容及文件：
- 验证命令/结果：
- 下一步：
- 阻塞或所需协作：

## 交接清单
- 修改文件：
- 对外接口及公共入口接线说明：
- 验证证据：
- 未覆盖项与风险：

- 时间：2026-09-05 10:55 CST
- 完成内容及文件：契约v1已发布并通知T0/T2/T3；SQLite migration、Repository、TaskService、附件存储、REST handler/API bridge初版已落盘，app pom锁定sqlite-jdbc 3.46.1.3。
- 验证命令/结果：尚未编译，不宣称通过。
- 下一步：增加状态/幂等/并发/重启与附件测试，运行聚焦验证；全量干净Reactor由队长集成阶段执行。
- 阻塞或所需协作：等待T2目标字段及outbox接口反馈、T3界面契约反馈。公共入口未改动。

- 时间：2026-09-05 11:02 CST
- 完成内容及文件：初版T1共7个生产类及2个JUnit测试类；补充事务内候选选择assign(taskId,candidates,mode)，保证跨任务AFFINITY占用查询与绑定同事务；REST null游标、附件刷盘、20MiB限制已覆盖。
- 验证命令/结果：`python3 /tmp/verify-task-t1.py`（隔离javac -source 8 -target 8 + JUnitCore）13 tests通过；`git diff --check`通过；真实Java 1.8.0_181加载sqlite-jdbc 3.46.1.3完成SQLite测试，JDBC class major=52。HTTP用例初次被sandbox禁止bind，获自动审批后在本机随机端口通过。首次Maven compile受并发target类文件消失干扰，未通过，不作为模块结果。
- 下一步：通知队长和T2/T3待集成接口，配合联调反馈；最新小修仍会重复隔离验证。
- 阻塞或所需协作：全量clean Reactor/package、Windows native/assembly、实际Provider与双环境由集成阶段验证。任务HTTP监听器需有界executor及请求读取超时，附件cleanup需低频调度；已补契约。

## T1初版交接（2026-09-05 11:03 CST）
- 修改文件：cmd-proxy-app/pom.xml；acp/task/model/TaskException.java；store/TaskMigrationRunner.java、TaskRepository.java；service/TaskService.java；attachment/TaskAttachmentStore.java；api/TaskApiBridge.java、TaskRestHandler.java；测试service/TaskServiceTest.java、api/TaskRestHandlerTest.java；本进度文件与starweave-task-contracts.md。
- 接线：创建 TaskService(CmdProxyHome.resolve("starweave/tasks").toPath(),instanceId)，TaskApiBridge.install(service)，HTTP注册TaskRestHandler.PREFIX -> new TaskRestHandler(service)。关闭先停HTTP/MCP/dispatch，clear(service)，service.close()。具体CmdProxyHome返回类型由队长按现代码调用。
- T2存储契约：claimOutbox使用每次独立leaseToken；ack/retry/discard核验token及未过期租约；assign候选重载在同事务中选择并保存；acceptReceipt/pendingReceipts/completeReceipt提供重启恢复。
- 验证：最终一轮13 tests全部通过（11个服务测试、2个真实HTTP测试），0失败；Java 1.8.0_181，SQLite JDBC字节码major52；git diff --check通过。
- 限制：未修改公共入口、未运行最终clean Reactor/package、未做Windows/真实Provider/双环境验收；未提交/推送/重启运行实例；其他成员文件保持原状。配置请求读取超时与有界HTTP executor、低频附件cleanup需启动层接线。
