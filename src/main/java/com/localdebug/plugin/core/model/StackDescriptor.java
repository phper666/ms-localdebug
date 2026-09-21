package com.localdebug.plugin.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 栈描述符（StackDescriptor）：一个 JSON 完整描述「某技术栈的本地调试方言」。
 * 字段以 docs/stack-template-examples.md §1（内置默认栈）为权威，
 * schema 语义见 docs/TECH-DESIGN-generic.md §2.2。
 * 纯 Java + Lombok，hutool JSONUtil.toBean 反序列化。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StackDescriptor {
    /** 唯一 ID */
    private String stack;
    /** 展示名 */
    private String displayName;
    /** 描述符 schema 版本（当前只支持 1） */
    private Integer schemaVersion;
    /** 继承来源：builtin:&lt;名&gt; 追内置库，裸文件名追用户目录 */
    private String inherits;

    /** 注册/配置中心 */
    private ConfigCenter configCenter;

    /** 「注册地址伪装」键组（H2 收编） */
    private RegistryPatch registryPatch;

    /** 组织路由启用器契约 + 染色定义 */
    private RoutingEnabler routingEnabler;

    /** 机制分支开关：mesh-service | intercept | portforward | direct */
    private String proxyModel;

    /** 隧道工具命令模板（M8 收编） */
    private Proxy proxy;

    /** 启动隔离意图（P1 仅记录意图，唯一实现 nacos；P3 收敛进 ConfigCenterProvider） */
    private Map<String, Map<String, Object>> bootIsolation;

    /** k8s | mesh | non-k8s */
    private String runtimeEnv;

    /** 凭证需求声明 */
    private List<CredentialRequirement> credentials;

    /** 校验声明（H1 替代 isValidConfig） */
    private Validation validation;

    /** 中间件键组 */
    private MiddlewareGroup middlewareGroup;

    /** 不可妥协项（如 forceIndependentMqGroup） */
    private Safety safety;

    /** 表单向导字段声明（P3 使用；内置默认栈暂无） */
    private List<FormField> formFields;

    /**
     * 占位符词汇表（模板字符串只允许引用以下占位符）
     */
    public static final List<String> PLACEHOLDER_VOCABULARY = List.of(
            "ns", "svc", "tag", "app", "port", "rpcPort", "alias", "configCenterAddr", "mqAddr"
    );

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConfigCenter {
        private String type;
        private String fallbackDataIdSuffix;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RegistryPatch {
        private String discoveryIpKey;
        private String discoveryPortKey;
        private String discoveryIpTemplate;
        private Integer discoveryPortValue;
        private String appNameTemplate;
        private Map<String, String> bootstrapPatch;
        private String importCheckKey;
        private Boolean importCheckValue;
        /**
         * 额外 application 侧补丁（H2 dubbo 键组 + 清空动作收编）：
         * 值支持 "{tag}" 模板串（占位符替换）/ 静态 true/false/数字 / null（写入 null 移除键，
         * 与 middleware-patches 的 null 语义一致）
         */
        private Map<String, Object> extraApplicationPatch;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoutingEnabler {
        /** provider-tag | consumer-hint | infra-subset | none */
        private String mode;
        private String httpLaneHeader;
        private String rpcAttachmentKey;
        private String rpcProviderTagKey;
        private String shadowNameTemplate;
        private Integer forcedRpcPort;
        private String rpcRegistryEnvVar;
        private String rpcRegistryIpTemplate;
        private List<String> nonProxyHostsSources;
        private List<String> requiredOrgComponents;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Proxy {
        private String tool;
        /** 模板名 → 命令模板（connect/mesh/preview/recover/clean ...） */
        private Map<String, String> templates;
        private String stopMechanism;
        private List<String> rollbackActions;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CredentialRequirement {
        private String provider;
        private Boolean required;
        private Long ttlSeconds;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Validation {
        private List<String> required;
        private List<String> optional;
        private List<String> precheck;
        private List<String> forced;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MiddlewareGroup {
        private String key;
        private String mqConsumersKey;
        private String producersKey;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Safety {
        private Boolean forceIndependentMqGroup;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FormField {
        private String key;
        private String label;
        private Boolean required;
        private String help;
        private Boolean secret;
        private String type;
        private List<String> options;
        /** JSON 字段名是 "default"（Java 保留字，这里用 defaultValue，hutool 序列化时键名为 defaultValue） */
        private String defaultValue;
    }
}
