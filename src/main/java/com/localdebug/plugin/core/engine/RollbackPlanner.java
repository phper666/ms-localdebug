package com.localdebug.plugin.core.engine;

import com.localdebug.plugin.core.model.StackDescriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 回滚规划器：按 proxy.rollbackActions 顺序生成回滚命令。
 * 每条动作名若命中 proxy.templates 同名键 → 用该模板展开（占位符按 context 替换）；
 * 未命中模板（动作本身即完整命令，如 intercept 类模型的 un-intercept）→ 原样返回。
 * mesh-service 模型 = rollbackActions [recover, clean] 各展开一条。
 * 纯 Java，无 IntelliJ 依赖。
 * <p>
 * TODO(P2): 尚未接线到 Action 流程——回滚仍由 ProjectRollbackAction 按备份文件还原；
 * 待 rollback 命令通道（UI 触发 + 进程管理）落地后接入。
 */
public class RollbackPlanner {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z]+)}");

    /**
     * 规划回滚命令
     *
     * @param desc    栈描述符
     * @param context 占位符 → 值映射（值 null/空 → 空串）
     * @return 回滚命令清单（按 rollbackActions 声明顺序）
     */
    public List<String> plan(StackDescriptor desc, Map<String, Object> context) {
        List<String> result = new ArrayList<>();
        if (desc == null || desc.getProxy() == null || desc.getProxy().getRollbackActions() == null) {
            return result;
        }
        Map<String, String> templates = desc.getProxy().getTemplates();
        for (String action : desc.getProxy().getRollbackActions()) {
            String template = templates == null ? null : templates.get(action);
            result.add(template != null ? resolveTemplate(template, context) : action);
        }
        return result;
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
