# 通用化边界地图（可抽象 vs 不可抽象）

> @oracle 独立复核（2026-09-21），审查对象：四份文档 + core 代码。本文是「通用性」的最终真相声明。
> 判级基准：区分**「插件能表达的」**与**「端到端调试能通的」**——两者不同，这是全部结论的根。

## 0. 判级四级

- **L1 完全数据化**：配置键/模板差异，StackDescriptor 字段即表达，换组件零代码
- **L2 Provider 接口**：调用形态不同（拉配置 API 不同），接口+实现，接入成本低
- **L3 引擎/流程级扩展**：不只是「调谁/写什么键」，而是「做什么动作」不同，需扩展点或引擎改造
- **L4 原则上不可抽象**：依赖服务端合作/环境前提/组织私有设施，插件无法单方面通用，只能文档化前提

## 1. 十二维度边界地图

| # | 维度 | 等级 | 机制级理由（证据） |
|---|---|---|---|
| 1 | 注册中心（nacos/eureka/consul/zk/etcd/k8s） | **L1（前提成立时）**，与代理层强耦合 | 全支持注册指定 IP（RESEARCH §2）；但伪装地址 `{svc}-{tag}.{ns}.svc.cluster.local` 必须由代理层真建同名 k8s Service 才可解析——注册 L1 是「借」代理层的 DNS。代理不建 Service（port-forward）→ 失效，退 L3 |
| 2 | 配置中心（nacos/apollo/consul-KV/scc/configMap） | **L2（拉取）+ L3 尾部（启动期隔离）** | 拉文本语义同构（Provider 成立）；但「障眼法」（bootstrap app.name=application-localdebug + import-check=false，机制篇 §3.2）是 nacos/SC 特有；Apollo=独立 appId/cluster，configMap=文件挂载无需隔离——启动期隔离策略按中心重设计，签名级覆盖不了 |
| 3 | 流量染色（dubbo tag / SC hint / Istio subset / MSE/TSF） | **L1（provider 标签键）+ L4（组织路由启用器前提）** | provider 侧全是框架原生键（数据化成立）；**消费侧透传依赖组织库**（platform-common 实证：A 缺库 → attachment 丢失 → 流量静默走 dev 不报错）；入口侧依赖组织网关改写组件。插件写 tag 没人路由=静默失效 |
| 4 | RPC 协议层（dubbo/gRPC/Feign/RestTemplate） | **注册伪装 L1-L2；染色能力按协议分化** | dubbo `DUBBO_IP_TO_REGISTRY` 与 gRPC name-resolver 是不同注册语义；伪装 DNS 对 gRPC 同样成立。分化在染色：**gRPC 无 per-user tag 原语** → 只能全量影子（多人互踩）或 Istio subset（L3）→ `dyeingMode: none` 显式降级 |
| 5 | HTTP 网关染色（SCG/Kong/APISIX/Higress/Traefik/Ingress） | **L1（header 约定）+ L4（入口改写组件）** | 各家都支持 header match→改后端（RESEARCH §4）；但 LocalRouteForwardFilter 这类改写组件在**组织网关代码**里，插件不可携带。缺组件 → 请求到正式实例、染色静默失效 |
| 6 | 代理隧道（kt-connect/Telepresence/port-forward） | **L2-L3** | ktctl mesh/preview=**建带 tag 的 k8s Service+隧道**；Telepresence=intercept 替换 Deployment（不建 Service）→ 注册伪装模型失效+染色命名契约重对齐；port-forward=无 Service。**工具切换=机制切换**，模板字符串表达不了 |
| 7 | MQ 隔离（rocketmq/kafka/rabbit/pulsar） | **L1** | 独立消费组全是配置键（RESEARCH §6）；「影子禁入共享消费组」作强制校验；BROADCASTING/CLUSTERING 语义差异文档注明 |
| 8 | 云凭证（aliyun STS/AWS/GCP/K8s SA） | **L2（Provider）+ L4 前提（角色类凭证本地不可用）** | 节点角色/IRSA/Workload Identity 本地全不可用，本地一律显式凭证（跨云普遍规律）；获取方式=接口差异。静态 AK/SK 场景连 Provider 都不需要（直接注入） |
| 9 | 任务调度防抢（xxl-job/powerjob/elasticjob/quartz） | **L1 为主 + L3 尾部（quartz/@Scheduled）** | xxl 独立 appname、powerjob 不启 worker、elasticjob 独立 namespace=配置键；quartz 本地禁用=条件 Bean/Profile=**代码**，插件不可注入 |
| 10 | 构建体系（Maven/Gradle） | **L3** | MavenUtil=pom 正则改写+changeInfo 回滚；Gradle 无等价物，需整套新引擎+回滚机制 |
| 11 | 运行环境（纯 K8s/sidecar 网格/非 K8s） | **纯 K8s=L1/L2；sidecar=L4 前提；非 K8s=L4 整模型失效** | 整条「回家路」依赖 k8s Service DNS + `svc.cluster.local`（机制篇 §1）；sidecar 与 shadow pod 冲突（RESEARCH §9）；**docker-compose 等无集群 DNS，注册伪装无处解析** |
| 12 | 服务端配套组件（LocalRouteForwardFilter、platform-common 染色库） | **L4 核心边界** | 本质=**路由启用器（routing enabler）**：「入口请求→影子实例」闭环里插件管不到的一半。dubbo 栈=消费侧透传库+入口组件；SC 栈=仅入口 5 行 filter；Istio=infra 清单（无代码有运维） |

## 2. 三个结论

### 结论 1：真实通用边界（一句话）

> 插件能通用 = **「注册中心支持注册地址覆盖 ∧ 集群 DNS 可达（隧道工具建 k8s Service）∧ 组织存在路由启用器（入口按 header/tag 分流到影子实例）」三前提同时成立**下的任意组件组合，全部可由 StackDescriptor + 2 Provider 表达。**不可绕过的前提 = 集群侧路由启用器 + k8s 集群 DNS**——缺任一个，影子实例收不到流量且**静默失败**。

「什么组件都通用」的真相：**插件侧机制（写什么键/拉什么/发什么凭证）可通用 80-90%；端到端「能 debug 通」永远依赖组织的路由启用器**——这是任何 IDE 插件都跨越不了的边界，只能靠契约文档 + 接入自检把前提显性化。

### 结论 2：既往文档分级错误（修正表）

| 维度 | 既往判定 | 修正 |
|---|---|---|
| 流量染色 | L1 数据化，RoutingStrategy 作插件 SPI「P3 缓建」 | **L1(provider 键)+L4(组织前提)**。RoutingStrategy 不该是插件 SPI——插件写 tag 没人路由=静默失效。错位：把「前提文档+接入自检」当成了「待建插件代码」 |
| 代理隧道 | 「telepresence=换 proxy.templates 零代码」 | **L2-L3**：工具切换=机制切换（Service+tag vs intercept vs 无 Service），级联注册策略与染色命名契约 |
| 配置中心 | L2（拉文本接口） | **L2+L3 尾部（启动期隔离策略）**——障眼法是 nacos/SC 特有流程，Apollo/configMap 各自不同 |
| 注册中心 | L1（纯数据） | **L1（前提成立时）**，补耦合声明：伪装地址可解析依赖代理层建 Service |
| RPC 协议层 | 未单列 | 应显式分级：gRPC 无 tag 原语→全量影子/L3 infra，是「该栈能通到 L 几」的分水岭 |
| 任务调度 | 笼统归数据层 | L1 为主 + quartz 是 L3 尾部 |
| 运行环境 | 仅 mesh 加 note | **非 K8s 是整模型失效的 L4**，应显式声明而非省略 |

### 结论 3：StackDescriptor 修订

**加**：
1. `routingEnabler` 契约段：header 名、实例标签键、影子命名模板、组织侧必须存在什么（消费侧透传库/网关改写组件/Istio VS）+ init 时**接入自检清单**（首次 init 明确提示「请确认网关已按 header tag 改写目标、消费侧已透传」）
2. `proxyModel: "mesh-service" | "intercept" | "portforward" | "direct"`——proxy 不能只是一组模板；模型决定注册策略与染色命名契约
3. `dyeingMode: "provider-tag" | "consumer-hint" | "infra-subset" | "none"`——none 时显式降级全量影子并警告（gRPC）
4. `bootIsolation` 段——按 configCenter.type 分化的启动期隔离策略
5. `runtimeEnv: "k8s" | "mesh" | "non-k8s"`——non-k8s 明确拒绝或文档化提示

**改**：credentials 默认空 + 按 patch 占位符按需启用；validation 加 routingEnabler 前提自检项；dyeing 段收敛进 routingEnabler（避免两处表达同一契约）。

**删**：**RoutingStrategy 作为插件 SPI**——从 P3 移除，改为文档化 + 自检清单。

**总评**：方案方向对，但把「L4 前提」错位成「L1 数据/L2 SPI」，承诺过度。修订后如实声明：插件通用的是「注册/拉取/凭证/注入」四类机制 + 组织路由启用器契约；**组织前提显性化之日，才是通用性成立之时**。