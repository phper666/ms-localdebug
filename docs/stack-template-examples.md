# 配置模板示例（StackDescriptor）

> 状态：草案。P1 实施时内置默认栈进 `resources/com/localdebug/plugin/core/stacks/sca-dubbo-ktctl.json`，本文档是其内容来源 + 换组件写法示例。
> 字段语义见 TECH-DESIGN-generic.md §2.2。模板值全部来自现有代码硬编码（机制篇实证），P1 回归 = 这份模板 vs ProjectInitAction/ProjectReplaceAction 硬编码逐键对账。

## 1. 内置默认栈：sca-dubbo-ktctl.json

```jsonc
{
  "stack": "sca-dubbo-ktctl",
  "displayName": "Spring Cloud Alibaba + Dubbo + kt-connect（默认栈）",
  "schemaVersion": 1,

  "configCenter": {
    "type": "nacos",
    "fallbackDataIdSuffix": ".yaml"
  },

  "registryPatch": {
    "discoveryIpKey": "spring.cloud.nacos.discovery.ip",
    "discoveryPortKey": "spring.cloud.nacos.discovery.port",
    "discoveryIpTemplate": "{svc}.{ns}.svc.cluster.local",
    "discoveryPortValue": 80,
    "appNameTemplate": "{app}-{tag}",
    "bootstrapPatch": {
      "spring.cloud.nacos.config.server-addr": "{configCenterAddr}",
      "spring.application.name": "application-localdebug"
    },
    "importCheckKey": "spring.cloud.nacos.config.import-check.enabled",
    "importCheckValue": false
  },

  "routingEnabler": {
    "mode": "provider-tag",
    "httpLaneHeader": "tag",
    "rpcAttachmentKey": "dubbo.tag",
    "rpcProviderTagKey": "dubbo.provider.tag",
    "shadowNameTemplate": "{svc}-{tag}",
    "forcedRpcPort": 20880,
    "rpcRegistryEnvVar": "DUBBO_IP_TO_REGISTRY",
    "rpcRegistryIpTemplate": "{svc}-{tag}.{ns}.svc.cluster.local",
    "nonProxyHostsSources": ["{configCenterAddr}", "{mqAddr}"],
    "requiredOrgComponents": [
      "网关按 header 'tag' 改写 lb 目标到影子服务（LocalRouteForward 类组件）",
      "消费侧已透传 attachment 'dubbo.tag'（如 platform-common 的 DubboTagClusterInterceptor）",
      "dev 集群 DNS 可达（隧道工具建 {svc}-{tag} Service）"
    ]
  },

  "proxyModel": "mesh-service",
  "proxy": {
    "tool": "ktctl",
    "templates": {
      "connect": "ktctl -d -n {ns} connect",
      "mesh":    "ktctl -d -n {ns} mesh {svc} --expose {port}:8080 --versionMark tag:{tag}",
      "preview": "ktctl -d -n {ns} preview {alias} --expose {rpcPort}:{rpcPort}",
      "recover": "ktctl -d -n {ns} recover {svc}",
      "clean":   "ktctl -d -n {ns} clean"
    },
    "stopMechanism": "signal-file",
    "rollbackActions": ["recover", "clean"]
  },

  "bootIsolation": {
    "nacos": { "renameApp": "application-localdebug", "disableImportCheck": true }
  },

  "runtimeEnv": "k8s",

  "credentials": [
    { "provider": "aliyun-sts", "required": false, "ttlSeconds": 28800 }
  ],

  "validation": {
    "required": ["configCenterAddr"],
    "optional": ["mqAddr", "roleArn", "schedulerNamespace", "schedulerGroupId", "schedulerAppKey"],
    "precheck": ["routingEnabler.requiredOrgComponents"],
    "forced": ["mqIndependentGroup"]
  },

  "middlewareGroup": {
    "key": "churchill",
    "mqConsumersKey": "churchill.rocketmq.default-config",
    "producersKey": "churchill.rocketmq.producers"
  },

  "safety": {
    "forceIndependentMqGroup": true
  }
}
```

## 2. 换组件示例：仅覆盖差异（inherits 语法）

新团队 = SC 2025 + **Eureka**注册 + **SC LB hint**染色 + **静态 AK/SK**，其余不变。用户只需要写差异：

```jsonc
{
  "stack": "sc-eureka-hint",
  "inherits": "builtin:sca-dubbo-ktctl-base",   // 继承内置底座，其余字段全部透传

  "registryPatch": {                              // 覆盖：换注册中心键组
    "discoveryIpKey": "eureka.instance.ip-address",
    "discoveryPortKey": null,                     // eureka 无单独 port 键，注册 port 默认跟随
    "discoveryIpTemplate": "{svc}-{tag}.{ns}.svc.cluster.local",
    "appNameTemplate": "{app}-{tag}",
    "bootstrapPatch": {}                          // eureka 场景清空 bootstrap patch
  },

  "routingEnabler": {                             // 覆盖：染色换 SC LB hint 方案
    "mode": "consumer-hint",
    "httpLaneHeader": "X-SC-LB-Hint",
    "rpcAttachmentKey": "X-SC-LB-Hint",
    "rpcProviderTagKey": "spring.cloud.lb.hint.metadata-key",  // 实例 metadataMap 的 hint 键
    "nonProxyHostsSources": ["{configCenterAddr}"],
    "requiredOrgComponents": [
      "入口 filter 注入 X-SC-LB-Hint header",
      "实例侧注册 metadata 带 hint 标签"
    ]
  },

  "proxy": {                                      // 覆盖：代理工具换成 Telepresence = proxyModel 联动
    "proxyModel": "intercept",
    "rollbackActions": []                         // un-intercept 是工具命令，由新引擎分支处理
  },

  "credentials": []                               // 覆盖：静态 AK/SK 直接注入，不需要 Provider
}
```

## 3. 模板库策略（已更新：用户确认做表单向导）

- **内置 1 份**：默认栈（P1 强制产出，回归基准）
- **模板导入 + 表单向导**（P3，用户已确认）：设置页「从模板新建」→ 选内置/导入模板 → 渲染声明式表单 → 生成用户 descriptor。向导是**通用渲染器**：表单字段来自模板自己的 `formFields` 声明，任何模板零 UI 代码适配
- **formFields 声明段**（加进模板 schema）：

```jsonc
// 每份模板声明自己在向导里暴露的字段（值→占位符/全局配置映射）
"formFields": [
  { "key": "configCenterAddr",   "label": "注册/配置中心地址",   "required": true,  "help": "本地可达的 nacos 地址（公网/内网）" },
  { "key": "mqAddr",             "label": "MQ 公网地址",          "required": false, "help": "不用 MQ 可留空" },
  { "key": "credentialProvider", "label": "凭证方式",             "required": false, "secret": false,
    "type": "select", "options": ["aliyun-sts", "static", "none"] },
  { "key": "accessKey",          "label": "AccessKey",            "required": false, "secret": true },
  { "key": "tag",                "label": "调试 tag",             "required": true,  "default": "{username}" }
]
```

- 向导生成结果 = 用户描述符（带值）= 现「手填 JSON」的自动化版；**校验/自检/audit 全走已有引擎管线，向导不 bypass 任何机制**
- 预设库积累方式不变：第二个真实团队接入时按实际栈沉淀模板（此时向导已就绪，沉淀成本更低）

## 4. 模板正确性红线

- builtin 模板值必须与 P1 改造前硬编码**逐键对账**（回归 gate）
- 模板只含 L1 数据（键/模板/值）+ L4 前提声明；**引擎分支语义不进模板**（bootIsolation 是临时例外，P3 收敛 Provider）
- DescriptorValidator：inherits 解析、键引用存在性、proxyModel×runtimeEnv 交叉、schemaVersion 匹配——写错任一立即报错