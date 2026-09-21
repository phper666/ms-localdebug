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

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
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
    /** 系统属性可覆盖配置根目录（测试隔离用），生产默认 ~/.localdebug/config */
    private static final String CONFIG_DIR_OVERRIDE_PROP = "localdebug.config.dir";

    /**
     * 单例持有者（static holder 延迟初始化）
     */
    private static class Holder {
        private static final ConfigManager INSTANCE = new ConfigManager();
    }

    private Config config;
    /** 磁盘文件解析失败标记：为 true 且目标文件仍不可解析时禁止覆盖保存，防止损坏配置被默认配置清空 */
    private volatile boolean invalidOnDisk = false;

    private ConfigManager() {
        loadConfig();
    }

    /**
     * 获取全局单例
     */
    public static ConfigManager getInstance() {
        return Holder.INSTANCE;
    }

    /**
     * 获取用户配置目录路径（支持系统属性覆盖，测试隔离用）
     */
    private Path getUserConfigDirectory() {
        String override = System.getProperty(CONFIG_DIR_OVERRIDE_PROP);
        if (StrUtil.isNotBlank(override)) {
            return Paths.get(override);
        }
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
        invalidOnDisk = false;
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
            // 解析失败：保留磁盘原文件不落盘，标记损坏状态禁止后续覆盖保存
            invalidOnDisk = true;
            log.warn("配置文件损坏或不可解析，保留原文件并使用默认配置（修复前禁止覆盖保存）: {}",
                    getUserConfigDirectory().resolve(CONFIG_FILE_NAME), e);
            config = loadDefaultConfig();
        }
    }

    /**
     * 保存配置文件（原子写：tmp 文件 + move 覆盖；损坏保护：磁盘文件不可解析时拒绝写入）
     */
    public void saveConfig() {
        try {
            Path configDir = getUserConfigDirectory();
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }

            Path configPath = configDir.resolve(CONFIG_FILE_NAME);
            if (invalidOnDisk && !isFileParseable(configPath)) {
                log.warn("磁盘配置文件损坏且不可解析，拒绝覆盖保存，请手动修复: {}", configPath);
                return;
            }

            String json = GSON.toJson(config);
            Path tmpPath = configDir.resolve(CONFIG_FILE_NAME + ".tmp");
            FileUtil.writeUtf8String(json, tmpPath.toFile());
            try {
                Files.move(tmpPath, configPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                log.debug("原子移动不受支持，降级为普通移动: {}", configPath);
                Files.move(tmpPath, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
            invalidOnDisk = false;
            log.info("配置已保存到: {}", configPath);
        } catch (Exception e) {
            log.error("保存配置文件失败", e);
        }
    }

    /**
     * 检查目标文件当前是否可解析（不存在视为可解析，无保护必要）
     */
    private boolean isFileParseable(Path configPath) {
        try {
            if (!Files.exists(configPath)) {
                return true;
            }
            String json = FileUtil.readUtf8String(configPath.toFile());
            GSON.fromJson(json, Config.class);
            return true;
        } catch (Exception e) {
            return false;
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
