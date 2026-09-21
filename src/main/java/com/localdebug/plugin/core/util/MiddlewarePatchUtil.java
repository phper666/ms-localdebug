package com.localdebug.plugin.core.util;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONNull;
import com.localdebug.plugin.core.constant.Constants;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通用中间件 yaml patch 应用器。
 * patch 数据存放在 classpath 资源 {@link Constants#CLASS_RESOURCE_MIDDLEWARE_PATCHES}，
 * 结构：{ "common": {key: value模板}, "bff": {...}, "rpc": {...} }，
 * 应用时先执行 common，再执行 serviceType 对应的分组。
 * value 为 null 表示写入 null（yaml 合并时会移除该 key）；
 * 字符串模板中的 ${name} 占位符从 context 中取值替换；若整个 value 是单个占位符且
 * context 中对应值为非字符串对象（如 List），则直接插入该对象。
 */
@Slf4j
public class MiddlewarePatchUtil {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{(\\w+)}");
    private static volatile JSONObject patchesCache;

    private MiddlewarePatchUtil() {
    }

    /**
     * 对目标 JSONObject 应用中间件 patch
     *
     * @param target      要写入的配置对象（application.yaml 对应的 JSONObject）
     * @param serviceType 服务类型（bff / rpc）
     * @param context     占位符取值上下文
     */
    public static void applyPatches(JSONObject target, String serviceType, Map<String, Object> context) {
        JSONObject patches = loadPatches();
        applyGroup(target, patches, "common", context);
        applyGroup(target, patches, serviceType, context);
    }

    private static JSONObject loadPatches() {
        JSONObject cached = patchesCache;
        if (cached == null) {
            synchronized (MiddlewarePatchUtil.class) {
                if (patchesCache == null) {
                    patchesCache = InternalFileUtil.getPluginResourcesConfig(Constants.CLASS_RESOURCE_MIDDLEWARE_PATCHES);
                }
                cached = patchesCache;
            }
        }
        return cached;
    }

    private static void applyGroup(JSONObject target, JSONObject patches, String group, Map<String, Object> context) {
        JSONObject groupPatches = patches.getJSONObject(group);
        if (groupPatches == null) {
            if (!"common".equals(group)) {
                log.warn("middleware-patches.json 中不存在分组: {}", group);
            }
            return;
        }
        for (String key : groupPatches.keySet()) {
            target.putByPath(key, resolveValue(groupPatches.get(key), context));
        }
    }

    private static Object resolveValue(Object template, Map<String, Object> context) {
        if (template == null || template instanceof JSONNull) {
            return null;
        }
        if (template instanceof String str) {
            Matcher whole = PLACEHOLDER.matcher(str.trim());
            if (whole.matches()) {
                Object value = context.get(whole.group(1));
                // 整值占位符且上下文为对象（List/Map 等）时直接插入对象本身
                if (value != null && !(value instanceof String)) {
                    return value;
                }
            }
            Matcher matcher = PLACEHOLDER.matcher(str);
            StringBuilder sb = new StringBuilder();
            while (matcher.find()) {
                Object value = context.get(matcher.group(1));
                matcher.appendReplacement(sb, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
            }
            matcher.appendTail(sb);
            return sb.toString();
        }
        return template;
    }

    /**
     * 供调试/校验使用：patch 资源是否可加载且非空
     */
    public static boolean isPatchesResourceAvailable() {
        JSONObject patches = loadPatches();
        return patches != null && !patches.isEmpty() && StrUtil.isNotBlank(patches.toString());
    }
}
