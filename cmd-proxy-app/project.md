# cmd-proxy-app 项目文档

## 1. 项目概况
- **坐标**：com.mola:cmd-proxy-app:1.0.0  
- **JDK**：Java 8  
- **主类**：com.mola.cmd.proxy.app.MainKt（Kotlin 入口）  
- **打包**：maven-assembly-plugin 生成可执行 fat-jar（cmd-proxy-app-1.0.0-jar-with-dependencies.jar）

## 2. 项目结构
```
cmd-proxy-app
├── src/main/java              // Java 工具包
│   └── com.mola.cmd.proxy.app.utils
│       ├── Base64Util.java
│       ├── ExecSqlUtil.java
│       └── McpFileUtils.java
│   └── HttpCommonService.java // 通用 HTTP 服务
├── src/main/kotlin            // Kotlin 业务核心
│   └── com.mola.cmd.proxy.app
│       ├── Main.kt            // 启动入口
│       ├── constants          // 常量定义
│       ├── chatgpt            // ChatGPT 代理模块
│       ├── imagegenerate      // 图像生成代理模块
│       ├── mcp                // MCP（流水线之王）代理模块
│       ├── interceptor        // 拦截器
│       └── utils              // Kotlin 工具
├── src/main/resources         // 资源文件（空）
├── src/test/java              // 单元测试（空）
├── target                     // Maven 构建输出
└── pom.xml                    // Maven 配置
```

## 3. 包职责说明
| 包路径 | 内容描述 |
|--------|----------|
| `chatgpt` | ChatGPT 代理服务启动与交互逻辑 |
| `imagegenerate` | 图像生成代理服务启动与交互逻辑 |
| `mcp` | 流水线之王 MCP 代理核心，含脚本执行、指令注册、扩展引擎 |
| `interceptor` | 代理可用性拦截器 |
| `constants` | 全局常量（如启动参数枚举） |
| `utils` | 跨模块通用工具（日志、Base64、SQL 执行、文件操作） |

## 4. 主要依赖
- `cmd-proxy-client`：内部客户端 SDK（版本继承父 POM）
- `kotlin-script-runtime`：Kotlin 脚本引擎
- `kotlinx-coroutines-core`：协程支持
- `groovy-all`：Groovy 脚本支持
- `ivy`：Apache Ivy 动态依赖管理
- `mysql-connector-java`：MySQL JDBC 驱动

## 5. 启动方式
```bash
# 默认端口 10020
java -jar cmd-proxy-app-1.0.0-jar-with-dependencies.jar [参数]

参数列表：
  chatgpt        启用 ChatGPT 代理
  image-generate 启用图像生成代理
  mcp            启用 MCP 代理（首次启动会交互式输入 group 编码）
```

## 6. 编码规范
- 统一 UTF-8 编码
- Java 8 语法兼容，Kotlin 1.9+ 特性可用
- 日志使用 `LogUtil`（已封装 debugReject，默认关闭 debug）
- 脚本文件统一放在 `src/main/kotlin/com/mola/cmd/proxy/app/example`，扩展名 `.kts`

## 7. 忽略文件（.gitignore 摘要）
- `target/`、`*/*.iml`、`.idea/`、`.vscode/`、`.DS_Store`
- Eclipse/NetBeans/VS Code/MacOS 自动生成文件全部排除
- 保留 `maven-wrapper.jar` 及 `src/**/target` 例外

> 本文档由项目结构自动生成，后续可随代码迭代持续更新。