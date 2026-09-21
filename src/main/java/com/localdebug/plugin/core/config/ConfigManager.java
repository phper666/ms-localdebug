package com.localdebug.plugin.core.config;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.localdebug.plugin.core.util.DirectoryUtil;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 配置管理类，用于管理插件的配置信息
 */
@Slf4j
@Getter
public class ConfigManager {
    private static final String CONFIG_FILE_NAME = "config.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private Config config;

    public ConfigManager() {
        loadConfig();
    }

    /**
     * 获取用户配置目录路径
     */
    private Path getUserConfigDirectory() {
        return DirectoryUtil.getToolsConfigDir();
    }

    /**
     * 加载默认配置（classpath 下如无 config.json 则返回空配置对象）
     */
    private Config loadDefaultConfig() {
        try {
            String json = ResourceUtil.readUtf8Str("com/localdebug/plugin/core/config.json");
            if (StrUtil.isNotBlank(json)) {
                return GSON.fromJson(json, Config.class);
            }
        } catch (Exception e) {
            log.debug("未找到内置默认配置，使用空配置对象");
        }
        return new Config();
    }

    /**
     * 加载配置文件
     */
    public void loadConfig() {
        try {
            Path configDir = getUserConfigDirectory();
            Path configPath = configDir.resolve(CONFIG_FILE_NAME);

            if (Files.exists(configPath)) {
                String json = FileUtil.readUtf8String(configPath.toFile());
                config = GSON.fromJson(json, Config.class);
                log.info("成功加载配置文件: {}", configPath);
            } else {
                config = loadDefaultConfig();
                log.info("使用默认配置");
            }
        } catch (Exception e) {
            log.error("加载配置文件失败，使用默认配置", e);
            config = loadDefaultConfig();
        }
    }

    /**
     * 保存配置文件
     */
    public void saveConfig() {
        try {
            Path configDir = getUserConfigDirectory();
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }

            Path configPath = configDir.resolve(CONFIG_FILE_NAME);
            String json = GSON.toJson(config);
            FileUtil.writeUtf8String(json, configPath.toFile());
            log.info("配置已保存到: {}", configPath);
        } catch (Exception e) {
            log.error("保存配置文件失败", e);
        }
    }

    /**
     * 从JSON字符串更新配置
     */
    public boolean updateConfigFromJson(String json) {
        try {
            if (!JSONUtil.isTypeJSON(json)) {
                log.error("JSON格式错误");
                return false;
            }

            this.config = GSON.fromJson(json, Config.class);
            saveConfig();
            return true;
        } catch (JsonSyntaxException e) {
            log.error("JSON格式错误", e);
            return false;
        } catch (Exception e) {
            log.error("更新配置失败", e);
            return false;
        }
    }

    public AppearanceConfig getAppearanceConfig() {
        return config != null ? config.getBase().getAppearance() : null;
    }

    public DebugConfig getDebugConfig() {
        return config != null ? config.getBase().getDebug() : null;
    }

    public KubectlConfig getKubectlConfig() {
        return config != null ? config.getBase().getDebug().getKubectl() : null;
    }

    public KtconnectConfig getKtconnectConfig() {
        return config != null ? config.getBase().getDebug().getKtconnect() : null;
    }

    public UpdateConfig getUpdateConfig() {
        return config != null ? config.getBase().getUpdate() : null;
    }

    public BehaviorConfig getBehaviorConfig() {
        return config != null ? config.getBase().getBehavior() : null;
    }

    public boolean shouldUseGlobalFont() {
        return config != null && config.getBase().getAppearance().isUseGlobalFont();
    }

    public void updateConfig(Config newConfig) {
        this.config = newConfig;
        saveConfig();
    }

    public String getConfigFilePath() {
        return getUserConfigDirectory().resolve(CONFIG_FILE_NAME).toString();
    }

    public String getConfigJson() {
        return GSON.toJson(config);
    }

    /**
     * 主配置类
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class Config {
        private ApplicationInfo application = new ApplicationInfo();
        private BaseConfig base = new BaseConfig();
    }

    /**
     * 应用程序信息类
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class ApplicationInfo {
        private String name = "LocalDebug";
        private AuthorInfo author = new AuthorInfo();
    }

    /**
     * 作者信息类
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class AuthorInfo {
        private String name = "localdebug";
        private String email = "";
    }

    /**
     * 基础配置类，包含所有基础功能配置
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class BaseConfig {
        private AppearanceConfig appearance = new AppearanceConfig();
        private BehaviorConfig behavior = new BehaviorConfig();
        private DebugConfig debug = new DebugConfig();
        private UpdateConfig update = new UpdateConfig();
    }

    /**
     * 外观配置类
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class AppearanceConfig {
        public String fontName = "Microsoft YaHei UI";
        public int fontSize = 12;
        public String theme = "default";
        public String language = "zh_CN";
        public boolean useGlobalFont = true;
        public String toggleWindowShortcut = "alt W";
    }

    /**
     * 行为配置类
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class BehaviorConfig {
        private boolean autoCheckUpdate = true;
        private int maxLogLines = 1000;
        private boolean continueCheckingUpdates = true;
    }

    /**
     * Debug配置类，作为基础配置的一部分
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class DebugConfig {
        private boolean enabled = true;
        private KubectlConfig kubectl = new KubectlConfig();
        private KtconnectConfig ktconnect = new KtconnectConfig();
        private List<String> projectPaths = new ArrayList<>();
    }

    /**
     * 更新配置类
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class UpdateConfig {
        private String updateUrl;
    }

    /**
     * Kubectl配置类
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class KubectlConfig {
        private String downloadUrl;
    }

    /**
     * Ktconnect配置类
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class KtconnectConfig {
        private String downloadUrl;
    }
}
