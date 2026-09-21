package com.localdebug.plugin.core.engine;

import com.localdebug.plugin.core.model.StackDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
        context.put("rpcPort", 20880);
    }

    @Test
    void discoveryIpTemplate_expandsToFullDns() {
        Map<String, Object> resolved = applier.apply(builtin, context);

        assertEquals("svc-a.ns-a.svc.cluster.local",
                resolved.get("spring.cloud.nacos.discovery.ip"));
    }

    @Test
    void discoveryPortValue_mappedToInteger() {
        Map<String, Object> resolved = applier.apply(builtin, context);

        assertEquals(80, resolved.get("spring.cloud.nacos.discovery.port"));
    }

    @Test
    void appNameTemplate_mappedToSpringApplicationName() {
        Map<String, Object> resolved = applier.apply(builtin, context);

        assertEquals("app-x-t1", resolved.get("spring.application.name"));
    }

    @Test
    void importCheckKeyValue_mappedToBoolean() {
        Map<String, Object> resolved = applier.apply(builtin, context);

        assertEquals(Boolean.FALSE, resolved.get("spring.cloud.nacos.config.import-check.enabled"));
    }

    @Test
    void bootstrapPatch_expandedWithBootstrapPrefix() {
        Map<String, Object> resolved = applier.apply(builtin, context);

        assertEquals("nacos:8848", resolved.get("bootstrap:spring.cloud.nacos.config.server-addr"));
        assertEquals("application-localdebug", resolved.get("bootstrap:spring.application.name"));
    }

    @Test
    void missingContextValue_becomesEmptyString() {
        context.remove("tag");
        Map<String, Object> resolved = applier.apply(builtin, context);

        assertEquals("app-x-", resolved.get("spring.application.name"));
    }

    @Test
    void extraApplicationPatch_dubboKeysAndClears() {
        Map<String, Object> resolved = applier.apply(builtin, context);

        assertEquals("t1", resolved.get("dubbo.provider.tag"));
        assertEquals(Boolean.TRUE, resolved.get("dubbo.consumer.lazy"));
        assertEquals("all", resolved.get("dubbo.registry.register-mode"));
        assertEquals(20880, resolved.get("dubbo.protocol.port"));
        assertEquals(null, resolved.get("management"));
        assertEquals("", resolved.get("server.port"));
    }

    /**
     * golden：内置默认栈 × (app=core-bridge-service, tag=yuzhao-li, svc=core-bridge-service, ns=dev1)
     * 输出 == 改造前 ProjectInitAction 硬编码 putByPath 全部键值（含类型与顺序）
     */
    @Test
    void golden_defaultStack_matchesOldHardcodedPutByPath() {
        Map<String, Object> goldenContext = new HashMap<>();
        goldenContext.put("app", "core-bridge-service");
        goldenContext.put("tag", "yuzhao-li");
        goldenContext.put("svc", "core-bridge-service");
        goldenContext.put("ns", "dev1");
        goldenContext.put("configCenterAddr", "nacos:8848");
        goldenContext.put("rpcPort", 20880);

        Map<String, Object> resolved = applier.apply(builtin, goldenContext);

        assertEquals("core-bridge-service-yuzhao-li", resolved.get("spring.application.name"));
        assertEquals("core-bridge-service.dev1.svc.cluster.local", resolved.get("spring.cloud.nacos.discovery.ip"));
        assertEquals(80, resolved.get("spring.cloud.nacos.discovery.port"));
        assertEquals(Boolean.FALSE, resolved.get("spring.cloud.nacos.config.import-check.enabled"));
        assertEquals("yuzhao-li", resolved.get("dubbo.provider.tag"));
        assertEquals(Boolean.TRUE, resolved.get("dubbo.consumer.lazy"));
        assertEquals("all", resolved.get("dubbo.registry.register-mode"));
        assertEquals(20880, resolved.get("dubbo.protocol.port"));
        assertEquals(null, resolved.get("management"));
        assertEquals("", resolved.get("server.port"));
        assertEquals("nacos:8848", resolved.get("bootstrap:spring.cloud.nacos.config.server-addr"));
        assertEquals("application-localdebug", resolved.get("bootstrap:spring.application.name"));
        assertEquals(12, resolved.size(), "键集合应与旧硬编码一致");

        // 顺序 == 旧 putByPath 插入顺序（.localdebug.json 键序逐字节依赖）
        assertEquals(List.of(
                "spring.application.name",
                "spring.cloud.nacos.discovery.ip",
                "spring.cloud.nacos.discovery.port",
                "spring.cloud.nacos.config.import-check.enabled",
                "dubbo.provider.tag",
                "dubbo.consumer.lazy",
                "dubbo.registry.register-mode",
                "dubbo.protocol.port",
                "management",
                "server.port",
                "bootstrap:spring.cloud.nacos.config.server-addr",
                "bootstrap:spring.application.name"
        ), new ArrayList<>(resolved.keySet()));
    }
}
