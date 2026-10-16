package com.localdebug.plugin.core.engine;

import com.localdebug.plugin.core.model.StackDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DescriptorValidator 单测：内置栈合法 / 未知占位符 / mesh×non-k8s / mode=none 允许缺失 provider 键
 */
class DescriptorValidatorTest {

    private final DescriptorLoader loader = new DescriptorLoader();
    private final DescriptorValidator validator = new DescriptorValidator();

    private StackDescriptor builtin;

    @BeforeEach
    void setUp() {
        builtin = loader.load(null);
    }

    @Test
    void builtinStack_isValid() {
        List<String> violations = validator.validate(builtin);
        assertTrue(violations.isEmpty(), "内置栈应合法，违规: " + violations);
    }

    @Test
    void unknownPlaceholder_reportedWithFieldName() {
        builtin.getRegistryPatch().setDiscoveryIpTemplate("{svc}.{foo}.cluster.local");

        List<String> violations = validator.validate(builtin);

        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v ->
                        v.contains("registryPatch.discoveryIpTemplate") && v.contains("{foo}")),
                "违规应含字段名与占位符: " + violations);
    }

    @Test
    void unknownPlaceholder_inProxyTemplate_reported() {
        builtin.getProxy().getTemplates().put("mesh", "ktctl mesh {svc} --expose {bogus}");

        List<String> violations = validator.validate(builtin);

        assertTrue(violations.stream().anyMatch(v ->
                        v.contains("proxy.templates.mesh") && v.contains("{bogus}")),
                "违规应含模板字段名: " + violations);
    }

    @Test
    void meshServiceCrossNonK8s_illegal() {
        builtin.setRuntimeEnv("non-k8s");

        List<String> violations = validator.validate(builtin);

        assertTrue(violations.stream().anyMatch(v -> v.contains("mesh-service") && v.contains("non-k8s")),
                "应报非法组合: " + violations);
    }

    @Test
    void modeNone_missingProviderKey_notReported() {
        builtin.getRoutingEnabler().setMode("none");
        builtin.getRoutingEnabler().setRpcProviderTagKey(null);

        List<String> violations = validator.validate(builtin);

        assertTrue(violations.stream().noneMatch(v -> v.contains("rpcProviderTagKey")),
                "mode=none 时 provider 键缺失不应报: " + violations);
    }

    @Test
    void modeProviderTag_missingProviderKey_reported() {
        builtin.getRoutingEnabler().setRpcProviderTagKey(null);

        List<String> violations = validator.validate(builtin);

        assertTrue(violations.stream().anyMatch(v -> v.contains("rpcProviderTagKey") && v.contains("provider-tag")),
                "mode=provider-tag 缺 rpcProviderTagKey 应报: " + violations);
    }

    @Test
    void unknownForcedItem_reported() {
        builtin.getValidation().setForced(List.of("mqIndependentGroup", "magicItem"));

        List<String> violations = validator.validate(builtin);

        assertTrue(violations.stream().anyMatch(v -> v.contains("validation.forced") && v.contains("magicItem")),
                "unknown forced 应报: " + violations);
    }

    @Test
    void unsupportedSchemaVersion_reported() {
        builtin.setSchemaVersion(2);

        List<String> violations = validator.validate(builtin);

        assertTrue(violations.stream().anyMatch(v -> v.contains("schemaVersion") && v.contains("2")),
                "schemaVersion=2 应报不支持: " + violations);
    }

    @Test
    void templateWithoutKey_reported() {
        // H1：typo 场景模拟——模板有值但 Key 为空（真实场景：discoveryIpKeyy 拼错被丢弃后 Key 为 null）
        builtin.getRegistryPatch().setDiscoveryIpKey(null);

        List<String> violations = validator.validate(builtin);

        assertTrue(violations.stream().anyMatch(v ->
                        v.contains("discoveryIpTemplate") && v.contains("discoveryIpKey")),
                "有值模板缺 Key 应报（该模板会被静默丢弃）: " + violations);
    }

    @Test
    void nullKeyWithoutPairValue_allowed() {
        // eureka 场景：discoveryPortKey 显式 null 且无配对值 → 未使用的键，应允许
        builtin.getRegistryPatch().setDiscoveryPortKey(null);
        builtin.getRegistryPatch().setDiscoveryPortValue(null);

        List<String> violations = validator.validate(builtin);

        assertTrue(violations.stream().noneMatch(v -> v.contains("discoveryPortKey")),
                "未使用的 Key（null 且无配对值）不应报违规: " + violations);
    }

    @Test
    void badPlaceholderForms_reported() {
        // 下划线形态（旧正则 [a-zA-Z] 不可见）
        builtin.getRegistryPatch().setDiscoveryIpTemplate("{svc}.{config_center_addr}.local");
        List<String> v1 = validator.validate(builtin);
        assertTrue(v1.stream().anyMatch(v -> v.contains("config_center_addr")),
                "下划线占位符应报不在词汇表: " + v1);

        // 空 token {}
        builtin.getRegistryPatch().setDiscoveryIpTemplate("{}");
        List<String> v2 = validator.validate(builtin);
        assertTrue(v2.stream().anyMatch(v -> v.contains("不在词汇表")),
                "空 token {} 应报不在词汇表: " + v2);

        // 双层花括号 → 裸括号残留
        builtin.getRegistryPatch().setDiscoveryIpTemplate("{{svc}}");
        List<String> v3 = validator.validate(builtin);
        assertTrue(v3.stream().anyMatch(v -> v.contains("未配对")),
                "{{svc}} 应报未配对的括号: " + v3);

        // ${} 与 {} 语法混用
        builtin.getRegistryPatch().setDiscoveryIpTemplate("${tag}");
        List<String> v4 = validator.validate(builtin);
        assertTrue(v4.stream().anyMatch(v -> v.contains("${...}")),
                "${tag} 混用语法应报: " + v4);
    }
}
