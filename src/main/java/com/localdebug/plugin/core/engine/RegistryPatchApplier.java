package com.localdebug.plugin.core.engine;

import cn.hutool.json.JSONConfig;
import cn.hutool.json.JSONNull;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.localdebug.plugin.core.model.StackDescriptor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * registryPatch 应用器：把「注册地址伪装」键组 + extraApplicationPatch + bootstrapPatch
 * 解析为 配置路径→值 映射（值类型保持：模板整串单占位符且上下文值为非字符串时直插原对象，
 * 保证 dubbo.protocol.port 仍为数字、management 仍为 null——产出文件与旧硬编码逐字节一致）。
 * <p>
 * 输出顺序（与旧 ProjectInitAction putByPath 顺序一致）：
 * ① appNameTemplate → appNameKey 路径（无 appNameKey 时回退 "spring.application.name"）
 * ② &lt;prefix&gt;Key+&lt;prefix&gt;Template 配对（Key 值为路径，Template 解析为值）
 * ③ &lt;prefix&gt;Key+&lt;prefix&gt;Value 配对（Key 值为路径，Value 原值）
 * ④ extraApplicationPatch 条目（JSON 声明顺序）
 * ⑤ bootstrapPatch 条目（统一 "bootstrap:" 前缀——application/boot 两侧同名路径必须区分目标文档）
 * <p>
 * 占位符替换风格参考 MiddlewarePatchUtil：未知占位符 → 空串。
 * 纯 Java，无 IntelliJ 依赖。
 */
public class RegistryPatchApplier {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z]+)}");
    private static final String BOOTSTRAP_PREFIX = "bootstrap:";

    /**
     * 应用 registryPatch
     *
     * @param desc    栈描述符
     * @param context 占位符 → 值映射（值 null/空 → 空串）
     * @return 配置路径 → 解析值（bootstrapPatch 条目键带 "bootstrap:" 前缀）
     */
    public Map<String, Object> apply(StackDescriptor desc, Map<String, Object> context) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        if (desc == null || desc.getRegistryPatch() == null) {
            return resolved;
        }
        // ignoreNullValue=false：保留 extraApplicationPatch 中 "management": null 的删除语义
        JSONObject patch = new JSONObject(desc.getRegistryPatch(), JSONConfig.create().setIgnoreNullValue(false));

        // ① appNameTemplate → appNameKey 路径（无 appNameKey 时回退 spring.application.name）
        if (patch.containsKey("appNameTemplate")) {
            String appNamePath = patch.getStr("appNameKey", "spring.application.name");
            if (appNamePath != null && !appNamePath.trim().isEmpty()) {
                resolved.put(appNamePath, resolveValue(patch.get("appNameTemplate"), context));
            }
        }
        // ②③ Key 配对（Template 优先，其次 Value）
        for (String field : patch.keySet()) {
            if (!field.endsWith("Key") || "appNameKey".equals(field)) {
                continue;
            }
            String prefix = field.substring(0, field.length() - "Key".length());
            String path = patch.getStr(field);
            if (path == null) {
                continue;
            }
            String templateField = prefix + "Template";
            String valueField = prefix + "Value";
            if (patch.containsKey(templateField)) {
                resolved.put(path, resolveValue(patch.get(templateField), context));
            } else if (patch.containsKey(valueField)) {
                resolved.put(path, patch.get(valueField));
            }
        }
        // ④ extraApplicationPatch（dubbo 键组 + 清空动作）
        JSONObject extra = patch.getJSONObject("extraApplicationPatch");
        if (extra != null) {
            for (String key : extra.keySet()) {
                resolved.put(key, resolveValue(extra.get(key), context));
            }
        }
        // ⑤ bootstrapPatch
        JSONObject bootstrap = patch.getJSONObject("bootstrapPatch");
        if (bootstrap != null) {
            for (String key : bootstrap.keySet()) {
                resolved.put(BOOTSTRAP_PREFIX + key, resolveValue(bootstrap.get(key), context));
            }
        }
        return resolved;
    }

    /**
     * 占位符解析：null/JSONNull → null；Boolean/Number 原样；String 整串单占位符且上下文值为
     * 非字符串 → 直插原对象（保类型）；否则字符串替换（未知占位符 → 空串）
     */
    public static Object resolveValue(Object value, Map<String, Object> context) {
        if (value == null || value instanceof JSONNull) {
            return null;
        }
        if (value instanceof String str) {
            Matcher whole = PLACEHOLDER.matcher(str.trim());
            if (whole.matches()) {
                Object contextValue = context.get(whole.group(1));
                if (contextValue != null && !(contextValue instanceof String)) {
                    return contextValue;
                }
            }
            return replacePlaceholders(str, context);
        }
        return value;
    }

    /**
     * 字符串模板占位符替换（未知占位符 → 空串）
     */
    public static String resolveTemplate(String template, Map<String, Object> context) {
        Object resolved = resolveValue(template, context);
        return resolved == null ? "" : String.valueOf(resolved);
    }

    private static String replacePlaceholders(String template, Map<String, Object> context) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            Object value = context.get(matcher.group(1));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
