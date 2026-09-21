# 通用化设计方案：注册中心 / 配置中心 / 三方服务

> 状态：提案。⚠ 分级经 @oracle 独立复核修正——「可抽象/不可抽象」的最终结论以 **BOUNDARY-map.md** 为准（本文的 RoutingStrategy 缓建、proxy 模板 L1 判定已被修正为 L4 前提 / L2-L3）。

## 0. 核心判断

微服务本地调试中，插件对任何外部服务只需要做**三件事**：

| 触碰点 | 现状实现 | 本质 |
|---|---|---|
| ① 注册指向本地（让 dev 流量能找到本地实例） | nacos discovery.ip 伪装 + dubbo tag（硬编码） | **改 yaml 键** + 服务端染色约定 |
| ② 拉应用配置（把 dev 环境配置拉下来改成本地可用） | nacos SDK（硬编码） | **一次远程读取** |
| ③ 给凭证（让本地 SDK 能连云上中间件） | 阿里云 STS（硬编码） | **发/取一个临时凭证** |

其余（MQ/Redis/OSS/Scheduler 的 endpoint、ak、topic…）全部是 **yaml 数据**，patch 引擎已支持。所以通用化 = 80% 数据化收尾 + 3 个小接口（其中 1 个可缓建）。

## 1. 生态盘点（与插件触碰点对照）

### 1.1 配置中心（触碰点②）
| 产品 | 接入方式 | 通用化难点 |
|---|---|---|
| **Nacos**（现状） | SDK `getConfig(dataId, group, ns)` | 无，已是内置实现 |
| Apollo | Open API / 客户端缓存文件 | 接口签名不同，但"按 appId+env+ns 拉文本"语义相同 |
| Consul KV | HTTP GET | 同上 |
| Spring Cloud Config | HTTP `{app}/{profile}/{label}` | 同上 |
| K8s ConfigMap | kubectl/官方 client | 可复用插件已有 kubectl 能力 |
| etcd / ZooKeeper（旧） | gRPC/客户端 | 低优先级 |

**结论**：`拉一段文本配置` 是全部产品的公共语义 → 一个 Provider 接口覆盖。

### 1.2 注册中心（触碰点①）
| 产品 | "指定实例注册到某 IP" 能力 | 染色/灰度机制 |
|---|---|---|
| **Nacos discovery**（现状） | `spring.cloud.nacos.discovery.ip/port` 覆盖 | dubbo tag / label 路由 |
| Eureka | `eureka.instance.hostname/ip-address` | metadata + 自定义负载均衡 |
| Consul | `spring.cloud.consul.discovery.ip-address` | 同上 |
| ZooKeeper（dubbo 老栈） | dubbo 协议层 `dubbo.provider.host` | dubbo tag 原生 |
| K8s Service（无注册中心） | 直接 `{svc}.{ns}.svc.cluster.local` | Service Selector / Istio header |

**结论**：各产品差异 = **要覆盖的 yaml 键不同**（纯数据）+ 染色约定不同（服务端组件，插件只管往 header/注册参数里写约定的 tag 键）。→ 先数据化；仅当接入"非 tag 型染色"（Istio/自研网关协议）才需要策略接口。

### 1.3 三方服务/中间件（触碰点③ + patch 数据）
| 类别 | 常见实现 | 插件动作 |
|---|---|---|
| MQ | RocketMQ（现状）、Kafka、RabbitMQ、Pulsar | 注入 addr/ak/topic + 消费组隔离 |
| 缓存 | Redis（通常共享 dev 实例，key 前缀隔离） | 注入 endpoint，或不动 |
| 对象存储 | OSS（现状）、S3、MinIO | 注入 endpoint/凭证 |
| 任务调度 | SchedulerX（现状）、XXL-Job、ElasticJob | 注入 namespace/app-key，防本地任务抢跑 dev |
| 凭证 | 阿里云 STS（现状）、长期 AK/SK、AWS IRSA/Envoy、K8s SA | **机制差异，需要 Provider** |
| 数据库 | MySQL/PG | 通常不动（patch 可覆盖，保险起见留能力） |

**结论**：endpoint 类全走 patch 数据；**只有凭证发放是机制**（STS AssumeRole vs 读环境 vs 静态 AK/SK）。

## 2. 目标架构

```
┌─ 数据层（无代码，团队/项目自持） ──────────────────┐
│  builtin defaults (jar 内，回退用)                  │
│   < global (~/.localdebug/config/*.json，团队共享)  │
│   < project (.localdebug.json，项目自持)            │
│   < runtime (debugTag 等运行时覆盖)                 │
└──────────────────────────────────────────────────┘
          ↓ 统一 patch/模板引擎（已存在，扩容）
┌─ SPI 层（3 接口，每个 ≤5 个方法） ─────────────────┐
│  ConfigCenterProvider   pull(namespace,dataId,group)→text │
│  CredentialProvider     get(service)→{ak,sk,token,expiry} │
│  RoutingStrategy        （Phase 3 缓建）                   │
└──────────────────────────────────────────────────┘
          ↓
  Action 引擎（init/replace/rollback，逻辑不变）
```

### 2.1 数据层细则
- **patch 数据扩容**：把 H2（dubbo.*/nacos.* 键）、M3（producers 重写）、M8（ktctl 命令模板 + 8080/20880 端口）、M5（占位符表）全部从代码移入数据。patch 分组从 `common/bff/rpc` 泛化为按 **debugProfile** 取用
- **debugProfile 取代 serviceType 二分**：`bff`/`rpc` 只是内置两个 profile；profile = { patches 子集, ktctl 命令模板组, jvm 附加参数 }。用户可加 grpc/其他 profile
- **组织数据出包**（H3/H4）：nacosNamespaceMap、serviceType 项目特判规则、默认 pom 依赖模板 → `~/.localdebug/config/` 用户文件，jar 内置值仅回退
- **校验分级**（H1）：`isValidConfig` 改为「nacos 必填 + 按 profile 实际引用的占位符检查缺失」——patch 数据里声明需要什么，校验引擎照单检查，不再全字段硬闸

### 2.2 SPI 细则（Phase 2，待数据层稳定后抽）
```java
interface ConfigCenterProvider {
    String name();                                   // "nacos" / "apollo" / "consul"
    boolean testConnection(Config c);
    String pull(Config c, String namespace, String dataId, String group) throws Exception;
}
interface CredentialProvider {
    String name();                                   // "static" / "aliyun-sts" / "aws"
    Credential get(Config c, String serviceType);    // ak/sk/token/expiry
    boolean needsRefresh(Credential cred);
}
```
- 内置实现：`NacosConfigCenterProvider`（现 ProjectReplaceAction 逻辑搬入）、`StaticCredentialProvider`（用户 AK/SK）、`AliyunStsCredentialProvider`（现 ALiYunClient 逻辑搬入）
- 注册机制：YAGNI——不做 IntelliJ extension point，第一版用配置文件选实现（`"configCenter": "nacos"`），多生态真需求出现再挂 EP
- **RoutingStrategy 缓建**：非 dubbo 染色场景（Istio header、自研网关协议）出现前不建

### 2.3 配置存储加固（前置必须，P0）
- ConfigManager：原子写（tmp+rename）、解析失败保留原文件、进程内单例（M1）
- PortUtil 分配加锁（M2）

## 3. 分阶段落地

| 阶段 | 内容 | 对应问题 | 完成后能力边界 |
|---|---|---|---|
| **P0** | 配置存储加固（M1/M2）+ 校验分级（H1） | 数据安全 | 任何团队可空配置启动 |
| **P1** | 数据化收尾：H2/H3/H4/M3/M5/M8 → 分层配置 + debugProfile | "只能用这些服务" | 任意 nacos+dubbo+k8s 团队零代码接入 |
| **P2** | 抽 ConfigCenterProvider + CredentialProvider，nacos/STS 变第一个实现 | 换配置中心/凭证体系 | Apollo/Consul/静态凭证可插拔 |
| **P3**（按需） | RoutingStrategy（非 tag 染色）、非 Maven 构建、非 ktctl 代理 | 机制级差异 | 真有第二生态再做 |

## 4. 明确不做（YAGNI）
- IntelliJ extension point 形式的 SPI 注册（配置文件选实现足够）
- 通用"插件市场式" provider 发现
- 数据库/Redis 的自动隔离策略（留给 patch 数据）
- 预建 Apollo/Consul 实现（接口留好，实现等真实需求）
