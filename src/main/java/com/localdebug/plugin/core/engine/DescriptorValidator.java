package com.localdebug.plugin.core.engine;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONConfig;
import cn.hutool.json.JSONObject;
import com.localdebug.plugin.core.model.StackDescriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 描述符元校验：schema 版本 / 必需字段 / Key×Template/Value 配对 / 占位符词汇表与语法 /
 * mode×proxyModel×runtimeEnv 交叉。
 * 描述符数据写错一个键 = 新式静默失败，必须比代码 bug 更早暴露（TECH-DESIGN §2.4/§7-3）。
 * 纯 Java，无 IntelliJ 依赖。
 */
public class DescriptorValidator {

    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    /** 当前支持的 routingEnabler.mode */
    private static final Set<String> MODES = Set.of("provider-tag", "consumer-hint", "infra-subset", "none");
    /** 当前支持的 proxyModel */
    private static final Set<String> PROXY_MODELS = Set.of("mesh-service", "intercept", "portforward", "direct");
    /** 当前支持的 runtimeEnv */
    private static final Set<String> RUNTIME_ENVS = Set.of("k8s", "mesh", "non-k8s");
    /** validation.forced 当前仅认 mqIndependentGroup */
    private static final Set<String> KNOWN_FORCED = Set.of("mqIndependentGroup");

    /** 占位符 token：{ 与 } 之间不允许再出现花括号（M8 逐 token 比对，防 {foo{bar}} 类脏数据） */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]*)}");

    /** proxyModel=mesh-service 时 proxy.templates 必需模板键 */
    private static final List<String> MESH_SERVICE_REQUIRED_TEMPLATES =
            List.of("connect", "mesh", "preview", "recover", "clean");

    /**
     * 校验描述符，返回违规清单（空 = 合法）
     */
    public List<String> validate(StackDescriptor desc) {
        List<String> violations = new ArrayList<>();
        if (desc == null) {
            violations.add("描述符为空");
            return violations;
        }

        // schema 版本
        if (desc.getSchemaVersion() == null || desc.getSchemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            violations.add("schemaVersion 不支持: " + desc.getSchemaVersion() + "（当前支持 " + SUPPORTED_SCHEMA_VERSION + "）");
        }

        // top 级必需字段
        if (StrUtil.isBlank(desc.getStack())) {
            violations.add("必需字段缺失: stack");
        }
        if (desc.getRegistryPatch() == null) {
            violations.add("必需字段缺失: registryPatch");
        }
        if (desc.getRoutingEnabler() == null) {
            violations.add("必需字段缺失: routingEnabler");
        }
        if (StrUtil.isBlank(desc.getProxyModel())) {
            violations.add("必需字段缺失: proxyModel");
        }
        if (desc.getProxy() == null) {
            violations.add("必需字段缺失: proxy");
        }

        // registryPatch 检查：Key/Template/Value 配对 + 占位符词汇表
        if (desc.getRegistryPatch() != null) {
            StackDescriptor.RegistryPatch rp = desc.getRegistryPatch();
            checkRegistryPatchPairs(rp, violations);
            checkPlaceholders("registryPatch.discoveryIpTemplate", rp.getDiscoveryIpTemplate(), violations);
            checkPlaceholders("registryPatch.appNameTemplate", rp.getAppNameTemplate(), violations);
            if (rp.getBootstrapPatch() != null) {
                for (Map.Entry<String, String> e : rp.getBootstrapPatch().entrySet()) {
                    checkPlaceholders("registryPatch.bootstrapPatch." + e.getKey(), e.getValue(), violations);
                }
            }
            if (rp.getExtraApplicationPatch() != null) {
                for (Map.Entry<String, Object> e : rp.getExtraApplicationPatch().entrySet()) {
                    if (e.getValue() instanceof String value) {
                        checkPlaceholders("registryPatch.extraApplicationPatch." + e.getKey(), value, violations);
                    }
                }
            }
        }
        if (desc.getRoutingEnabler() != null) {
            StackDescriptor.RoutingEnabler re = desc.getRoutingEnabler();
            checkPlaceholders("routingEnabler.shadowNameTemplate", re.getShadowNameTemplate(), violations);
            checkPlaceholders("routingEnabler.rpcRegistryIpTemplate", re.getRpcRegistryIpTemplate(), violations);
            if (re.getNonProxyHostsSources() != null) {
                for (int i = 0; i < re.getNonProxyHostsSources().size(); i++) {
                    checkPlaceholders("routingEnabler.nonProxyHostsSources[" + i + "]",
                            re.getNonProxyHostsSources().get(i), violations);
                }
            }
        }
        if (desc.getProxy() != null && desc.getProxy().getTemplates() != null) {
            for (Map.Entry<String, String> e : desc.getProxy().getTemplates().entrySet()) {
                checkPlaceholders("proxy.templates." + e.getKey(), e.getValue(), violations);
            }
        }
        // proxyModel=mesh-service 依赖五段模板，缺键运行时产出空命令（H1c）
        if ("mesh-service".equals(desc.getProxyModel())) {
            Map<String, String> templates = desc.getProxy() != null ? desc.getProxy().getTemplates() : null;
            for (String required : MESH_SERVICE_REQUIRED_TEMPLATES) {
                if (templates == null || !templates.containsKey(required)) {
                    violations.add("proxyModel=mesh-service 时 proxy.templates 缺少必需模板: " + required
                            + "（运行时将产出空命令）");
                }
            }
        }

        // routingEnabler.mode
        if (desc.getRoutingEnabler() != null) {
            String mode = desc.getRoutingEnabler().getMode();
            if (!MODES.contains(mode)) {
                violations.add("routingEnabler.mode 非法: " + mode + "（支持 " + MODES + "）");
            } else if ("provider-tag".equals(mode) && StrUtil.isBlank(desc.getRoutingEnabler().getRpcProviderTagKey())) {
                violations.add("routingEnabler.mode=provider-tag 时 rpcProviderTagKey 必填");
            }
        }

        // proxyModel / runtimeEnv / 交叉
        if (!PROXY_MODELS.contains(desc.getProxyModel())) {
            violations.add("proxyModel 非法: " + desc.getProxyModel() + "（支持 " + PROXY_MODELS + "）");
        }
        if (!RUNTIME_ENVS.contains(desc.getRuntimeEnv())) {
            violations.add("runtimeEnv 非法: " + desc.getRuntimeEnv() + "（支持 " + RUNTIME_ENVS + "）");
        } else if ("mesh-service".equals(desc.getProxyModel()) && "non-k8s".equals(desc.getRuntimeEnv())) {
            violations.add("非法组合: proxyModel=mesh-service × runtimeEnv=non-k8s（mesh 隧道依赖 k8s 集群）");
        }

        // validation.forced 未知项
        if (desc.getValidation() != null && desc.getValidation().getForced() != null) {
            for (String forced : desc.getValidation().getForced()) {
                if (!KNOWN_FORCED.contains(forced)) {
                    violations.add("validation.forced 未知项: " + forced + "（当前仅支持 " + KNOWN_FORCED + "）");
                }
            }
        }

        return violations;
    }

    /**
     * registryPatch Key/Template/Value 配对校验（H1a/H1d，空值容忍语义）：
     * <ul>
     *   <li>Template/Value **有值**而其 XxxKey 缺失/为空 → 违规（typo 场景：discoveryIpKeyy 被丢弃后，
     *       模板有值但 discoveryIpKey 为空——正是要抓的静默丢弃）</li>
     *   <li>XxxKey **有值**而配对 Template/Value 都为空/缺失 → 违规（键值不会生效）</li>
     *   <li>Key 与配对值**同为 null** → 允许（如 eureka 场景 discoveryPortKey 显式 null，不使用的键）</li>
     *   <li>appNameTemplate 例外：允许无 appNameKey（applier 回退 spring.application.name）</li>
     * </ul>
     */
    private void checkRegistryPatchPairs(StackDescriptor.RegistryPatch rp, List<String> violations) {
        JSONObject patch = new JSONObject(rp, JSONConfig.create().setIgnoreNullValue(false));
        Set<String> fields = patch.keySet();
        for (String field : fields) {
            String prefix = null;
            if (field.endsWith("Template")) {
                prefix = field.substring(0, field.length() - "Template".length());
            } else if (field.endsWith("Value")) {
                prefix = field.substring(0, field.length() - "Value".length());
            }
            if (prefix != null) {
                if ("appName".equals(prefix)) {
                    continue;
                }
                if (hasUsableValue(patch.get(field))) {
                    String keyPath = patch.getStr(prefix + "Key");
                    if (keyPath == null || keyPath.trim().isEmpty()) {
                        violations.add("registryPatch 配对缺失: " + field + " 有值但 " + prefix
                                + "Key 缺失/为空（该模板/值将被静默丢弃）");
                    }
                }
                continue;
            }
            if (field.endsWith("Key")) {
                String keyPrefix = field.substring(0, field.length() - "Key".length());
                String path = patch.getStr(field);
                if (path == null || path.trim().isEmpty()) {
                    // 未使用的 Key（如部分注册中心不需要 port 键）允许为空；typo 场景由上分支兜住
                    continue;
                }
                String templateField = keyPrefix + "Template";
                String valueField = keyPrefix + "Value";
                boolean hasPair = hasUsableValue(patch.get(templateField)) || hasUsableValue(patch.get(valueField));
                if (!hasPair) {
                    violations.add("registryPatch 配对缺失: " + field + " 有值但缺少 " + templateField
                            + "/" + valueField + "（该键值不会生效）");
                }
            }
        }
    }

    /** 值是否可用：非 null/JSONNull；空/空白字符串视为不可用 */
    private static boolean hasUsableValue(Object value) {
        if (value == null || value instanceof cn.hutool.json.JSONNull) {
            return false;
        }
        if (value instanceof String s) {
            return !s.trim().isEmpty();
        }
        return true;
    }

    /**
     * 提取模板字符串中的占位符并逐一比对词汇表，未知占位符报「字段 + 第几个」；
     * 同时检测坏语法（M8）：${...} 与 {...} 混用、未配对的裸 { 或 }
     */
    private void checkPlaceholders(String fieldName, String template, List<String> violations) {
        if (StrUtil.isBlank(template)) {
            return;
        }
        if (template.contains("${")) {
            violations.add("字段 [" + fieldName + "] 使用了 ${...} 语法（描述符模板应为 {name} 形式）");
        }
        // 去掉所有合法 token 后仍残留花括号 = 未配对的裸 { 或 }
        String stripped = PLACEHOLDER.matcher(template).replaceAll("");
        if (stripped.indexOf('{') >= 0 || stripped.indexOf('}') >= 0) {
            violations.add("字段 [" + fieldName + "] 存在未配对的 { 或 }");
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        int index = 0;
        while (matcher.find()) {
            index++;
            String placeholder = matcher.group(1);
            if (!StackDescriptor.PLACEHOLDER_VOCABULARY.contains(placeholder)) {
                violations.add("字段 [" + fieldName + "] 第 " + index + " 个占位符 {" + placeholder
                        + "} 不在词汇表内（支持 " + StackDescriptor.PLACEHOLDER_VOCABULARY + "）");
            }
        }
    }
}
