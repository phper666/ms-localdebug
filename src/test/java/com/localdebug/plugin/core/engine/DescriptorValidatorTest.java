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
}
