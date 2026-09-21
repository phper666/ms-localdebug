package com.localdebug.plugin.core.engine;

import com.localdebug.plugin.core.engine.ValidationEngine.ValidationResult;
import com.localdebug.plugin.core.model.StackDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ValidationEngine 单测：required 缺失 / mqIndependentGroup 强制项 / precheck 清单
 */
class ValidationEngineTest {

    private final DescriptorLoader loader = new DescriptorLoader();
    private final ValidationEngine engine = new ValidationEngine();

    private StackDescriptor builtin;

    @BeforeEach
    void setUp() {
        builtin = loader.load(null);
    }

    @Test
    void requiredMissing_reportedInMissingRequired() {
        Map<String, Object> snapshot = new HashMap<>();

        ValidationResult result = engine.check(builtin, snapshot, List.of(), List.of());

        assertTrue(result.getMissingRequired().contains("configCenterAddr"),
                "缺 configCenterAddr 应进 missingRequired: " + result.getMissingRequired());
        assertFalse(result.isValid());
    }

    @Test
    void requiredPresent_andForcedSatisfied_isValid() {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("configCenterAddr", "nacos:8848");
        snapshot.put(ValidationEngine.SNAPSHOT_KEY_MQ_CONSUMER_GROUP_ISOLATED, true);

        ValidationResult result = engine.check(builtin, snapshot, List.of(), List.of());

        assertTrue(result.getMissingRequired().isEmpty());
        assertTrue(result.getForcedViolations().isEmpty());
        assertTrue(result.isValid());
    }

    @Test
    void mqIndependentGroupEvidenceMissing_forcedViolation() {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("configCenterAddr", "nacos:8848");
        // 无 mqConsumerGroupIsolated 证据

        ValidationResult result = engine.check(builtin, snapshot, List.of(), List.of());

        assertFalse(result.getForcedViolations().isEmpty());
        assertTrue(result.getForcedViolations().get(0).contains(ValidationEngine.FORCED_MQ_INDEPENDENT_GROUP),
                "强制项违规应点名 mqIndependentGroup: " + result.getForcedViolations());
        assertFalse(result.isValid(), "强制项违规时不可通过");
    }

    @Test
    void precheckEntries_containsOrgComponents() {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("configCenterAddr", "nacos:8848");
        snapshot.put(ValidationEngine.SNAPSHOT_KEY_MQ_CONSUMER_GROUP_ISOLATED, true);

        ValidationResult result = engine.check(builtin, snapshot, List.of(), List.of());

        assertFalse(result.getPrecheckEntries().isEmpty());
        assertTrue(result.getPrecheckEntries().stream()
                        .anyMatch(e -> e.contains("网关按 header 'tag' 改写 lb 目标")),
                "precheck 应含 requiredOrgComponents 条目: " + result.getPrecheckEntries());
        // 引用 "routingEnabler.requiredOrgComponents" 应展开为组件清单本身，而非原样字符串
        assertTrue(result.getPrecheckEntries().stream().noneMatch(e -> e.equals("routingEnabler.requiredOrgComponents")),
                "precheck 引用应展开而非原样: " + result.getPrecheckEntries());
    }

    @Test
    void emptyValueTreatedAsMissing() {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("configCenterAddr", "");

        ValidationResult result = engine.check(builtin, snapshot, List.of(), List.of());

        assertEquals(List.of("configCenterAddr"), result.getMissingRequired(), "空串应视为缺失");
    }
}
