package com.localdebug.plugin.core.model;

import java.util.List;

/**
 * debugProfile（取代 BFF/RPC 二分，见 TECH-DESIGN §2.3）
 *
 * @param patches      命中的 patch 标识（如 portOverride:bff）
 * @param proxySteps   代理步骤顺序（如 connect/mesh/recover/clean）
 * @param extraJvm     额外 JVM 参数
 */
public record DebugProfile(List<String> patches, List<String> proxySteps, List<String> extraJvm) {
}
