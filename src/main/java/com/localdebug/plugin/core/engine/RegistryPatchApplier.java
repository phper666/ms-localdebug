package com.localdebug.plugin.core.engine;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.localdebug.plugin.core.model.StackDescriptor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * registryPatch 应用器：把「注册地址伪装」键组 + bootstrapPatch 解析为 配置路径→值 映射。
 * <p>
 * 规则（描述符驱动，不写死具体键）：
 * <ul>
 *   <li>&lt;prefix&gt;Key + &lt;prefix&gt;Template：Key 值为配置路径，Template 解析（占位符替换）为值</li>
 *   <li>&lt;prefix&gt;Key + &lt;prefix&gt;Value：Key 值为配置路径，Value 原值转字符串</li>
 *   <li>appNameTemplate（schema 无 appNameKey）：固定输出到路径 "spring.application.name"
 *       （application.yaml 影子命名；bootstrap 侧的 spring.application.name 由 bootstrapPatch 单独表达）</li>
 *   <li>bootstrapPatch 条目统一以 "bootstrap:" 前缀输出——application 侧与 bootstrap 侧同名路径
 *       （如 spring.application.name）必须区分目标文档，防覆盖</li>
 * </ul>
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
    public Map<String, String> apply(StackDescriptor desc, Map<String, Object> context) {
        Map<String, String> resolved = new LinkedHashMap<>();
        if (desc == null || desc.getRegistryPatch() == null) {
            return resolved;
        }
        JSONObject patch = JSONUtil.parseObj(desc.getRegistryPatch());
        for (String field : patch.keySet()) {
            if ("bootstrapPatch".equals(field)) {
                JSONObject bootstrap = patch.getJSONObject(field);
                if (bootstrap != null) {
                    for (String k : bootstrap.keySet()) {
                        resolved.put(BOOTSTRAP_PREFIX + k, resolveTemplate(bootstrap.getStr(k), context));
                    }
                }
                continue;
            }
            if (field.endsWith("Key")) {
                String prefix = field.substring(0, field.length() - "Key".length());
                String path = patch.getStr(field);
                if (path == null) {
                    continue;
                }
                String templateField = prefix + "Template";
                String valueField = prefix + "Value";
                if (patch.containsKey(templateField)) {
                    resolved.put(path, resolveTemplate(patch.getStr(templateField), context));
                } else if (patch.containsKey(valueField)) {
                    Object value = patch.get(valueField);
                    resolved.put(path, value == null ? "" : String.valueOf(value));
                }
            } else if (field.endsWith("Template")) {
                String prefix = field.substring(0, field.length() - "Template".length());
                // 无 Key 配对的模板：appNameTemplate 固定映射到 spring.application.name
                if (!patch.containsKey(prefix + "Key") && "appName".equals(prefix)) {
                    resolved.put("spring.application.name", resolveTemplate(patch.getStr(field), context));
                }
            }
        }
        return resolved;
    }

    private String resolveTemplate(String template, Map<String, Object> context) {
        if (template == null) {
            return "";
        }
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
