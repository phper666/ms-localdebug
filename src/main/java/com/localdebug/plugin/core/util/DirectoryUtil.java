package com.localdebug.plugin.core.util;

import com.localdebug.plugin.core.constant.Constants;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 目录管理器，用于统一管理插件使用的各种目录
 */
public class DirectoryUtil {

    /**
     * 全局基础目录：~/.localdebug（config/、bin/、signal/ 子目录结构不变）
     */
    private static final String TOOLS_BASE_DIR = ".localdebug";

    /**
     * 获取用户主目录
     */
    public static String getUserHome() {
        return System.getProperty("user.home");
    }

    /**
     * 获取工具基础目录路径
     */
    public static Path getToolsBaseDir() {
        return Paths.get(getUserHome(), TOOLS_BASE_DIR);
    }

    /**
     * 获取工具二进制文件目录路径 (~/.localdebug/bin)
     */
    public static Path getToolsBinDir() {
        return Paths.get(getUserHome(), TOOLS_BASE_DIR, "bin");
    }

    /**
     * 获取配置文件目录路径 (~/.localdebug/config)
     */
    public static Path getToolsConfigDir() {
        return Paths.get(getUserHome(), TOOLS_BASE_DIR, "config");
    }

    /**
     * 获取日志文件目录路径 (~/.localdebug/logs)
     */
    public static Path getToolsLogsDir() {
        return Paths.get(getUserHome(), TOOLS_BASE_DIR, "logs");
    }

    /**
     * 获取信号文件目录路径 (~/.localdebug/signal)
     */
    public static Path getSignalFilesDir() {
        return Paths.get(getUserHome(), TOOLS_BASE_DIR, "signal");
    }

    /**
     * 获取阿里云配置文件路径 (~/.localdebug/config/aliyun_config.json)
     */
    public static Path getToolsGlobalAliYunConfigPath() {
        return getToolsConfigDir().resolve(Constants.GLOBAL_ALIYUN_CONFIG);
    }

    /**
     * 确保指定目录存在，如果不存在则创建
     */
    public static Path ensureDirectoryExists(Path directoryPath) throws IOException {
        if (!Files.exists(directoryPath)) {
            Files.createDirectories(directoryPath);
        }
        return directoryPath;
    }

    public static Path ensureToolsBaseDirExists() throws IOException {
        return ensureDirectoryExists(getToolsBaseDir());
    }

    public static Path ensureToolsBinDirExists() throws IOException {
        return ensureDirectoryExists(getToolsBinDir());
    }

    public static Path ensureToolsConfigDirExists() throws IOException {
        return ensureDirectoryExists(getToolsConfigDir());
    }

    public static Path ensureToolsLogsDirExists() throws IOException {
        return ensureDirectoryExists(getToolsLogsDir());
    }

    public static Path ensureSignalFilesDirExists() throws IOException {
        return ensureDirectoryExists(getSignalFilesDir());
    }

    /**
     * 获取项目备份路径
     */
    public static String getProjectBackPath(String projectPath) {
        return projectPath + "/" + Constants.PROJECT_CONFIG_BACK + "/" + projectPath.hashCode();
    }
}
