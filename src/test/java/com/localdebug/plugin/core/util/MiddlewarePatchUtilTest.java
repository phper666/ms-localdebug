package com.localdebug.plugin.core.util;

import cn.hutool.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MiddlewarePatchUtil 空占位符收集单测（H2）：
 * 「被使用但解析为空」的占位符必须被如实上报（调用方据此按「缺什么报什么」报错）
 */
class MiddlewarePatchUtilTest {

    private Map<String, Object> fullContext() {
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
    void fullContext_noEmptyPlaceholders() {
        JSONObject target = new JSONObject();

        List<String> empty = MiddlewarePatchUtil.applyPatches(target, "bff", fullContext());

        assertTrue(empty.isEmpty(), "完整上下文不应有空占位符: " + empty);
        // putByPath 嵌套写：用路径断言
        assertEquals("mq.example:8080", target.getByPath("churchill.rocketmq.default-config.addr"));
        assertEquals("STSAK", target.getByPath("churchill.rocketmq.default-config.access-key"));
        assertEquals("ns1", target.getByPath("churchill.scheduler.namespace"));
        assertEquals(18080, ((Number) target.getByPath("server.port")).intValue(), "bff 分组 server.port 应写入分配端口");
    }

    @Test
    void missingMqAddr_reported() {
        Map<String, Object> ctx = fullContext();
        ctx.put("mqAddr", "");

        List<String> empty = MiddlewarePatchUtil.applyPatches(new JSONObject(), "bff", ctx);

        assertTrue(empty.contains("mqAddr"), "mqAddr 被使用但为空应上报: " + empty);
    }

    @Test
    void missingStsAndScheduler_reported() {
        Map<String, Object> ctx = fullContext();
        ctx.put("stsAccessKey", null);
        ctx.put("schedulerNamespace", null);

        List<String> empty = MiddlewarePatchUtil.applyPatches(new JSONObject(), "rpc", ctx);

        assertTrue(empty.contains("stsAccessKey"), "stsAccessKey 缺失应上报: " + empty);
        assertTrue(empty.contains("schedulerNamespace"), "schedulerNamespace 缺失应上报: " + empty);
        assertTrue(!empty.contains("roleArn"), "roleArn 已配置不应误报: " + empty);
    }
}
