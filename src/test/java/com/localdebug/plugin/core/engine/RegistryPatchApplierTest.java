package com.localdebug.plugin.core.engine;

import com.localdebug.plugin.core.model.StackDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * RegistryPatchApplier 单测：Key+Template 配对展开 / Key+Value / appName 映射 / bootstrapPatch 前缀
 */
class RegistryPatchApplierTest {

    private final DescriptorLoader loader = new DescriptorLoader();
    private final RegistryPatchApplier applier = new RegistryPatchApplier();

    private StackDescriptor builtin;
    private Map<String, Object> context;

    @BeforeEach
    void setUp() {
        builtin = loader.load(null);
        context = new HashMap<>();
        context.put("svc", "svc-a");
        context.put("ns", "ns-a");
        context.put("tag", "t1");
        context.put("app", "app-x");
        context.put("configCenterAddr", "nacos:8848");
        context.put("mqAddr", "mq:9876");
    }

    @Test
    void discoveryIpTemplate_expandsToFullDns() {
        Map<String, String> resolved = applier.apply(builtin, context);

        assertEquals("svc-a.ns-a.svc.cluster.local",
                resolved.get("spring.cloud.nacos.discovery.ip"));
    }

    @Test
    void discoveryPortValue_mappedToString() {
        Map<String, String> resolved = applier.apply(builtin, context);

        assertEquals("80", resolved.get("spring.cloud.nacos.discovery.port"));
    }

    @Test
    void appNameTemplate_mappedToSpringApplicationName() {
        Map<String, String> resolved = applier.apply(builtin, context);

        assertEquals("app-x-t1", resolved.get("spring.application.name"));
    }

    @Test
    void importCheckKeyValue_mapped() {
        Map<String, String> resolved = applier.apply(builtin, context);

        assertEquals("false", resolved.get("spring.cloud.nacos.config.import-check.enabled"));
    }

    @Test
    void bootstrapPatch_expandedWithBootstrapPrefix() {
        Map<String, String> resolved = applier.apply(builtin, context);

        assertEquals("nacos:8848", resolved.get("bootstrap:spring.cloud.nacos.config.server-addr"));
        assertEquals("application-localdebug", resolved.get("bootstrap:spring.application.name"));
    }

    @Test
    void missingContextValue_becomesEmptyString() {
        context.remove("tag");
        Map<String, String> resolved = applier.apply(builtin, context);

        assertEquals("app-x-", resolved.get("spring.application.name"));
    }
}
