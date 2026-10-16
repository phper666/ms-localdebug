# 通用化技术方案（TECH-DESIGN）

> 状态：方案（待评审，未实施）。
> 上游：DESIGN-generic.md（分级路线）/ DESIGN-mechanism.md（机制篇+审查问题清单）/ RESEARCH-stack-survey.md（生态依据）。
> 编译基线：IntelliJ 2023.1.5 IU / Java 17 / Gradle Kotlin DSL（运行时兼容到 262.*）。

## 0. 判级与工程基线

- **判级**：复杂（多阶段数据化改造，涉及 core 包 ~15 文件）；非安全敏感（凭证链已由 P2 迁入 PasswordSafe，本次不新增攻击面；若后续动凭证机制再复判）。
- **工程基线三问**：① git：**未初始化**（实施前先 `git init` 打基线 tag，回滚有底）；② 脚手架：已有（Gradle IntelliJ 插件模板完整）；③ 测试框架：JUnit 平台随模板可用但**零测试**——P0 起为改动核心补最小单测（`src/test/java`，纯 JUnit，不引 mock 框架）。
- **总原则**：行为等价第一。每阶段验收以「旧团队默认栈全流程行为不变」为回归底线，再验证新配置通道生效。

## 1. 现状 → 目标架构

```
现状：                                            目标：
ProjectInit/Replace/Rollback                     ProjectInit/Replace/Rollback
  ├─ 硬编码 nacos/dubbo 键（H2/M3）      →         ├─ 流程引擎不变
  ├─ 硬编码 ktctl 命令模板（M8）         →         └─ 所有输入来自 StackDescriptor（言）
  ├─ 内置 namespace map/特判（H3）                分层配置 + 2 个 Provider 接口
  ├─ isValidConfig 全字段闸（H1）                 分级校验引擎（占位符推导）
  └─ 直接 new nacos client/STS（耦合）            注册表选择 provider 实现
```

核心概念——**StackDescriptor（栈描述符）**：一个 JSON 完整描述「某技术栈的本地调试方言」，插件引擎只读描述符执行，不再含任何栈方言代码。

## 2. 数据模型（本方案核心交付物）

### 2.1 分层配置（低优先级覆写高优先级）

```
① builtin（jar 内，保旧团队默认栈行为）
   < ② global（~/.localdebug/config/，用户/团队自持）
   < ③ project（.localdebug.json，项目自持）
   < ④ runtime（debugTag、端口分配、用户输入）
```

### 2.2 StackDescriptor（内置 1 份默认 + 用户可加）

```jsonc
{
  "stack": "sca-dubbo-ktctl",                      // 唯一 ID
  "displayName": "Spring Cloud Alibaba + Dubbo + kt-connect",
  "configCenter": { "type": "nacos", "fallbackDataIdSuffix": ".yaml" },
  "registryPatch": {                                // 「注册地址伪装」键（H2 收编）
    "discoveryIpKey": "spring.cloud.nacos.discovery.ip",
    "discoveryPortKey": "spring.cloud.nacos.discovery.port",
    "discoveryIpTemplate": "{svc}.{ns}.svc.cluster.local",
    "discoveryPortValue": 80,
    "appNameTemplate": "{app}-{tag}",               // 影子命名
    "bootstrapPatch": {                             // bootstrap 写入（含障眼法）
      "spring.cloud.nacos.config.server-addr": "{configCenterAddr}",
      "spring.application.name": "application-localdebug"
    },
    "extraApplicationPatch": {                      // H2 dubbo 键组 + 清空动作（P1 批次2）
      "dubbo.provider.tag": "{tag}",
      "dubbo.consumer.lazy": true,
      "dubbo.registry.register-mode": "all",
      "dubbo.protocol.port": "{rpcPort}",
      "management": null,                           // null = 写入 null 移除键（与 middleware-patches 语义一致）
      "server.port": ""
    }
  },
  "proxy": {                                        // 隧道工具命令模板（M8 收编）
    "tool": "ktctl",
    "templates": {
      "connect": "ktctl -d -n {ns} connect",
      "mesh":    "ktctl -d -n {ns} mesh {svc} --expose {port}:8080 --versionMark tag:{tag}",
      "preview": "ktctl -d -n {ns} preview {alias} --expose {rpcPort}:{rpcPort}",
      "recover": "ktctl -d -n {ns} recover {svc} ",
      "clean":   "ktctl -d -n {ns} clean "
    },
    "stopMechanism": "signal-file",
    "rollbackActions": ["recover", "clean"]
  },
  "credentials": [],                                 // 默认空；按 patch 占位符按需启用（静态 AK 场景连 Provider 都不用）
  "validation": { "required": ["configCenterAddr"],
                  "optional": ["mqAddr","roleArn","schedulerNamespace","schedulerGroupId","schedulerAppKey"],
                  "precheck": ["routingEnabler.requiredOrgComponents"],
                  "forced": ["mqIndependentGroup"] },  // forced=强制不变式（恒校验）
  "routingEnabler": {                                // ★ 组织路由启用器契约（L4 前提显性化）+ 染色定义唯一出处
    "mode": "provider-tag",                         // provider-tag | consumer-hint | infra-subset | none
                                                    //   none=全量影子（gRPC 无 tag 原语），启动显式告警（能力上限声明）
    "httpLaneHeader": "tag",                         // 入口染色 header（组织网关改写组件按它分流）
    "rpcAttachmentKey": "dubbo.tag",                 // 消费侧链路契约（组织透传库/原生框架负责）
    "rpcProviderTagKey": "dubbo.provider.tag",       // 插件可写的 provider 标签键
    "shadowNameTemplate": "{svc}-{tag}",             // 影子实例命名契约（网关/注册/隧道三方对齐）
    "requiredOrgComponents": [                       // init 时落 .localdebug.audit.json 对照清单
      "网关按 header 'tag' 改写 lb 目标到影子服务",
      "消费侧已透传 rpcAttachmentKey（如 platform-common）"
    ]
  },
  "proxyModel": "mesh-service",                      // ★ mesh-service | intercept | portforward | direct
                                                     //   机制分支开关：决定注册伪装策略+染色命名契约+rollback 动作（工具切换=机制切换，L2-L3）
  "inherits": "builtin:sca-dubbo-ktctl",             // ★ 描述符继承：内置模板+仅覆盖差异字段（防五维组合爆炸）；删除继承键用 {"$delete": true} 哨兵，null 保留写 null 语义
  "bootIsolation": {                                 // ★ P1 仅记录意图（唯一实现 nacos）；P3 收敛进 ConfigCenterProvider——防描述符退化为贫血 DSL
    "nacos": { "renameApp": "application-localdebug", "disableImportCheck": true }
  },
  "runtimeEnv": "k8s",                               // ★ k8s | mesh | non-k8s（non-k8s=整模型失效 L4，拒绝或文档化）
                                                    //   与 proxyModel 交叉校验（non-k8s × mesh-service = 非法组合）
  "schemaVersion": 1,                                // ★ 描述符 schema 版本（元校验依据）
  "middlewareGroup": { "key": "churchill", "mqConsumersKey": "churchill.rocketmq.default-config", "producersKey": "churchill.rocketmq.producers" },
  "safety": { "forceIndependentMqGroup": true }     // 不可妥协项：影子实例禁入共享消费组
}
```

**换栈 = 换/新增一份描述符**：Eureka=换 registryPatch 键组；SC LB hint=换 routingEnabler 键；apollo=换 configCenter.type + bootIsolation。**但注意边界**（详见 BOUNDARY-map.md）：代理工具切换（telepresence 等）=机制切换，非模板字符串可表达；流量染色端到端依赖组织路由启用器（L4 前提）。

**已声明未接线字段清单**（P2/P3 勿误以为可用）：`safety`（除 forceIndependentMqGroup 已接线）、`bootIsolation`、`credentials`、`formFields`、`configCenter.fallbackDataIdSuffix`（`.yaml` 兜底仍硬编码于 ProjectReplaceAction）、`middlewareGroup.mqConsumersKey`、`routingEnabler` 的 httpLaneHeader/rpcAttachmentKey/shadowNameTemplate（仅 Validator 引用）、`RollbackPlanner`（待 rollback 命令通道）。

### 2.3 debugProfile（取代 BFF/RPC 二分）

```jsonc
{
  "profiles": {
    "bff":  { "patches": ["portOverride:bff"],  "proxySteps": ["connect","mesh","recover","clean"], "extraJvm": [] },
    "rpc":  { "patches": ["portOverride:rpc"],  "proxySteps": ["connect","preview","recover","clean"], "extraJvm": [] },
    "grpc": { /* 用户自建，不需要改插件 */ }
  }
}
```

profile 由「namespace 启发式 + 项目特判」推导出**默认值**，用户可在 `.localdebug.json` 显式指定——推导规则本身（H3）移到 global 配置，内置值仅回退。

### 2.4 分级校验引擎（H1 替代 isValidConfig）

- **声明式**：middleware-patches 中每个 `${占位符}` 声明其来源配置字段；校验 = 收集「实际启用的 profile 引用的 patches」→ 求所需字段集合 → 对照项目/全局配置查缺。
- **两类校验必须分离**（委员会共识）：
  - **字段校验**（确定性错误）：缺配置字段 → fail-fast 报错，带字段名与设置页路径
  - **前提确认**（概率性错误）：组织路由启用器缺失 → 只能提示确认，**不能验证、不能报「已通过」**——插件探测不到集群侧状态，措辞必须是「前提确认」而非「检查通过」，防仪式感幻觉
- **强制不变式**：`forceIndependentMqGroup` 恒校验、绝不进 optional（影子实例抢 dev MQ 消息是安全问题，不是可用性问题）。P1 实现语义：validation.forced 与 safety.forceIndependentMqGroup 二者取或（防死字段）；证据键 `mqConsumersConfigured`（=消费者列表已配置，文案已按此降级；「消费组是否真与 dev 隔离」的强校验留 P2/P3）。
- **audit 文件**：init 产出 `.localdebug.audit.json`——routingEnabler.requiredOrgComponents 逐条 unconfirmed 清单（无确认通道，P3 向导时补 confirmed 流）；不阻塞 init；通知经 invokeLater 派发到 EDT（后台线程安全）。文件留在项目根，可 review、可贴给组织基建团队。
- **H2 实现说明**：P1 采用「实际使用的占位符检查」——MiddlewarePatchUtil 收集被使用但解析为空的占位符 → 映射配置字段名报错（缺什么报什么）；原「patch 挂 requires 声明」方案降级，P3 再评估。replace 侧：nacos 配置文本含 `${mainServiceRamRoleName}` 而 ramRole 未配 → 报错退出，不再写占位符字面量。
- **描述符元校验**：describeor 加载时做 schema 校验（schemaVersion）+ 引用检查（registryPatch 引用键存在、mode 声明与 routingEnabler 键一致、proxyModel × runtimeEnv 交叉合法）——数据写错一个键 = 新式静默失败，必须比代码 bug 更早暴露。
- `validation.required`（如 configCenterAddr）恒校验；其余按需。
- 空配置可启动（不再全有或全无），缺什么报什么（带字段名与所在的设置页路径）。

### 2.5 组织数据出包（H3/H4/H5）

| 数据 | 去向 | 内置回退 |
|---|---|---|
| nacosNamespaceMap（H3） | `~/.localdebug/config/namespace-map.json` | jar 内旧值（标注 deprecated） |
| serviceType 推导规则（H3） | `~/.localdebug/config/project-rules.json` | jar 内旧值 |
| pom 依赖默认模板（H4） | `~/.localdebug/config/dependency-template.json` | 内置回退旧值（保回归）；用户写 `[]` 即达成新团队零污染 |
| 工具下载 URL（H5） | config.json（已在，仅默认值出包） | 留空引导首次配置 |

## 3. 关键类设计

```java
// P2：两个接口 + 注册表（配置文件选实现，不用 IntelliJ EP）
interface ConfigCenterProvider {
  String name();
  boolean testConnection(Config cfg);
  String pull(Config cfg, String namespace, String dataId, String group) throws Exception;
  List<String> candidateDataIds(String dataId);   // 默认 [dataId, dataId+".yaml"]
}
interface CredentialProvider {
  String name();
  Credential get(Config cfg, String purpose);     // purpose: mq|kms|scheduler
  boolean needsRefresh(Credential c);
}
// 注册表：ProviderRegistry，实现映射来自 ~/.localdebug/config/providers.json（type 字符串 → 类注册表，内置实现默认存在）

// P1：数据对象
record StackDescriptor(...)                       // §2.2
record DebugProfile(patches, proxySteps, extraJvm)
engine/ValidationEngine                             // §2.4
engine/RegistryPatchApplier                         // §2.2 registryPatch 应用（取代硬编码 putByPath 组）
engine/DescriptorValidator                          // ★ schema 校验 + 引用检查 + 交叉组合校验（描述符错误=新式静默失败，必须早暴露）
engine/RollbackPlanner                              // ★ rollback 动作按 proxyModel 分化：mesh-service=recover{svc}；intercept=un-intercept；portforward=无动作（防影子 Service 残留污染 dev）

// ★ L3 预留接缝（全行业愿景）：只建接口签名，实现按真实需求后补，内核不为此改动
bridge/ProxyBridge                                  // 流量通路抽象（已落地签名）：start/stop/isRunning/hasRunningProcesses/getRunningProcessesInfo/stopAll
                                                    //   实现① KtConnectBridge（P1 落地，模板数据 + 信号文件 + M5 命令守卫 + PATH 前置）
                                                    //   未来：TelepresenceBridge / DirectBridge / 非K8s(docker-compose) 通路（rollback/discoverResources 待接线时补签名）
project/ProjectAdapter                              // 语言/构建适配（未建，接缝声明）：discoverConfigFiles / injectPatches / findEntryPoint / rewriteBuildFile
                                                    //   实现① MavenSpringAdapter（P1 只做收敛准备：配置文件发现收敛为单一方法）
                                                    //   未来：GradleAdapter / GoKratosAdapter / NodeNestAdapter
// 注：染色跨协议（gRPC metadata 等）仍走 routingEnabler 数据 + L4 前提声明，不需要新接口

// 复用（不重写）：MiddlewarePatchUtil、MavenUtil、PortUtil、Action 流程、DebugCommandService
```

**改造清单（对照审查问题）**：
- `ProjectInitAction`：H2 dubbo/nacos 键删 → RegistryPatchApplier；H3 出包；H1 改 ValidationEngine；服务名推导算法保留（编辑距离+粗筛，通用），namespace 过滤（dev1）→ 描述符/配置
- `ProjectReplaceAction`：M3 producers 重写 → patches（P1 已接线：读 middlewareGroup.producersKey）；M5 占位符表 → 数据（TODO P2，仍硬编码 `${mainServiceRamRoleName}`）；M8 模板 → 描述符（已接线）；jvmrc 生成读描述符 routingEnabler 段（已接线，rpcPort 与 init 同源 forcedRpcPort）
- `ConfigManager`：原子写（tmp+rename）、解析失败保留原文件、单例
- `PortUtil`：synchronized 分配段
- ktctl 命令启动（DebugCommandService.start）顺带修两隐患：PATH 注入 `~/.localdebug/bin` + 参数改 `cmd /c`/`sh -c` 整串执行（避免空白切分）

## 4. 目录/文件规划

```
~/.localdebug/
├── config/
│   ├── config.json                  # 全局配置（加固后）
│   ├── stacks/*.json                # 用户栈描述符（新增，可选）
│   ├── namespace-map.json           # H3 出包
│   ├── project-rules.json           # H3 出包
│   ├── dependency-template.json     # H4 出包
│   ├── providers.json               # P2 provider 选择
│   └── patches/middleware-patches.json  # 用户补丁（可覆盖内置）
├── bin/  signal/  ...
```

jar 内置：`resources/com/localdebug/plugin/core/`（StackDescriptor 默认一份 = 现硬编码内容全量搬入，保行为等价）。

## 5. 分阶段实施与验收

| 阶段 | 改动（文件级） | 验收标准 |
|---|---|---|
| **P0 加固**（1-2 文件） | ConfigManager 原子写+单例；PortUtil 加锁；isValidConfig 改「nacos 必填 + 其余可选」（H1 最小缓解） | ① 旧流程行为不变 ② ConfigManager 损坏 json 不再清空配置（单测）③ git init + 基线 tag |
| **P1 数据化**（~15 文件） | §3 改造清单全部落地；内置 StackDescriptor 内容 == 现硬编码（逐键对照）；中间件 patches 挂 requires 声明；DescriptorValidator + RollbackPlanner + audit 文件生成 | ① **回归**：旧团队默认栈 init/replace/rollback 产出文件与改造前逐字节一致（diff 校验，差异逐条解释）② 描述符通道可执行 + 前提缺失时 audit/自检清单**正确拦截**（恶用例：描述符键拼错必须被 DescriptorValidator 拒绝）③ 单测覆盖 ValidationEngine/RegistryPatchApplier/DescriptorValidator/MavenUtil ④ 「真实端到端调试通」需真集群，**明确列验收外**——验收只验证通道与拦截，不冒领端到端承诺 |
| **P2 Provider 化**（4-6 文件） | ConfigCenterProvider/CredentialProvider + 注册表 + 内置 nacos/static/aliyun-sts 实现；ALiYunClient 变 provider 实现 | ① core 内无直接 `new NacosConfigService / new ALiYunClient` ② provider 接口单测（fake 实现）③ 旧栈回归不变 |
| **P3 模板库+表单向导（用户已确认需求）** | 内置/导入模板库；表单向导（选模板 → 渲染 `formFields` 声明式表单 → 生成/导入 descriptor）；模板 schema 加 formFields 段 | 向导是**通用字段渲染器**——写一次，任意模板自动长表单，不为每份模板写专属 UI；apollo/consul provider 实现等真实生态需求，不预建 |

## 6. 测试与验证策略

- **单测**（JUnit，`src/test/java`）：ValidationEngine（占位符→字段推导）、RegistryPatchApplier（键写入与合并语义）、ConfigManager（原子写/损坏保护）、MavenUtil（幂等替换/回滚）、PortUtil（并发分配唯一）。
- **回归**（P1 核心）：改造前后对样例项目跑 init/replace/rollback，diff 产出文件（.localdebug.json、ktctl.cmds、jvmrc、application.yaml、pom.xml）逐字节比对。
- **IDE 冒烟**：runIde 手测 ToolWindow 三页签 + 全流程（用户侧完成，因 ActionToolbar 需 IDE 运行时）。
- **审批 gate**：P1 的「内置描述符 == 现硬编码」对照表作为评审附件；任何行为差异必须在方案冻结前显式确认。

## 7. 风险与假设

1. **回归是最大风险**：方言数据化过程中键顺序/合并语义变化会导致 diff 噪音 → 用逐字节比对 + 对照表控制；diff 验收滑向「确认噪音」= 语义变化被一起吞掉，差异必须逐条解释。
2. **通用边界（L4 前提）**：端到端「能 debug 通」依赖三个不可绕过前提——注册中心支持地址覆盖、隧道工具建集群 DNS（k8s）、组织存在路由启用器。缺任一个 = 影子实例收不到流量且静默失败。插件以契约字段 + audit 文件 + 前提确认清单显性化，不承诺跨过（BOUNDARY-map.md）。
3. **描述符数据可靠性**：通用化把「代码可靠性」换成「数据可靠性」——描述符写错键=新式静默失败（无编译检查、无人 review）→ DescriptorValidator 元校验必须比代码 bug 更早暴露。
4. **描述符退化风险（贫血 DSL）**：描述符内嵌引擎分支语义 = 换描述符变「改描述符+改引擎」双维护 → bootIsolation 等原则性字段限期收敛进 Provider（P3 gate）。
5. **组合爆炸**：五维字段交叉组合用户写不出第二份描述符 → 继承语义（内置模板+覆盖差异）是落地前提。
2. 编译基线 2023.1.5 vs 用户 IDE 262：已知风险，涉及平台 API 时二次确认。
3. Istio sidecar 网格环境下 mesh 隧道受限（调研 §9）→ 描述符增加 `note` 字段，文档层面提示；本期不解决。
4. 广度假设：本期以「Maven + Java + nacos + dubbo + ktctl」默认栈保行为等价，「eureka/apollo/grpc/telepresence」只验证描述符通道不验证真实环境（真实接入需真集群，列为验收外事项）。

## 8. 预留拓展：全行业愿景（接缝已建，实现按需）

用户愿景：全行业微服务本地调试（跨语言/跨运行时/跨部署形态）。原则：**现在建接缝，不建实现**。

- **ProxyBridge**（流量通路）：k8s 隧道模型只是实现①；非 K8s 直连、VPN、Serverless 通路 = 未来实现②③——内核（描述符/校验/audit/向导）不随通路变
- **ProjectAdapter**（语言/构建）：Maven+Spring 只是实现①；Gradle/Go/Python/Node = 未来实现。P1 唯一前置动作＝把「配置文件发现」收敛为单一方法，不做接口重构
- **IntelliJ 平台事实**：IDEA/GoLand/PyCharm/WebStorm 同平台——语言扩展天然有宿主 IDE；跨 IDE 分发只需平台兼容性构建矩阵（P3 后按需）
- **明确不现在做**：不写任何非 Java 适配器、不建远程/非 K8s 通路、不做跨 IDE 打包矩阵——真实需求出现时，接缝已就位，补充实现即可

## 9. 明确不做（YAGNI）

- IntelliJ extension point 形式注册 provider（配置文件选实现够用）
- 预建 apollo/consul/aws 实现（接口留好，实现随需求走）
- 设置页「列表配置编辑 UI」（M6）——本期只保 json 编辑通道不劣化
- Gradle 构建支持、非 Java 栈 runtime 支持
- 数据库/Redis 自动隔离

## 10. 实施顺序（派工批次）

1. `git init` + 基线 tag（我直接做）
2. P0（fixer，1 批次）
3. P1（fixer，2 批次：先数据对象+引擎+单测，再 Action 改造+回归 diff）
4. P2（fixer，1 批次）
5. 每批次编译 `-x test` + 单测全绿 + 汇总报告；P1 后用户 runIde 冒烟