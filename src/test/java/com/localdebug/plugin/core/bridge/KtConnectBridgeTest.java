package com.localdebug.plugin.core.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KtConnectBridge 命令守卫单测（M5）：
 * 含未填写占位符（&lt;/&gt;）或引号不配对 → 拒绝启动（sh -c 下 &lt; 会被当输入重定向，造成难解报错）
 */
class KtConnectBridgeTest {

    @Test
    void normalCommand_allowed() {
        assertFalse(KtConnectBridge.hasUnfilledPlaceholderOrUnbalancedQuotes("ktctl -d -n dev1 connect"));
        assertFalse(KtConnectBridge.hasUnfilledPlaceholderOrUnbalancedQuotes(
                "ktctl -d -n dev1 mesh core-bridge-service --expose 8081:8080 --versionMark tag:yuzhao-li"));
        assertFalse(KtConnectBridge.hasUnfilledPlaceholderOrUnbalancedQuotes("ktctl mesh 'svc' --expose \"8081:8080\""));
        assertFalse(KtConnectBridge.hasUnfilledPlaceholderOrUnbalancedQuotes(null));
    }

    @Test
    void unfilledPlaceholder_rejected() {
        assertTrue(KtConnectBridge.hasUnfilledPlaceholderOrUnbalancedQuotes(
                "ktctl -d -n <请填写服务命名空间> mesh <请填写服务名> --expose 8081:8080"));
        assertTrue(KtConnectBridge.hasUnfilledPlaceholderOrUnbalancedQuotes(
                "ktctl -d -n dev1 recover svc > out.log"));
    }

    @Test
    void unbalancedQuotes_rejected() {
        assertTrue(KtConnectBridge.hasUnfilledPlaceholderOrUnbalancedQuotes("ktctl mesh 'svc --expose 8081:8080"));
        assertTrue(KtConnectBridge.hasUnfilledPlaceholderOrUnbalancedQuotes("ktctl mesh \"svc --expose 8081:8080"));
    }
}
