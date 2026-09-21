# ms-localdebug（idea-debug-plugin）

通用微服务本地调试 IDEA 插件。配合 [kt-connect](https://github.com/alibaba/kt-connect) 在本地开发/调试 Kubernetes 集群中的微服务：本地应用接入集群网络、流量按 tag 路由到本地、配置自动改写与回滚，全程在 IDE 内完成。

## 项目简介

- **JVM 参数 / 环境变量自动注入**：IDEA 启动 Java 应用时，按主类匹配项目根目录 `.localdebug.jvmrc.json`，自动追加 JVM 参数与环境变量
- **ktctl 代理生命周期管理**：在工具窗口内启动/终止 `ktctl connect / mesh / preview` 等命令，停止走信号文件机制
- **项目配置 init / replace / rollback**：
  - **初始化（init）**：备份 `application.yaml` / `bootstrap.yaml` / `pom.xml`，复制 `.localdebug.json` 配置模板到项目根目录，生成 `.localdebug.ktctl.cmds`、`.localdebug.k8sServices.json`、`ports.json`
  - **替换（replace）**：读取 `.localdebug.json`，改写本地配置文件（Nacos 地址、Dubbo tag、中间件 patch 等），生成 `.localdebug.jvmrc.json`
  - **回滚（rollback）**：从备份恢复项目配置到初始化前状态

## 功能清单

工具窗口 `LocalDebug`（右侧边栏，secondary）分三个页签：

### 项目配置（MainToolWindowPanel）
- 添加 / 移除 debug 项目（项目路径列表存于全局配置）
- 执行「初始化配置 / 替换配置 / 回滚配置」三个动作（对应 `ProjectInitAction` / `ProjectReplaceAction` / `ProjectRollbackAction`）

### 调试命令（DebugCommandsPanel）
- 选择 debug 项目，读取其 `.localdebug.ktctl.cmds` 中的命令清单
- 逐条「启动 / 终止」，状态实时显示，日志分区输出（走 `DebugCommandService`）
- 支持命令类型：connect / mesh / exchange / preview

### 工具安装（ToolsPanel）
- 一键安装 kubectl / ktctl（下载地址取自全局配置），安装到 `~/.localdebug/bin/` 并验证、自动加入 PATH

### 设置页（LocalDebugSettingsConfigurable，Settings → LocalDebug 微服务调试）
- 阿里云 AccessKey / SecretKey（存 IntelliJ PasswordSafe）、Nacos / RocketMQ / Scheduler 等配置
- K8s kubeconfig 编辑、保存到 `~/.kube/config`、测试连接

## 工作原理

1. **`.localdebug.*` 文件契约**：项目根目录下的 `.localdebug.json`（用户填写）、`.localdebug.ktctl.cmds`（ktctl 命令清单）、`.localdebug.jvmrc.json`（JVM 参数注入源）、`.localdebug.k8sServices.json`（K8s service 候选名）构成插件与项目的契约。全局状态在 `~/.localdebug/`（`config/` 全局配置，`bin/` 工具二进制，`signal/` 信号文件）。
2. **JavaProgramPatcher 注入**：`RunApplicationAgent` 继承 IDEA 的 `JavaProgramPatcher`，在每次运行/调试 Java 应用前读取项目根目录 `.localdebug.jvmrc.json`，按主类匹配后将 `jvmArgs` 追加到 VM 参数、`jvmEnvs` 合并到环境变量。
3. **信号文件停止机制**：终止 ktctl 命令时，优先写信号文件 `~/.localdebug/signal/ktctl-<type>-signal-<pid>`（内容为 `stop`，ktctl 约定），写失败才退化为 `process.destroy()`；超时再 `destroyForcibly()`。

## 配置文件格式

### `.localdebug.json`（项目根目录，初始化时生成模板，用户填写）

```jsonc
{
  "jvmArgs": "",                // 可选，全局 JVM 参数
  "debugTag": "",               // 可选，链路 tag；非空时优先于 OS 用户名作为 debug tag
  "updateDependencies": [],     // 需要修改版本的依赖
  "addDependencies": [],        // 需要新增的依赖
  "replaceConfigs": [           // 每个服务一条
    {
      "appName": "",            // 应用名（通常自动识别）
      "nacosGroup": "",
      "nacosDataId": "",        // Nacos 配置 dataId
      "nacosNamespaceId": "",
      "k8sServiceName": "",     // 目标服务 K8s Service 名
      "k8sServiceNamespace": "",
      "serviceType": "bff",     // bff / rpc
      "tag": ""                 // 链路 tag（留空则取 debugTag 或 OS 用户名）
    }
  ],
  "aliYunConfig": { /* Nacos/RocketMQ/Scheduler 等中间件地址与凭据 */ }
}
```

### `.localdebug.jvmrc.json`（replace 动作生成，勿手工维护）

```json
[
  {
    "mainClass": "com.example.Application",
    "jvmArgs": "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005",
    "jvmEnvs": { "SPRING_PROFILES_ACTIVE": "dev" }
  }
]
```

### `.localdebug.ktctl.cmds`（init 动作生成，格式：`##命令说明###应用名` 后跟命令行）

```bash
##ktconnect代理###my-app
ktctl -d -n <namespace> connect

##接口流量拦截代理###my-app        # bff 类型
ktctl -d -n <namespace> mesh <service> --expose <port>:8080 --versionMark tag:<tag>

##rpc流量拦截代理###my-app         # rpc 类型
ktctl -d -n <namespace> preview <service>-<tag> --expose 20880:20880

##清理流量拦截###my-app
ktctl -d -n <namespace> recover <service>

##清理集群里已超期KT代理容器###my-app
ktctl -d -n <namespace> clean
```

## 构建与安装

```bash
# 开发调试：启动一个带插件的 IDEA 沙箱实例
./gradlew runIde

# 打包插件，产物在 build/distributions/ms-localdebug-<version>.zip
./gradlew buildPlugin

# 本地安装：Settings → Plugins → ⚙ → Install Plugin from Disk...
# 选择 build/distributions/ 下的 zip 后重启
```

环境要求：JDK 17，IDEA 2023.1+（`sinceBuild=231`，`untilBuild=252.*`）。

## 使用流程

1. **安装 kubectl / ktctl**：工具窗口 → 工具安装 → 两个安装按钮（下载地址在 `~/.localdebug/config/config.json` 配置）
2. **添加项目**：工具窗口 → 项目配置 → 添加要调试的项目
3. **初始化配置**：选中项目 → 初始化配置（备份原配置 + 生成 `.localdebug.json` 模板等）
4. **填写 `.localdebug.json`**：按项目实际情况填写 Nacos、K8s 服务名、serviceType 等
5. **替换配置**：选中项目 → 替换配置（改写本地配置、生成 `.localdebug.jvmrc.json`）
6. **启动 ktconnect 代理**：工具窗口 → 调试命令 → 启动 `ktctl connect`
7. **启动本地应用**：正常 Run/Debug，插件自动注入 JVM 参数与环境变量
8. **启动流量拦截**：调试命令页启动 mesh（接口流）或 preview（RPC 流）
9. **带 tag 请求**：调用方请求头带 `tag`（BFF）或 `dubbo-tag`（RPC），流量路由到本地
10. **回滚配置**：调试完成后回滚配置，恢复 `application.yaml` / `bootstrap.yaml` / `pom.xml`

## 项目结构

```
src/main/java/com/localdebug/plugin/
├── RunApplicationAgent.java      # JavaProgramPatcher：JVM 参数/环境变量注入
├── core/
│   ├── action/                   # init / replace / rollback 三个动作
│   │   ├── Action.java
│   │   ├── ProjectInitAction.java
│   │   ├── ProjectReplaceAction.java
│   │   └── ProjectRollbackAction.java
│   ├── cloud/                    # 阿里云 STS 临时凭证 + PasswordSafe 存储
│   │   ├── ALiYunClient.java
│   │   └── AliyunCredentialStore.java
│   ├── config/                   # .localdebug.json / jvmrc / replace / spring-boot 配置模型
│   │   ├── ConfigManager.java
│   │   ├── JvmParametersConfig.java
│   │   ├── LocalDebugConfig.java
│   │   ├── ReplaceConfig.java
│   │   └── SpringBootConfig.java
│   ├── constant/Constants.java   # 文件名与路径常量
│   ├── maven/MavenUtil.java      # pom.xml 改写
│   ├── model/                    # Dependency / FilePath / JarCommand / K8sServiceItems
│   ├── process/DebugCommandService.java  # ktctl 进程管理（信号文件停止）
│   └── util/                     # ToolInstaller / PortUtil / MiddlewarePatchUtil / JsonFileUtil 等
└── ui/
    ├── MainToolWindowFactory.java    # 工具窗口入口（三页签）
    ├── MainToolWindowPanel.java      # 项目配置页签
    ├── DebugCommandsPanel.java       # 调试命令页签
    ├── ToolsPanel.java               # 工具安装页签
    └── settings/LocalDebugSettingsConfigurable.java  # 阿里云 + K8s 设置页
```

## 技术栈

- IntelliJ IDEA Platform（Gradle IntelliJ Plugin，Java + Kotlin 插件依赖）
- Java 17，Gradle Kotlin DSL
- Hutool、Jackson / SnakeYAML、Lombok
- Nacos Client（配置拉取）、阿里云 STS SDK（临时凭证）
- zip4j（工具安装解压）、Apache Maven Model（pom.xml 改写）
