package com.localdebug.plugin.core.config;

import cn.hutool.core.io.FileUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ConfigManager 最小单测：原子写回读一致 + 损坏文件保护（不覆盖用户配置）
 * 通过系统属性 localdebug.config.dir 隔离到 @TempDir，不触碰真实 ~/.localdebug
 */
class ConfigManagerTest {

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
    void saveThenLoad_contentRoundTrips() {
        ConfigManager cm = ConfigManager.getInstance();
        cm.loadConfig();
        cm.getConfig().getBase().getAppearance().setFontName("TestFont");
        cm.saveConfig();

        Path configPath = tempDir.resolve("config.json");
        assertTrue(Files.exists(configPath), "保存后配置文件应存在");

        cm.loadConfig();
        assertEquals("TestFont", cm.getConfig().getBase().getAppearance().getFontName(),
                "重新加载后配置应保持一致");
    }

    @Test
    void corruptFile_loadKeepsOriginalAndSaveRefused() {
        ConfigManager cm = ConfigManager.getInstance();
        cm.loadConfig();
        cm.getConfig().getBase().getBehavior().setAutoCheckUpdate(false);
        cm.saveConfig();
        Path configPath = tempDir.resolve("config.json");
        assertTrue(Files.exists(configPath), "保存后配置文件应存在");

        // 写坏磁盘 json，模拟文件损坏
        FileUtil.writeUtf8String("{broken json!!", configPath.toFile());

        cm.loadConfig(); // 解析失败 → 默认配置 + invalidOnDisk 标记，原文件保留
        String corruptedContent = FileUtil.readUtf8String(configPath.toFile());
        assertEquals("{broken json!!", corruptedContent, "损坏文件不应被加载覆盖");

        cm.saveConfig(); // 应拒绝覆盖写入
        String afterSave = FileUtil.readUtf8String(configPath.toFile());
        assertEquals(corruptedContent, afterSave, "损坏配置文件不应被默认配置覆盖");
    }
}
