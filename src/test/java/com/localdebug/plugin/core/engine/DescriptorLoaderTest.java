package com.localdebug.plugin.core.engine;

import cn.hutool.core.io.FileUtil;
import com.localdebug.plugin.core.model.StackDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DescriptorLoader 单测：内置默认栈 / 用户目录栈 / inherits 合并（子覆盖父、防循环）
 * 系统属性 localdebug.config.dir 隔离到 @TempDir，不触碰真实 ~/.localdebug
 */
class DescriptorLoaderTest {

    private static final String CONFIG_DIR_PROP = "localdebug.config.dir";
    private static final String MINIMAL_STACK =
            "{\"stack\":\"mine\",\"displayName\":\"我的栈\",\"schemaVersion\":1,"
                    + "\"registryPatch\":{\"discoveryIpKey\":\"eureka.instance.ip-address\"},"
                    + "\"routingEnabler\":{\"mode\":\"none\"},"
                    + "\"proxyModel\":\"direct\",\"proxy\":{\"tool\":\"x\",\"rollbackActions\":[]},"
                    + "\"runtimeEnv\":\"non-k8s\"}";

    private final DescriptorLoader loader = new DescriptorLoader();

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        System.setProperty(CONFIG_DIR_PROP, tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(CONFIG_DIR_PROP);
    }

    private Path userStacksDir() {
        return tempDir.resolve("stacks");
    }

    @Test
    void loadNull_loadsBuiltinDefaultStack() {
        StackDescriptor desc = loader.load(null);

        assertNotNull(desc);
        assertEquals("sca-dubbo-ktctl", desc.getStack());
        assertEquals(1, desc.getSchemaVersion());
        assertEquals("spring.cloud.nacos.discovery.ip", desc.getRegistryPatch().getDiscoveryIpKey());
        assertEquals("provider-tag", desc.getRoutingEnabler().getMode());
        assertEquals("mesh-service", desc.getProxyModel());
        assertEquals("k8s", desc.getRuntimeEnv());
        assertEquals("ktctl", desc.getProxy().getTool());
        assertEquals("application-localdebug", desc.getBootIsolation().get("nacos").get("renameApp"));
        assertEquals("mqIndependentGroup", desc.getValidation().getForced().get(0));
    }

    @Test
    void loadUserStack_fromUserStacksDir() {
        FileUtil.mkdir(userStacksDir().toFile());
        FileUtil.writeUtf8String(MINIMAL_STACK, userStacksDir().resolve("mine.json").toFile());

        StackDescriptor desc = loader.load("mine");

        assertEquals("mine", desc.getStack());
        assertEquals("我的栈", desc.getDisplayName());
        assertEquals("eureka.instance.ip-address", desc.getRegistryPatch().getDiscoveryIpKey());
    }

    @Test
    void inherits_userStackOverridesAndInheritsParent() {
        FileUtil.mkdir(userStacksDir().toFile());
        String child = "{\"stack\":\"child\",\"displayName\":\"子栈\",\"inherits\":\"builtin:sca-dubbo-ktctl\","
                + "\"proxyModel\":\"intercept\",\"proxy\":{\"rollbackActions\":[]}}";
        FileUtil.writeUtf8String(child, userStacksDir().resolve("child.json").toFile());

        StackDescriptor desc = loader.load("child");

        assertEquals("child", desc.getStack());
        assertEquals("子栈", desc.getDisplayName());                          // 子层覆盖
        assertEquals("intercept", desc.getProxyModel());                      // 子层覆盖
        assertEquals("spring.cloud.nacos.discovery.ip",                        // 父层透传
                desc.getRegistryPatch().getDiscoveryIpKey());
        assertEquals("ktctl -d -n {ns} mesh {svc} --expose {port}:8080 --versionMark tag:{tag}", // 父层透传
                desc.getProxy().getTemplates().get("mesh"));
        assertEquals(0, desc.getProxy().getRollbackActions().size());         // 子层覆盖
    }

    @Test
    void inherits_cycle_detected() {
        FileUtil.mkdir(userStacksDir().toFile());
        FileUtil.writeUtf8String("{\"stack\":\"a\",\"inherits\":\"b\"}", userStacksDir().resolve("a.json").toFile());
        FileUtil.writeUtf8String("{\"stack\":\"b\",\"inherits\":\"a\"}", userStacksDir().resolve("b.json").toFile());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> loader.load("a"));
        assertTrue(ex.getMessage().contains("循环"), "应报循环继承: " + ex.getMessage());
    }

    @Test
    void missingStack_throws() {
        assertThrows(IllegalArgumentException.class, () -> loader.load("no-such-stack"));
    }

    @Test
    void inherits_deleteSentinel_removesKey() {
        // M2：删除继承键用 {"$delete": true} 哨兵；null 保留写 null 语义
        FileUtil.mkdir(userStacksDir().toFile());
        String child = "{\"stack\":\"child\",\"inherits\":\"builtin:sca-dubbo-ktctl\","
                + "\"registryPatch\":{\"discoveryIpKey\":{\"$delete\":true}}}";
        FileUtil.writeUtf8String(child, userStacksDir().resolve("child.json").toFile());

        StackDescriptor desc = loader.load("child");

        assertNull(desc.getRegistryPatch().getDiscoveryIpKey(), "$delete 应删除继承的键");
        assertEquals("spring.cloud.nacos.discovery.port", desc.getRegistryPatch().getDiscoveryPortKey(),
                "其它键不受影响");
    }

    @Test
    void inherits_nullValue_preserved() {
        // M2：子层 null 原样保留（覆盖父层值为 null，而非删除键）
        FileUtil.mkdir(userStacksDir().toFile());
        String child = "{\"stack\":\"child\",\"inherits\":\"builtin:sca-dubbo-ktctl\","
                + "\"registryPatch\":{\"discoveryPortKey\":null,\"discoveryPortValue\":null}}";
        FileUtil.writeUtf8String(child, userStacksDir().resolve("child.json").toFile());

        StackDescriptor desc = loader.load("child");

        assertNull(desc.getRegistryPatch().getDiscoveryPortKey(), "子层 null 应覆盖父层值");
        assertNull(desc.getRegistryPatch().getDiscoveryPortValue(), "子层 null 应覆盖父层值");
    }

    @Test
    void stackNameWithTraversal_rejected() {
        // L5：路径穿越防护
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> loader.load("../evil"));
        assertTrue(ex.getMessage().contains("非法栈名"), "应报非法栈名: " + ex.getMessage());
    }
}
