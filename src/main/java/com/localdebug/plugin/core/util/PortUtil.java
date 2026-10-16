package com.localdebug.plugin.core.util;

import cn.hutool.core.io.FileUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static com.localdebug.plugin.core.constant.Constants.PORT_CONFIG_FILE_NAME;

/**
 * 端口管理工具类
 * 用于管理项目使用的端口，支持按项目分配端口并自动递增
 *
 * @author yuzhao.li
 */
@Slf4j
@UtilityClass
public class PortUtil {
    private static final int DEFAULT_START_PORT = 8080;
    /** 系统属性可覆盖配置根目录（测试隔离用），生产默认 ~/.localdebug/config */
    private static final String CONFIG_DIR_OVERRIDE_PROP = "localdebug.config.dir";
    /** 磁盘 ports.json 解析失败标记：为 true 时拒绝覆盖保存，防止损坏文件被空配置清空（同 ConfigManager 保护） */
    private static volatile boolean portConfigCorrupt = false;

    /**
     * 获取项目端口配置文件路径
     * 
     * @return 端口配置文件路径
     */
    private static Path getPortConfigFilePath() {
        String override = System.getProperty(CONFIG_DIR_OVERRIDE_PROP);
        if (override != null && !override.isEmpty()) {
            return Paths.get(override, PORT_CONFIG_FILE_NAME);
        }
        return Paths.get(DirectoryUtil.getToolsConfigDir().toString(), PORT_CONFIG_FILE_NAME);
    }
    
    /**
     * 获取或创建端口配置文件
     * 
     * @return 端口配置JSONObject
     */
    private static JSONObject getOrCreatePortConfig() {
        Path configPath = getPortConfigFilePath();
        File configFile = new File(configPath.toUri());
        portConfigCorrupt = false;
        
        // 如果配置文件不存在，创建一个新的
        if (!configFile.exists()) {
            // 确保配置目录存在（覆盖目录或真实配置目录）
            try {
                Files.createDirectories(configPath.getParent());
            } catch (Exception e) {
                log.error("Failed to create config directory", e);
            }
            
            // 创建默认配置
            JSONObject defaultConfig = new JSONObject();
            FileUtil.writeUtf8String(defaultConfig.toString(), configFile);
            return defaultConfig;
        }
        
        // 读取现有配置
        try {
            String content = FileUtil.readString(configFile, StandardCharsets.UTF_8);
            if (content.trim().isEmpty()) {
                return new JSONObject();
            }
            return JSONUtil.parseObj(content);
        } catch (Exception e) {
            // 解析失败：使用空配置继续分配，但标记损坏，保存时拒绝覆盖原文件
            portConfigCorrupt = true;
            log.error("Failed to read port config file, using empty config (原文件保留，不覆盖)", e);
            return new JSONObject();
        }
    }
    
    /**
     * 保存端口配置到文件
     * 
     * @param config 端口配置
     */
    private static void savePortConfig(JSONObject config) {
        if (portConfigCorrupt) {
            log.warn("ports.json 损坏且不可解析，拒绝覆盖保存，请手动修复");
            return;
        }
        try {
            Path configPath = getPortConfigFilePath();
            File configFile = new File(configPath.toUri());
            FileUtil.writeUtf8String(JSONUtil.formatJsonStr(config.toString()), configFile);
        } catch (Exception e) {
            log.error("Failed to save port config file", e);
        }
    }
    
    /**
     * 为指定项目获取基础端口（BFF端口）
     * 如果项目已有分配的端口，则返回该端口
     * 如果项目没有分配端口，则分配一个新的端口（基于最大已分配RPC端口+1）
     * 
     * @param projectPath 项目路径
     * @return 分配给项目的BFF端口
     */
    public synchronized static int getBffPortForProject(String projectPath) {
        JSONObject config = getOrCreatePortConfig();
        
        // 如果项目已经有分配的BFF端口，直接返回
        if (config.containsKey(projectPath + "_bff")) {
            return config.getInt(projectPath + "_bff");
        }
        
        // 如果没有BFF端口但有RPC端口，则返回RPC端口-1作为BFF端口
        if (config.containsKey(projectPath + "_rpc")) {
            int rpcPort = config.getInt(projectPath + "_rpc");
            int bffPort = rpcPort - 1;
            config.set(projectPath + "_bff", bffPort);
            savePortConfig(config);
            return bffPort;
        }
        
        // 如果都没有，则查找当前最大的端口号
        // 减2是因为我们要为BFF分配端口，RPC端口是BFF端口+1
        int maxPort = DEFAULT_START_PORT - 2;
        for (String key : config.keySet()) {
            // 只考虑RPC端口或者BFF端口
            if (key.endsWith("_rpc") || key.endsWith("_bff")) {
                Integer port = config.getInt(key);
                if (port != null && port > maxPort) {
                    maxPort = port;
                }
            }
        }
        
        // 分配新端口（最大端口+1作为BFF端口）
        int newBffPort = maxPort + 1;
        int newRpcPort = newBffPort + 1;
        
        config.set(projectPath + "_bff", newBffPort);
        config.set(projectPath + "_rpc", newRpcPort);
        
        // 保存配置
        savePortConfig(config);
        
        return newBffPort;
    }
    
    /**
     * 为指定项目获取RPC端口
     * 
     * @param projectPath 项目路径
     * @return 分配给项目的RPC端口
     */
    public synchronized static int getRpcPortForProject(String projectPath) {
        JSONObject config = getOrCreatePortConfig();
        
        // 如果项目已经有分配的RPC端口，直接返回
        if (config.containsKey(projectPath + "_rpc")) {
            return config.getInt(projectPath + "_rpc");
        }
        
        // 如果没有RPC端口但有BFF端口，则返回BFF端口+1
        if (config.containsKey(projectPath + "_bff")) {
            int bffPort = config.getInt(projectPath + "_bff");
            int rpcPort = bffPort + 1;
            config.set(projectPath + "_rpc", rpcPort);
            savePortConfig(config);
            return rpcPort;
        }
        
        // 如果都没有，则查找当前最大的端口号
        // 减2是因为我们要为BFF分配端口，RPC端口是BFF端口+1
        int maxPort = DEFAULT_START_PORT - 2;
        for (String key : config.keySet()) {
            // 只考虑RPC端口或者BFF端口
            if (key.endsWith("_rpc") || key.endsWith("_bff")) {
                Integer port = config.getInt(key);
                if (port != null && port > maxPort) {
                    maxPort = port;
                }
            }
        }
        
        // 分配新端口（最大端口+1作为BFF端口，+2作为RPC端口）
        int newBffPort = maxPort + 1;
        int newRpcPort = newBffPort + 1;
        
        config.set(projectPath + "_bff", newBffPort);
        config.set(projectPath + "_rpc", newRpcPort);
        
        // 保存配置
        savePortConfig(config);
        
        return newRpcPort;
    }
    
    /**
     * 获取项目端口对（BFF端口和RPC端口）
     * 
     * @param projectPath 项目路径
     * @return 包含BFF端口和RPC端口的数组，索引0为BFF端口，索引1为RPC端口
     */
    public synchronized static int[] getPortsForProject(String projectPath) {
        return new int[] {getBffPortForProject(projectPath), getRpcPortForProject(projectPath)};
    }
    
    /**
     * 更新项目端口（主要用于初始化时设置端口）
     * 
     * @param projectPath 项目路径
     * @param bffPort BFF端口号
     * @param rpcPort RPC端口号
     */
    public synchronized static void updatePortsForProject(String projectPath, int bffPort, int rpcPort) {
        JSONObject config = getOrCreatePortConfig();
        config.set(projectPath + "_bff", bffPort);
        config.set(projectPath + "_rpc", rpcPort);
        savePortConfig(config);
    }
}