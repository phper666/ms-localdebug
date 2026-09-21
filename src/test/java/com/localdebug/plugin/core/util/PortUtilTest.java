package com.localdebug.plugin.core.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PortUtil 最小单测：并发端口分配互不相同
 * 通过系统属性 localdebug.config.dir 隔离到 @TempDir，不触碰真实 ~/.localdebug
 */
class PortUtilTest {

    private static final String CONFIG_DIR_PROP = "localdebug.config.dir";

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        System.setProperty(CONFIG_DIR_PROP, tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(CONFIG_DIR_PROP);
    }

    @Test
    void concurrentGetPortsForProject_allocatesDistinctPorts() throws Exception {
        int threadCount = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            List<Future<int[]>> futures = IntStream.range(0, threadCount)
                    .mapToObj(i -> pool.submit(() -> PortUtil.getPortsForProject("/tmp/proj-" + i)))
                    .collect(Collectors.toList());

            List<int[]> results = new ArrayList<>();
            for (Future<int[]> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }

            Set<Integer> allPorts = new HashSet<>();
            for (int[] ports : results) {
                allPorts.add(ports[0]);
                allPorts.add(ports[1]);
            }
            assertEquals(threadCount * 2, allPorts.size(),
                    "并发分配的所有 BFF/RPC 端口必须互不相同");
        } finally {
            pool.shutdownNow();
        }
    }
}
