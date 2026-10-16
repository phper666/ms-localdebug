package com.localdebug.plugin.core.engine;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.localdebug.plugin.core.model.DebugProfile;
import com.localdebug.plugin.core.model.StackDescriptor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 分级校验引擎（TECH-DESIGN §2.4，H1 替代 isValidConfig）。
 * 两类校验分离：
 * <ul>
 *   <li>字段校验（确定性错误）：validation.required 缺失 → missingRequired（fail-fast，带字段名）</li>
 *   <li>前提确认（概率性错误）：precheckEntries → 只做前提清单（audit 文件用），不报「已通过」</li>
 * </ul>
 * 强制不变式：validation.forced=mqIndependentGroup 或 safety.forceIndependentMqGroup=true 恒校验
 * （影子实例抢 dev MQ 消息是安全问题）。
 * <p>
 * ConfigSnapshot = 简单占位符→值映射（Map&lt;String,Object&gt;，值 null/空 = 缺）。
 * profiles / enabledPatchGroups 本批保留签名占位（批次 2 依据 profile 引用 patches 求所需字段集合时启用）。
 * 纯 Java，无 IntelliJ 依赖。
 */
public class ValidationEngine {

    /** mq 独立消费组强制项名称（与内置栈 validation.forced 一致） */
    public static final String FORCED_MQ_INDEPENDENT_GROUP = "mqIndependentGroup";
    /** mqIndependentGroup 的证据键：ConfigSnapshot 中为 true 表示已配置 MQ 消费者列表 */
    public static final String SNAPSHOT_KEY_MQ_CONSUMERS_CONFIGURED = "mqConsumersConfigured";

    /**
     * 执行校验
     *
     * @param desc               栈描述符
     * @param configSnapshot     占位符 → 值映射（值 null/空 = 缺失）
     * @param profiles           启用的 debug profiles（本批保留，批次 2 使用）
     * @param enabledPatchGroups 启用的 patch 分组（本批保留，批次 2 使用）
     */
    public ValidationResult check(StackDescriptor desc, Map<String, Object> configSnapshot,
                                  List<DebugProfile> profiles, List<String> enabledPatchGroups) {
        ValidationResult result = new ValidationResult();
        if (desc == null) {
            return result;
        }

        // required 字段逐一查快照
        List<String> required = desc.getValidation() != null ? desc.getValidation().getRequired() : null;
        if (required != null) {
            for (String field : required) {
                if (isMissing(configSnapshot, field)) {
                    result.getMissingRequired().add(field);
                }
            }
        }

        // 强制不变式：mqIndependentGroup（validation.forced 与 safety.forceIndependentMqGroup 取或，防死字段）
        List<String> forced = desc.getValidation() != null ? desc.getValidation().getForced() : null;
        boolean forcedByValidation = forced != null && forced.contains(FORCED_MQ_INDEPENDENT_GROUP);
        boolean forcedBySafety = desc.getSafety() != null
                && Boolean.TRUE.equals(desc.getSafety().getForceIndependentMqGroup());
        if ((forcedByValidation || forcedBySafety)
                && !Boolean.TRUE.equals(configSnapshot.get(SNAPSHOT_KEY_MQ_CONSUMERS_CONFIGURED))) {
            result.getForcedViolations().add("MQ 消费者列表未配置（独立消费组为强制项，请确认消费组与 dev 隔离）");
        }

        // precheck：routingEnabler.requiredOrgComponents + validation.precheck（支持点路径引用展开，去重保序）
        LinkedHashSet<String> precheck = new LinkedHashSet<>();
        if (desc.getRoutingEnabler() != null && desc.getRoutingEnabler().getRequiredOrgComponents() != null) {
            precheck.addAll(desc.getRoutingEnabler().getRequiredOrgComponents());
        }
        List<String> precheckRefs = desc.getValidation() != null ? desc.getValidation().getPrecheck() : null;
        if (precheckRefs != null) {
            for (String ref : precheckRefs) {
                List<String> resolved = resolveListRef(ref, desc);
                if (resolved != null) {
                    precheck.addAll(resolved);
                } else {
                    precheck.add(ref);
                }
            }
        }
        result.getPrecheckEntries().addAll(precheck);

        return result;
    }

    private boolean isMissing(Map<String, Object> snapshot, String field) {
        if (snapshot == null) {
            return true;
        }
        Object value = snapshot.get(field);
        if (value == null) {
            return true;
        }
        if (value instanceof String s) {
            return s.isEmpty();
        }
        return false;
    }

    /**
     * 解析点路径引用（如 routingEnabler.requiredOrgComponents）为字符串列表；不可解析返回 null
     */
    private List<String> resolveListRef(String ref, StackDescriptor desc) {
        if (!ref.contains(".")) {
            return null;
        }
        try {
            Object value = JSONUtil.parseObj(desc).getByPath(ref);
            if (value instanceof JSONArray arr) {
                return arr.toList(String.class);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 校验结果：missingRequired（确定性错误）/ precheckEntries（前提清单）/ forcedViolations（强制不变式）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationResult {
        private List<String> missingRequired = new ArrayList<>();
        private List<String> precheckEntries = new ArrayList<>();
        private List<String> forcedViolations = new ArrayList<>();

        /** 可通过 = 无确定性错误且无强制项违规（precheck 只提示，不阻塞） */
        public boolean isValid() {
            return missingRequired.isEmpty() && forcedViolations.isEmpty();
        }
    }
}
