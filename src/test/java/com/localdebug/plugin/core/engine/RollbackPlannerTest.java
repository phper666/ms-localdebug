package com.localdebug.plugin.core.engine;

import com.localdebug.plugin.core.model.StackDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * RollbackPlanner 单测：mesh-service → recover + clean 各展开一条；未命中模板的动作原样返回
 */
class RollbackPlannerTest {

    private final DescriptorLoader loader = new DescriptorLoader();
    private final RollbackPlanner planner = new RollbackPlanner();

    private StackDescriptor builtin;

    @BeforeEach
    void setUp() {
        builtin = loader.load(null);
    }

    @Test
    void meshService_expandsRecoverAndClean() {
        Map<String, Object> context = new HashMap<>();
        context.put("ns", "ns-a");
        context.put("svc", "svc-a");

        List<String> plan = planner.plan(builtin, context);

        // 模板带尾随空格（保持与旧 createKtCtlCmdFile 字符串逐字节一致）
        assertEquals(List.of(
                "ktctl -d -n ns-a recover svc-a ",
                "ktctl -d -n ns-a clean "
        ), plan);
    }

    @Test
    void actionWithoutTemplate_returnedAsIs() {
        builtin.getProxy().setRollbackActions(List.of("un-intercept"));
        // templates 不含 un-intercept

        List<String> plan = planner.plan(builtin, Map.of());

        assertEquals(List.of("un-intercept"), plan);
    }

    @Test
    void missingContextValue_becomesEmptyString() {
        Map<String, Object> context = new HashMap<>();
        context.put("ns", "ns-a");
        // 无 svc

        List<String> plan = planner.plan(builtin, context);

        assertEquals("ktctl -d -n ns-a recover  ", plan.get(0));
    }
}
