# 本地调试机制详解（配置替换原理篇）

> 上游文档：DESIGN-generic.md（通用化方案）。本文回答「为什么这些配置要这样替换」。
> 证据源：idea-debug-plugin core 代码 + platform-common 仓库侦察（file:line 均已核实）。

## 1. 全景：一个请求的两条回家路

本地调试 = 让本地进程成为 dev 集群的「影子实例」。dev 请求携带个人 tag，被路由进集群内锚点，经 ktctl 隧道抵达本地。

```
【HTTP/BFF 流量】
Postman/前端 → dev gateway
  header: tag: yuzhao-li
  → gateway LocalRouteForwardFilter：lb://svc → lb://svc-yuzhao-li
  → K8s Service (svc-yuzhao-li, ktctl mesh 创建, versionMark tag:yuzhao-li)
  → mesh pod → ktctl 隧道 → 本地进程(bffWebServerPort)

【RPC/dubbo 流量】
dev 集群内服务 A 处理请求（header dubbo-tag: yuzhao-li 已进 ThreadLocal）
  → A 调用 B（dubbo）
  → DubboTagClusterInterceptor 写 attachment "dubbo.tag=yuzhao-li"
  → dubbo 原生 TagRouter：只选注册时带 tag=yuzhao-li 的 provider
  → 本地实例注册地址 = {svc}-{tag}.{ns}.svc.cluster.local（DUBBO_IP_TO_REGISTRY）
  → K8s Service (svc-yuzhao-tag, ktctl preview 创建) → 隧道 → 本地进程(20880)
```

两条路独立工作，可同时启用（同时带 tag 和 dubbo-tag）。

## 2. dubbo-tag 机制：platform-common 内的完整链路

> 仓库：Churchill_Backend/platform-common。**注意：docx 指向的 TracingAspect 是红鲱鱼**——它只维护 trace-id/canary 的 MDC，与路由无关。

### 2.1 透传链路（每步 file:line）

| 步骤 | 类.方法 | 位置 |
|---|---|---|
| ① HTTP 进来，读 header | `BusinessHeaderInterceptor.preHandle`（另有 Apex/Platform/Storefront/Pre 四个同族） | `servlet/interceptor/BusinessHeaderInterceptor.java:33,66` |
| ② 存入容器 | `DubboTagContext.setDubboTag` → ThreadLocal + MDC | `servlet/context/DubboTagContext.java:18,21` |
| ③ header 名常量 | `HeaderConstant.DUBBO_TAG = "dubbo-tag"` | `constants/HeaderConstant.java:60` |
| ④ 发 RPC 前写 attachment | `DubboTagClusterInterceptor.before` → `invocation.setAttachment(CommonConstants.TAG_KEY, tag)` | `dubbo/interceptor/DubboTagClusterInterceptor.java:26,30` |
| ⑤ SPI 自动注册 | `META-INF/dubbo/internal/org.apache.dubbo.rpc.cluster.interceptor.ClusterInterceptor` | 随 common jar 打包 |
| ⑥ dubbo 原生 TagRouter 消费 `dubbo.tag` 选 provider | dubbo 框架代码 | 非 common 代码 |

### 2.2 三个关键设计点

1. **provider 侧没有 common 代码**。本地影子实例「带 tag 注册」靠的是纯 dubbo 配置 `dubbo.provider.tag=<tag>`（插件 init 写入 yaml）——这是 dubbo 原生 TagRouter 的 provider 侧约定。**common 只负责消费侧把 header 变成 attachment**。
2. **用 ClusterInterceptor 而非 Filter**：必须跑在 TagRouter 之前（cluster 层 `before()` → route），Filter 时机太晚；且用 attachment 键 `dubbo.tag`（`CommonConstants.TAG_KEY`）而非 `RpcContext.setTag()`——兼容 dubbo 2.7.23（Churchill-Parent 默认）/3.3.0（部分服务）。
3. **零副作用设计**：无 header → ThreadLocal 空 → interceptor 不写 attachment → TagRouter 走普通实例。无开关无配置键，`@Activate(group=CONSUMER, order=-100)` 无条件激活——**依赖即生效**。

### 2.3 「被调试服务必须依赖 platform-common」的准确表述

依赖约束落在**消费侧的每一跳**：
- 链路 A(集群)→B(本地) 中，A 必须有 `DubboTagClusterInterceptor` 才能把 `dubbo-tag` 写进 invocation；A 没依赖 common → attachment 丢失 → TagRouter 无 tag → 请求打到普通实例，**调试静默失效**（不报错，流量走 dev）
- 本地实例若继续调 C，同样需要 common 保持 tag 透传
- **provider 侧不需要**（纯 dubbo 配置）；HTTP 入口服务收 `tag` header 也不需要（网关层处理）

两个易漏细节：
- header interceptor **不自动注册**——各服务要在自己的 WebMvcConfigurer 里 `addInterceptor`（如 notice-service `InterceptorConfig.java:14`）。新服务忘了注册 → BFF 链路的 dubbo-tag 断
- `DubboTagContext` 是普通 ThreadLocal（非 TTL）→ **异步线程里发 dubbo 调用会丢 tag**

### 2.4 与 HTTP 层的分工

- 网关侧 `LocalRouteForwardCommonFilter`（gateway-parent/gateway-common）读 `tag` header → 把 `lb://svc` 改写成 `lb://svc-{tag}` → 走 mesh
- 网关侧不需要 common 的 dubbo 机制；`dubbo-tag` 在 gateway 只是被透传下去，由下游服务的 interceptor 接手

## 3. 配置替换逐条详解（插件侧）

### 3.1 init 阶段：推导 + 写键（ProjectInitAction）

| 动作 | 位置 | 原理 |
|---|---|---|
| 扫描 k8s services：`kubectl get services --all-namespaces -l control-by!=kt -o=json` | :476 | `control-by!=kt` 排除 ktctl 自己建的 Service（mesh/preview 的影子 Service），避免下次推导匹配到影子 |
| 过滤 `dev1` namespace | :486 | dev1 是本地调试专用集群段，其内 Service 都不是「真实服务」，不参与候选 |
| namespaceId→k8s ns 映射 | :343 + nacosNamespaceMap.json | 项目 yaml 里 nacos 的 namespace 是 UUID，K8s namespace 是名字，需要桥接表 |
| k8sServiceName 推导 | :399-436 | appName 与 K8s Service 名不一定一致；先按 `-` 分词做 contains 粗筛（排除 provider/management/service 这类泛词），再编辑距离取最小；多解/无解时写提示让用户手选 |
| serviceType 推导（BFF/RPC） | :354-393 | 决定走 mesh（HTTP 入口）还是 preview（纯 RPC）：按 k8s namespace 语义（business/storefront=BFF，engagement/internal 等默认 RPC）+ 8 个项目名特例 |
| nacos group/dataId/namespace 取值 | :334-342 | 从项目 yaml `spring.cloud.nacos.config.*` 读，dataId 缺省 `{appName}.yaml`；replace 时 dataId 再试 `.yaml` 后缀两种 |
| `rNacosGroup="localdebug"` | :153 | 预留字段（当前消费链路未见使用，审查定级 L5 死字段） |

### 3.2 init 写入的 yaml 键（身份+寻址）

| 键 | 值 | 原理（一句话） |
|---|---|---|
| `spring.cloud.nacos.config.server-addr` | 全局配置的可达 nacos | 原 yaml 写的是集群内网地址，本地连不上 |
| `spring.application.name`（bootstrap） | `application-localdebug` | 障眼法：让 Spring 启动期按应用名拉主配置时**拉不到东西**，真正的 dev 配置改由插件 replace 时拉下来落成本地文件 |
| `spring.cloud.nacos.config.import-check.enabled=false` | | Spring Cloud 2020+ 校验，配合上面障眼法防启动失败 |
| `spring.application.name`（application） | `{appName}-{tag}` | 影子实例命名：不与 dev 正式实例混淆，与 preview alias 命名对齐 |
| `spring.cloud.nacos.discovery.ip` | `{svc}.{ns}.svc.cluster.local` | 注册地址伪装：本机 IP 对集群不可达，谎报为集群内 Service DNS；HTTP 流量锚定原 Service（mesh 分流） |
| `spring.cloud.nacos.discovery.port` | 80 | ClusterIP ServicePort |
| `dubbo.provider.tag` | `{tag}` | dubbo 原生 TagRouter 的 provider 侧约定（§2.2-1） |
| `dubbo.registry.register-mode=all` | | 接口级+应用级双注册，兼容两代消费方 |
| `dubbo.consumer.lazy=true` | | 本地也是消费方，避免启动期对全量 provider 建连阻塞 |
| `dubbo.protocol.port=20880` | | 与 preview `--expose 20880:20880` 对齐（原代码有 getDubboPort 想读项目实际端口，现为死代码） |
| `management=null` / `server.port=""` | | 删 dev 的 actuator 绑定；端口交给 PortUtil 按 profile 写入（bff/rpc 各一，`ports.json` 幂等分配） |

### 3.3 replace 阶段：配置快照 + 凭证换装（ProjectReplaceAction）

| 动作 | 位置 | 原理 |
|---|---|---|
| pom 依赖替换（幂等，看 changeInfo） | :83-110 | rocketmqModule/hedwig 换 dev-debug 版 + ons-client 2.0.6（阿里建议，VDI 网络下 1.8.8x 失败率极高） |
| bootstrap 深合并 | :118-122 | init 准备的 bootstrap patch 落盘 |
| nacos 拉配置（dataId 两连试） | :162-176 | dev 的 application 配置在 nacos 上；拉下来做**本地快照**（replace 时点），本地启动不依赖远程配置 |
| `${mainServiceRamRoleName}` 占位符替换 | :173 | dev 配置里的组织占位符 → 个人 roleArn |
| producers 重写 | :130-147 | 生产者的 ramRole 置空 + addr 换个人 MQ addr：集群内身份（RamRole）本地拿不到 |
| middleware-patches.json 应用 | :148 | 所有 ram-role→null + ak/sk/token 注入：**「集群内自动鉴权 → 本地显式凭证」**的统一表达；consumers 整对象替换 = 独立消费组（防影子实例抢 dev 的 MQ 消息，这是最关键的安全点） |
| 生成 `.localdebug.ktctl.cmds` | :178-232 | mesh（BFF，`--expose {port}:8080 --versionMark tag:`）/ preview（RPC，alias={svc}-{tag}，20880）/ recover / clean |
| 生成 `.localdebug.jvmrc.json` | :234-252 | `-Dhttp.nonProxyHosts=nacos|mq(含 _/- 和大小写变体)`：VDI 代理网络防劫持；`-DDUBBO_IP_TO_REGISTRY={svc}-{tag}.{ns}.svc.cluster.local`：dubbo 注册地址伪装（§1 RPC 路） |

### 3.4 共同模式

每条替换都在消除一个「集群内假设」：

| 集群内假设 | 本地现实 | 替换手段 |
|---|---|---|
| 网络全通，用内网地址 | 单向可达，需隧道 | 注册伪装 + ktctl mesh/preview |
| 身份自动（RamRole/节点角色） | 个人显式凭证 | ram-role→null + STS ak/sk/token |
| 配置在注册中心随手可拉 | 配置中心可达但受代理干扰 | 两段式 + 本地快照 + nonProxyHosts |
| 端口由 K8s 分配 | 本地自管 | PortUtil + server.port patch |
| 流量按服务名均摊 | 只接带我 tag 的请求 | mesh versionMark + dubbo tag + 独立消费组 |

## 4. 参考实现：notice-service 接入点清单（实证）

> notice-service-parent 曾接入本套调试工具（证据：`.gitignore:41-43` 保留 `### ms-localdebug ###` 规则、`bootstrap-dev.yaml:1-17` 注释掉的 localhost nacos 配置）。它就是「一个服务如何接入」的活范例。

| 接入点 | 位置 | 性质 |
|---|---|---|
| 注册 `BusinessHeaderInterceptor`（读 dubbo-tag header） | `InterceptorConfig.java:19-24`（`/**` 全路径，排除 api-docs） | **服务侧唯一代码改动（5 行）**，无 @Profile 开关，全环境生效 |
| 依赖 platform-common（消费侧 SPI 自动生效） | `notice-service/pom.xml:79`、`notice-service-core/pom.xml:29`，版本 `0.1.198`（parent pom:28） | 依赖即获得 DubboTagClusterInterceptor |
| dubbo 版本 | 继承 Churchill-Parent 2.7.23，无覆盖 | attachment 方式兼容 2.x/3.x |
| provider tag / 注册中心 / 端口配置 | **全在 nacos 远程配置，仓库内零 dubbo 配置** | 插件 replace 阶段拉取 + 写入的对象正是这些 |

## 5. 对通用化的约束更新（衔接 DESIGN-generic.md）

1. **新增一类 Provider 事实**：dubbo-tag 的消费侧透传依赖组织公共库（platform-common）。通用版插件不可能自带这个库——正确抽象是文档化「**路由启用器（routing enabler）**」概念：dubbo 栈=TagRouter+公共库透传；spring cloud 栈=loadbalancer hint/Feign interceptor；istio=header。插件侧只负责写 provider 侧注册键（都是框架原生配置）
2. **provider 侧键全部是框架原生约定**（dubbo.provider.tag、eureka.hostname、nacos.discovery.ip）→ 支持 DESIGN 的判断：RoutingStrategy 可以只做数据，真 SPI 缓建
3. **serviceType/serviceName/namespace 推导是组织启发式**（H3）：推导算法本身通用（编辑距离+粗筛），但 namespace 子串规则和 8 个项目特例必须出包进用户配置
4. **MQ 独立消费组是不可妥协的安全项**：任何通用化方案中，「影子实例不得加入共享消费组」必须作为强制校验，不能只是默认值
