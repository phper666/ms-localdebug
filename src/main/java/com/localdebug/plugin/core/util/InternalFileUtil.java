package com.localdebug.plugin.core.util;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONConfig;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.localdebug.plugin.core.cloud.AliyunCredentialStore;
import com.localdebug.plugin.core.config.ALiYunConfig;
import com.localdebug.plugin.core.config.ALiYunTemporaryCredentials;
import com.localdebug.plugin.core.config.LocalDebugConfig;
import com.localdebug.plugin.core.config.ReplaceConfig;
import com.localdebug.plugin.core.config.SpringBootConfig;
import com.localdebug.plugin.core.constant.Constants;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * @author yuzhao.li
 **/
@Slf4j
@UtilityClass
public class InternalFileUtil {
    public static void searchProjectFiles(File projectDirectory, List<String> filePaths, String fileName) {
        if (projectDirectory.isDirectory()) {
            File[] files = projectDirectory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory() &&
                            !Constants.PROJECT_TARGET_DIR.equals(file.getName()) &&
                            !Constants.PROJECT_TEST_DIR.equals(file.getName()) &&
                            !Constants.PROJECT_BUILD_DIR.equals(file.getName()) &&
                            !Constants.PROJECT_CONFIG_BACK.equals(file.getName())) {
                        searchProjectFiles(file, filePaths, fileName);
                    } else if (file.isFile() && file.getName().equals(fileName)) {
                        filePaths.add(FileUtil.normalize(file.getPath()));
                    } else if (file.isFile() && ObjectUtil.isEmpty(fileName)) {
                        filePaths.add(FileUtil.normalize(file.getPath()));
                    }
                }
            }
        }
    }

    public static String getProjectRunApplicationFilePackage(File projectJavaDir, String runApplicationPackage) {
        if (projectJavaDir.isDirectory()) {
            File[] files = projectJavaDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory() &&
                            !Constants.PROJECT_TARGET_DIR.equals(file.getName()) &&
                            !Constants.PROJECT_TEST_DIR.equals(file.getName()) &&
                            !Constants.PROJECT_BUILD_DIR.equals(file.getName()) &&
                            !Constants.PROJECT_CONFIG_BACK.equals(file.getName())) {
                        runApplicationPackage = getProjectRunApplicationFilePackage(file, runApplicationPackage);
                    } else if (file.isFile() && file.getName().contains("Application")) {
                        for (String data : FileUtil.readUtf8Lines(file)) {
                            if (ObjectUtil.isEmpty(runApplicationPackage)) {
                                runApplicationPackage = data.replace("package ", "").replace(";", ".") + file.getName().replace(".java", "");
                                log.info("runApplicationPackage: {}", runApplicationPackage);
                                return runApplicationPackage;
                            }
                        }
                    }
                }
            }
        }

        return runApplicationPackage;
    }

    public static LocalDebugConfig getLocalDebugConfig(String classResourcesConfigPath) {
        JSONObject jsonObject = getPluginResourcesConfig(classResourcesConfigPath);
        JSONConfig config = JSONConfig.create().setIgnoreNullValue(false).setIgnoreError(true);
        LocalDebugConfig localDebugConfig = JSONUtil.toBean(jsonObject.toJSONString(2), config, LocalDebugConfig.class);

        if (jsonObject.containsKey("replaceConfigs") && jsonObject.get("replaceConfigs") instanceof JSONArray) {
            JSONArray array = jsonObject.getJSONArray("replaceConfigs");
            List<ReplaceConfig> replaceConfigs = JSONUtil.toList(array, ReplaceConfig.class);

            // 修复 JSONObject 字段中的 null 值问题
            for (int i = 0; i < array.size(); i++) {
                JSONObject item = array.getJSONObject(i);
                ReplaceConfig replaceConfig = replaceConfigs.get(i);

                if (item != null) {
                    replaceConfig.setProjectBootstrapConfig(copyJsonObjectWithNullValues(item, "projectBootstrapConfig", config));
                    replaceConfig.setProjectApplicationConfig(copyJsonObjectWithNullValues(item, "projectApplicationConfig", config));
                }
            }
            localDebugConfig.setReplaceConfigs(replaceConfigs);
        }

        return localDebugConfig;
    }

    public static JSONObject copyJsonObjectWithNullValues(JSONObject source, String key, JSONConfig config) {
        if (source.containsKey(key)) {
            Object obj = source.get(key);
            if (obj instanceof JSONObject) {
                JSONObject result = new JSONObject(config);
                result.putAll((JSONObject) obj);
                return result;
            }
        }
        return null;
    }

    public static ALiYunConfig getGlobalProjectMqConfig() {
        Path configPath = DirectoryUtil.getToolsGlobalAliYunConfigPath();
        File file = configPath.toFile();
        ALiYunConfig config;
        if (file.exists()) {
            config = JSONUtil.toBean(FileUtil.readUtf8String(file), ALiYunConfig.class);
        } else {
            config = new ALiYunConfig();
        }
        // P2: AK/SK 不再落明文 json，读取时从 IntelliJ PasswordSafe 叠加
        config.setAccessKey(AliyunCredentialStore.getAccessKey() == null ? "" : AliyunCredentialStore.getAccessKey());
        config.setSecretKey(AliyunCredentialStore.getSecretKey() == null ? "" : AliyunCredentialStore.getSecretKey());
        return config;
    }

    /**
     * 保存阿里云配置到全局配置文件
     *
     * @param config 阿里云配置对象
     */
    public static void saveGlobalProjectMqConfig(ALiYunConfig config) {
        try {
            // P2: AK/SK 存 IntelliJ PasswordSafe，明文 json 中剥离
            AliyunCredentialStore.setCredentials(config.getAccessKey(), config.getSecretKey());
            config.setAccessKey("");
            config.setSecretKey("");

            Path configPath = DirectoryUtil.getToolsGlobalAliYunConfigPath();
            File configFile = configPath.toFile();

            // 确保目录存在
            DirectoryUtil.ensureToolsConfigDirExists();

            // 写入配置文件
            FileUtil.writeUtf8String(
                    JSONUtil.toJsonStr(config),
                    configFile
            );
        } catch (Exception e) {
            throw new RuntimeException("保存阿里云配置失败", e);
        }
    }

    /**
     * 获取阿里云临时凭证
     *
     * @return 阿里云临时凭证对象
     */
    public static ALiYunTemporaryCredentials getGlobalALiYunTemporaryCredentials() {
        try {
            Path configPath = DirectoryUtil.getToolsGlobalAliYunConfigPath();
            // 构造临时凭证文件路径（在相同目录下）
            Path tempCredentialsPath = configPath.getParent().resolve(Constants.GLOBAL_ALIYUN_TEMP_CREDENTIALS_CONFIG);
            File tempCredentialsFile = tempCredentialsPath.toFile();
            
            if (tempCredentialsFile.exists()) {
                return JSONUtil.toBean(FileUtil.readUtf8String(tempCredentialsFile), ALiYunTemporaryCredentials.class);
            } else {
                return new ALiYunTemporaryCredentials();
            }
        } catch (Exception e) {
            return new ALiYunTemporaryCredentials();
        }
    }

    /**
     * 保存阿里云临时凭证到文件
     *
     * @param credentials 临时凭证对象
     */
    public static void saveGlobalALiYunTemporaryCredentials(ALiYunTemporaryCredentials credentials) {
        try {
            Path configPath = DirectoryUtil.getToolsGlobalAliYunConfigPath();
            // 构造临时凭证文件路径（在相同目录下）
            Path tempCredentialsPath = configPath.getParent().resolve(Constants.GLOBAL_ALIYUN_TEMP_CREDENTIALS_CONFIG);
            File tempCredentialsFile = tempCredentialsPath.toFile();
            
            // 确保目录存在
            DirectoryUtil.ensureToolsConfigDirExists();
            
            // 写入临时凭证文件
            FileUtil.writeUtf8String(
                    JSONUtil.toJsonStr(credentials),
                    tempCredentialsFile
            );
        } catch (Exception e) {
            throw new RuntimeException("保存阿里云临时凭证失败", e);
        }
    }

    public static JSONObject getPluginResourcesConfig(String resourceConfigPath) {
        try {
            // 使用Hutool的ResourceUtil直接读取resources下的文件
            String content = ResourceUtil.readUtf8Str(resourceConfigPath);
            JSONObject jsonObject = JSONUtil.parseObj(content, JSONConfig.create().setIgnoreNullValue(false));
            log.info("getResourcesConfig: {}", jsonObject);
            return jsonObject;
        } catch (Exception e) {
            log.error("Failed to load resource config: {}", resourceConfigPath, e);
        }
        return new JSONObject();
    }

    public static SpringBootConfig getProjectYaml(String projectYamlPath) {
        return JSONUtil.toBean(getProjectYamlByPath(projectYamlPath).getStr("spring", "{}"), SpringBootConfig.class);
    }

    public static JSONObject getProjectYamlByPath(String projectYamlPath) {
        try {
            Yaml yaml = new Yaml();
            FileInputStream fileInputStream = new FileInputStream(projectYamlPath);
            Object config = yaml.load(fileInputStream);
            fileInputStream.close();
            return JSONUtil.parseObj(config);
        } catch (Exception e) {
            log.error("get yaml by project yaml path error: ", e);
        }
        return JSONUtil.createObj();
    }

    public static JSONObject getYamlByContent(String yamlStr) {
        try {
            Yaml yaml = new Yaml();
            Object config = yaml.load(yamlStr);
            return JSONUtil.parseObj(config);
        } catch (Exception e) {
            log.error("get yaml by content error: ", e);
            throw new RuntimeException(e);
        }
    }

    public static void pluginConfigCopyToProjectConfig(String pluginConfigPath, String projectConfigPath) {
        try {
            File targetFile = new File(projectConfigPath);
            // 直接从当前应用的resources读取配置文件
            String content = ResourceUtil.readUtf8Str(pluginConfigPath);
            FileUtil.writeUtf8String(content, targetFile);
        } catch (Exception e) {
            log.error("plugin config copy to project config error, e: " + e);
        }
    }

    public static String createProjectConfigBackDirName(File searchConfigFile) {
        return SecureUtil.md5(searchConfigFile.getParent());
    }
}
