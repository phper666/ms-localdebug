# 微服务配套技术栈全景调研（2025–2026）

> 调研来源：@librarian（2026-09-21，版本经 GitHub release / Apache archive 实测，机制经官方文档 + DeepWiki 源码级核实）。
> 上游文档：DESIGN-generic.md（通用化方案）、DESIGN-mechanism.md（机制篇）。本文是通用化选型的外部依据。
> `待确认` = 未逐字核实项，实施时需二次验证。

## 横向结论（对插件通用化最相关的四条）

1. **所有主流注册中心都支持「注册时指定 IP」**——这是本地调试插件的最底层抓手（各栈配置键见 §2）。
2. **流量染色只有两条成熟路线**：① header 打标 + 实例元数据匹配（Dubbo tag / SC LB hint / Istio subset——三者语义一致，可抽象为同一「tag→instance」模型）；② K8s 层换 Service 目标（kt-connect 路线）。两者正交，插件应同时支持。
3. **MQ / 任务调度 / 云凭证的「本地调试」本质都是身份隔离**：独立 consumer group / 独立执行器组 / 本地凭证链——不依赖流量染色，走数据与凭证层。
4. kt-connect 本体 release 停在 v0.3.7（2022-11）：mesh 机制 = shadow pod（`kt-role: kt-mesh-shadow` label）+ nginx router 按 header 分流 / manual 模式交 Istio 路由。注意它有「改 Service selector」副作用。

## 1. 服务框架

| 框架 | 生态 | 主版本 | 本地调试相关机制 |
|---|---|---|---|
| Spring Cloud | 国际（中国亦普遍） | 2025.1.3（配 Boot 4.x）；2025.0.x（配 Boot 3.5） | 注册 IP 由 DiscoveryClient 子实现决定；LB hint 染色（§5）；`spring.cloud.discovery.enabled` 可关注册 |
| Spring Cloud Alibaba | 中国 | 2025.1.0.0 | `spring.cloud.nacos.discovery.ip` 指定注册 IP（核心）；`discovery.register-enabled=false` 只订阅不注册 |
| Dubbo | 中国（国际亦有） | 3.3.6 | `DUBBO_IP_TO_REGISTRY` / `DUBBO_PORT_TO_REGISTRY`；`dubbo.provider.host`；tag 路由（§5） |
| gRPC | 国际 | 1.84 | 无注册中心绑定 IP 概念——地址由 name resolver 提供；本地调试=自定义 resolver 或客户端 target 直指 localhost。`待确认`各语言 resolver 细节 |
| go-zero | 中国 | 1.10.3 | 注册 key={ip}:{port}，由 `ListenOn` 决定；zrpc `NewDirectClient` 直连本地。`待确认`键名 |
| Kratos | 中国 | 3.0.0（API 大改） | registry endpoint 可指定 IP:port。`待确认`细节 |
| Quarkus 3.39.4 / Micronaut 5.2.2 | 国际 | | dev mode 直连 + 配置覆盖。`待确认` |
| Node(NestJS) / Python | — | | 注册 host 普遍可配；本地调试常靠 K8s 层工具。`待确认` |

## 2. 注册中心：能否指定注册 IP/hostname（插件最底层抓手）

| 注册中心 | 生态 | 主版本 | 指定注册地址机制 |
|---|---|---|---|
| Nacos | 中国 | 3.2.4 | ✅ 注册 API 直接带 `ip/port/metadata`；Spring `spring.cloud.nacos.discovery.ip(.port)`；Dubbo 走 `DUBBO_IP_TO_REGISTRY` |
| Eureka | 国际 | server 2.0.6 | ✅ `eureka.instance.ip-address/hostname/prefer-ip-address`；`metadata-map.*` 打染色标签 |
| Consul | 国际 | 2.0.4 | ✅ Agent 注册 body `Address` 字段；Spring `spring.cloud.consul.discovery.instance-*`；`discovery.tags` |
| ZooKeeper | 国际（老） | 3.9.6 | ✅ 临时节点数据由注册方写入，IP 完全可控（Dubbo `DUBBO_IP_TO_REGISTRY`） |
| etcd | 国际 | 3.7.1 | ✅ 注册=写 key，IP 由写入方指定 |
| K8s Service 原生 | — | — | Pod IP 自动填 Endpoint；本地调试：kt-connect / Telepresence / 手工改 Endpoint / ExternalName |

## 3. 配置中心：程序化拉取 API 形态

| 配置中心 | 生态 | 主版本 | 拉取 API |
|---|---|---|---|
| Nacos | 中国 | 3.2.4 | `GET /nacos/v2/cs/config?dataId=&group=&namespaceId=`；SDK 长轮询订阅 |
| Apollo | 中国 | 2.5.2 | `GET {config-service}/configfiles/{appId}/{cluster}/{namespace}`（properties 文本）；开放平台 API；本地调试用独立 appId/cluster 即不污染 dev |
| Consul KV | 国际 | 2.0.4 | `GET /v1/kv/{key}`（watch long-polling） |
| Spring Cloud Config | 国际 | 5.0.5 | `GET /{application}/{profile}[/{label}]` |
| K8s ConfigMap | — | — | 挂载文件/Env；本地用 profile 覆盖 |
| AWS AppConfig | 国际 | — | `GetConfiguration`（SDK 轮询） |

## 4. 网关：header 染色转发能力

| 网关 | 生态 | 主版本 | 机制 |
|---|---|---|---|
| Spring Cloud Gateway | 国际（中国亦用） | 5.0.3 | 自定义 GlobalFilter 写染色 header（`X-SC-LB-Hint`）→ LB hint 闭环；或 filter 直接改 uri |
| Kong | 国际 | 3.9.3 | `request-transformer` 加 header；按 header 选上游需自定义插件；`traffic-split` 按 header 分流 |
| APISIX | 中国 | 3.18.0 | `serverless-pre-function`（Lua）加 header 改 upstream；`traffic-split` 按 header 分流 |
| Higress | 中国 | 2.2.4 | Envoy 内核：HTTPRoute header match → 指定后端；Wasm 插件 |
| Istio Ingress | 国际 | 1.31 | VirtualService `headers.match` → destination subset（入口即完成染色） |
| Traefik | 国际 | 3.7.13 | Router rule `Header(...)` → 对应后端，天然按 header 路由 |

## 5. 流量染色 / 全链路灰度

| 方案 | 生态 | 机制（经源码/文档核实） |
|---|---|---|
| Dubbo tag 路由 | 中国 | 消费者携带 `dubbo.tag` attachment；`TagStateRouter` 按 tag 过滤 provider；`force.use.tag=true` 无匹配即失败不降级；tag 规则可动态下发；消费者侧支持 `DUBBO_TAG` 环境变量；provider 注册带静态 tag |
| Spring Cloud LB hint | 国际 | `HintBasedServiceInstanceListSupplier`：请求头 `X-SC-LB-Hint`（键可配 `spring.cloud.loadbalancer.hint-header-name`）→ 匹配实例 metadataMap `hint`；无匹配回退全量 |
| Istio lane/泳道 | 国际 | VirtualService `http.match[].headers` → `destination.subset` → DestinationRule 按 pod label 分 subset；入口染色全链路透传 |
| 阿里云 MSE 全链路灰度 | 中国 | 实例打灰度标签 → 入口按请求 header 灰度标识路由（header 名 `待确认`）；agent 自动透传 |
| 腾讯 TSF 泳道 | 中国 | 命名空间+部署组+泳道规则（`待确认`细节） |
| SOFAMesh | 中国 | 停滞（2019），不建议新选型 |

## 6. 消息队列：消费组隔离（本地不抢 dev 消息）

| MQ | 生态 | 主版本 | 隔离机制 |
|---|---|---|---|
| RocketMQ | 中国 | 5.5.1 | 独立 consumer group（同组 CLUSTERING 会抢队列）；BROADCASTING 组内每实例收全量（调试友好）；`subscribe(topic, tag)` 二次过滤 |
| Kafka | 国际 | 4.3.1 | 独立 `group.id` 消费全量副本（注意 `auto.offset.reset`）；加入 dev 同组会 rebalance 抢分区 |
| RabbitMQ | 国际 | 4.3.6 | 独立 queue（绑定同 exchange）或独立 vhost；fanout 天然复制 |
| Pulsar | 国际 | 4.2.4 | 独立 subscription 名即独立消息流 |

## 7. 云凭证：本地进程获取方式

| 云/平台 | 本地常用方式 |
|---|---|
| 阿里云 | 环境变量 AK + STS `AssumeRole`（插件现状）；或 `ALIBABA_CLOUD_CREDENTIALS_URI` 指向本地 mock；ACK 内走 OIDC Pod Identity（本地不可用） |
| AWS | `AWS_PROFILE`（SSO/静态）/ 环境变量；IRSA 本地可模拟 OIDC token 文件；EKS Pod Identity 本地不可用 |
| GCP | ADC：`gcloud auth application-default login`（本地开发最常用）→ `GOOGLE_APPLICATION_CREDENTIALS` |
| K8s SA | `kubectl create token <sa>` 拿短时 token；或 `kubectl proxy` + 代理访问 |

**共性**：云上身份机制（节点角色/IRSA/Workload Identity）本地都不可用，本地一律走「显式凭证」——印证机制篇 §3.3 的「集群内身份 → 显式凭证」替换原理，且这是跨云通用的规律。

## 8. 任务调度：本地不抢定时任务

| 调度器 | 生态 | 主版本 | 「本地不抢」机制 |
|---|---|---|---|
| XXL-Job | 中国 | 3.4.2 | 无专用开关。① 独立 `xxl.job.executor.appname`（独立执行器组不接任务）；② 调度端 addressType=1 手动录入集群地址；注册键 `appname/ip/port/address` 可显式指定 IP（对插件友好） |
| PowerJob | 中国 | 5.1.2 | 不启动 worker 即不注册；独立 appId/namespace 隔离。`待确认`显式禁用配置 |
| ElasticJob | 中国 | 3.0.5 | 本地不加载 job 配置即不注册（ZK）；或独立 namespace。`待确认`3.x 配置键 |
| SchedulerX | 中国 | 云服务 | namespace+应用分组隔离；兼容 XXL/ElasticJob/Spring Schedule 协议。`待确认`本地模式细节 |
| Quartz | 国际 | 2.3.x（停滞） | Spring `@Scheduled` 用 profile/条件 Bean 禁用本地触发 |

## 9. 服务网格：对 kt-connect 类工具的影响

| 网格 | 主版本 | 与 kt-connect 的相互作用 |
|---|---|---|
| Istio sidecar | 1.31.0 | kt-connect mesh manual 模式即为此设计（shadow pod `kt-role: kt-mesh-shadow` + version label → DR/VS 路由）；⚠ shadow pod 若被注入 sidecar，SSH 隧道流量会被 iptables 拦截（`待确认`kt-connect 处理）；改 Service selector 的做法可能被 mesh 路由绕过 |
| Istio ambient | 1.31 | ztunnel L4 透明拦截，无 pod iptables 注入，冲突更小。`待确认`ztunnel 对 selector 变更的响应 |
| Linkerd | 2.20 | 类似 sidecar；ServiceProfile/HTTPRoute 可配合 manual 模式 |

## 10. 部署运行时

K8s 1.3x（事实标准，本地调试工具的宿主层）；Helm 4.3.0（弱相关）；Knative 1.23（scale-to-zero 与常驻本地实例冲突）；SAE（集成 MSE 治理，复用染色机制）。

## 11. 对插件设计的直接结论

1. **注册层**：面向「注册中心 API/配置键直写 IP + metadata 标签」抽象——Nacos/Consul/Dubbo/Eureka 均有原生能力，全部是配置键级差异（数据化即可，不需要 SPI）
2. **流量层**：「header 染色 × 实例标签匹配」三套主流机制（Dubbo tag / SC hint / Istio subset）语义一致 → 抽象为统一「tag→instance」数据模型；HTTP 网关侧同理（各家都有 header match → 改后端）
3. **隔离层**（MQ/任务/凭证）：走身份隔离而非流量控制 → 独立消费组/独立执行器组/显式凭证链，全部可用 patch 数据 + CredentialProvider 表达
4. **网格环境**：Istio 环境下 mesh shadow pod 有 sidecar 冲突风险 → 通用化文档中需标注前提（无网格或 ambient）
