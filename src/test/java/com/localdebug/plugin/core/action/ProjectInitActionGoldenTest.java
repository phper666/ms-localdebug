package com.localdebug.plugin.core.action;

import cn.hutool.json.JSONConfig;
import cn.hutool.json.JSONObject;
import com.localdebug.plugin.core.engine.DescriptorLoader;
import com.localdebug.plugin.core.model.StackDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1 批次3 init 侧快照 golden（oracle 复核指出的等价性证据链薄弱环节补强）：
 * composeApplicationPatch（init 的 application patch 组合：import-check 条件写 → registryPatch 展开
 * → 中间件 patch）的键序与键值与改造前 putByPath + patch 逐项等价。
 * 上下文 = 典型项目（core-bridge-service / dev1 / yuzhao-li），bff 分组。
 */
class ProjectInitActionGoldenTest {

    private final DescriptorLoader loader = new DescriptorLoader();
    private StackDescriptor builtin;

    @BeforeEach
    void setUp() {
        builtin = loader.load(null);
    }

    private Map<String, Object> registryContext() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("app", "core-bridge-service");
        ctx.put("tag", "yuzhao-li");
        ctx.put("svc", "core-bridge-service");
        ctx.put("ns", "dev1");
        ctx.put("configCenterAddr", "nacos.example:8848");
        ctx.put("rpcPort", 20880);
        return ctx;
    }

    private Map<String, Object> patchContext() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("mqAddr", "mq.example:8080");
        ctx.put("roleArn", "acs:ram::123:role/localdebug");
        ctx.put("accessKey", "AK");
        ctx.put("secretKey", "SK");
        ctx.put("schedulerNamespace", "ns1");
        ctx.put("schedulerGroupId", "g1");
        ctx.put("schedulerAppKey", "app1");
        ctx.put("mqConsumers", List.of());
        ctx.put("stsAccessKey", "STSAK");
        ctx.put("stsSecretKey", "STSSK");
        ctx.put("stsSecurityToken", "TOKEN");
        ctx.put("bffWebServerPort", 18080);
        ctx.put("rpcWebServerPort", 18081);
        return ctx;
    }

    @Test
    void composeApplicationPatch_keyOrderAndValues_matchOldHardcoded() {
        JSONObject bootstrapSink = new JSONObject(JSONConfig.create().setIgnoreNullValue(false));

        ProjectInitAction.ApplicationPatchComposition composition = ProjectInitAction.composeApplicationPatch(
                builtin, registryContext(), true, "bff", patchContext(), bootstrapSink);
        JSONObject app = composition.applicationConfig;

        // 顶层键序（putByPath 嵌套语义，旧行为相同）：spring → dubbo → management → server → churchill → alicloud
        List<String> keys = new ArrayList<>(app.keySet());
        assertEquals(List.of("spring", "dubbo", "management", "server", "churchill", "alicloud"), keys,
                "顶层键序应与旧 putByPath 顺序一致: " + keys);

        // 注册伪装键组值（嵌套路径）
        assertEquals("core-bridge-service-yuzhao-li", app.getByPath("spring.application.name"));
        assertEquals("core-bridge-service.dev1.svc.cluster.local", app.getByPath("spring.cloud.nacos.discovery.ip"));
        assertEquals(80, ((Number) app.getByPath("spring.cloud.nacos.discovery.port")).intValue());
        // dubbo 键组（类型保持：Boolean / Integer）
        assertEquals("yuzhao-li", app.getByPath("dubbo.provider.tag"));
        assertEquals(Boolean.TRUE, app.getByPath("dubbo.consumer.lazy"));
        assertEquals("all", app.getByPath("dubbo.registry.register-mode"));
        assertEquals(20880, ((Number) app.getByPath("dubbo.protocol.port")).intValue());
        // 清空动作（hutool 将 null 存为 JSONNull，isNull 是正确判法）
        assertTrue(app.containsKey("management"), "management 键应存在（写 null 移除语义）");
        assertTrue(app.isNull("management"), "management 值应为 null: " + app.get("management"));
        // bff 分组 server.port 原地覆盖为分配端口（键序不变）
        assertEquals(18080, ((Number) app.getByPath("server.port")).intValue());

        // 中间件 patch（churchill）抽样
        assertEquals("mq.example:8080", app.getByPath("churchill.rocketmq.default-config.addr"));
        assertEquals("STSAK", app.getByPath("churchill.rocketmq.default-config.access-key"));
        assertEquals("ns1", app.getByPath("churchill.scheduler.namespace"));
        assertEquals("sts_access", app.getByPath("alicloud.kms.type"));

        // bootstrapPatch 分流（不进 application 文档，进 bootstrapSink，嵌套结构——深合并依赖）
        assertEquals("application-localdebug", bootstrapSink.getByPath("spring.application.name"));
        assertEquals("nacos.example:8848", bootstrapSink.getByPath("spring.cloud.nacos.config.server-addr"));
        assertNull(app.getByPath("spring.cloud.nacos.config.server-addr"), "bootstrap 键不应出现在 application 文档");

        // 占位符全解析（H2 证据）
        assertTrue(composition.emptyPlaceholders.isEmpty(), "占位符应全部解析: " + composition.emptyPlaceholders);
        assertTrue(ProjectInitAction.missingPatchFields(composition.emptyPlaceholders).isEmpty());
    }

    @Test
    void composeApplicationPatch_missingMqAddr_reportedViaMissingPatchFields() {
        Map<String, Object> patchCtx = patchContext();
        patchCtx.put("mqAddr", "");

        ProjectInitAction.ApplicationPatchComposition composition = ProjectInitAction.composeApplicationPatch(
                builtin, registryContext(), false, "bff", patchCtx, new JSONObject());

        // H2：被使用但为空的占位符 → 映射为缺失字段名
        assertEquals(List.of("mqAddr"), ProjectInitAction.missingPatchFields(composition.emptyPlaceholders));
        // importCheckPresent=false → import-check 不写，首顶层键为 spring（appName 嵌套其中）
        List<String> keys = new ArrayList<>(composition.applicationConfig.keySet());
        assertEquals("spring", keys.get(0));
        assertNull(composition.applicationConfig.getByPath("spring.cloud.nacos.config.import-check.enabled"),
                "importCheckPresent=false 时不应写 import-check");
    }
}
