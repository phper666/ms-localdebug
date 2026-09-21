package com.localdebug.plugin.core.engine;

import cn.hutool.core.util.StrUtil;
import com.localdebug.plugin.core.model.StackDescriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 描述符元校验：schema 版本 / 必需字段 / 占位符词汇表 / mode×proxyModel×runtimeEnv 交叉。
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

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z]+)}");

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

        // 占位符词汇表检查（registryPatch / routingEnabler / proxy.templates）
        if (desc.getRegistryPatch() != null) {
            StackDescriptor.RegistryPatch rp = desc.getRegistryPatch();
            checkPlaceholders("registryPatch.discoveryIpTemplate", rp.getDiscoveryIpTemplate(), violations);
            checkPlaceholders("registryPatch.appNameTemplate", rp.getAppNameTemplate(), violations);
            if (rp.getBootstrapPatch() != null) {
                for (Map.Entry<String, String> e : rp.getBootstrapPatch().entrySet()) {
                    checkPlaceholders("registryPatch.bootstrapPatch." + e.getKey(), e.getValue(), violations);
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
     * 提取模板字符串中的占位符并逐一比对词汇表，未知占位符报「字段 + 第几个」
     */
    private void checkPlaceholders(String fieldName, String template, List<String> violations) {
        if (StrUtil.isBlank(template)) {
            return;
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
