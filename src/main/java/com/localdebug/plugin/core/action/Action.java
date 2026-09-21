package com.localdebug.plugin.core.action;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONConfig;
import cn.hutool.json.JSONObject;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import com.localdebug.plugin.core.constant.Constants;
import com.localdebug.plugin.core.util.InternalFileUtil;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * 项目调试操作接口
 * 定义所有项目调试操作的统一接口（headless：日志通过 Consumer 输出，UI 由上层适配）
 */
public interface Action {

    /**
     * 执行操作
     *
     * @param projectPath     项目路径
     * @param projectBackPath 项目备份路径
     * @param logger          日志输出回调
     */
    void execute(String projectPath, String projectBackPath, Consumer<String> logger);

    default ConfigService initNacosConfigService(String serverAddr, String namespace) throws NacosException {
        Properties properties = new Properties();
        properties.put("serverAddr", serverAddr);
        properties.put("namespace", namespace);
        return NacosFactory.createConfigService(properties);
    }

    default boolean existsProjectConfigBackFile(String projectBackPath) {
        String projectConfigBackPath = projectBackPath + "/" + Constants.ROLLBACK_FILE_PATHS_NAME;
        try {
            File projectConfigBack = new File(projectConfigBackPath);
            return projectConfigBack.exists();
        } catch (Exception e) {
            return false;
        }
    }

    default String getProjectConfigBackPathByFileName(String projectBackPath, String fileName) {
        String projectConfigBackPath = projectBackPath + "/" + Constants.ROLLBACK_FILE_PATHS_NAME;
        File projectConfigBack = new File(projectConfigBackPath);
        if (projectConfigBack.exists()) {
            try {
                String jsonContent = FileUtil.readUtf8String(projectConfigBack);
                JSONArray jsonArray = new JSONArray(jsonContent);
                for (int i = 0; i < jsonArray.size(); i++) {
                    String backPath = String.valueOf(jsonArray.getJSONObject(i).get("backPath"));
                    // 标准化路径分隔符后再比较，统一转换为正斜杠
                    String normalizedBackPath = backPath.replace("\\", "/");
                    String normalizedFileName = fileName.replace("\\", "/");
                    if (normalizedBackPath.contains(normalizedFileName)) {
                        return backPath;
                    }
                }
            } catch (Exception e) {
                // 处理可能的JSON解析错误或IO异常
                e.printStackTrace();
            }
        }
        return null;
    }

    default void checkProjectGitignore(String projectBasePath) {
        String projectGitignorePath = projectBasePath + "/" + Constants.GITIGNORE_CONFIG_NAME;
        boolean isExists = false;
        List<String> lines = FileUtil.readUtf8Lines(projectGitignorePath);
        for (String line : lines) {
            if (line.contains(".localdebug*")) {
                isExists = true;
                break;
            }
        }
        if (!isExists) {
            FileUtil.appendUtf8String("""

                    
                    
                    ### ms-localdebug ###
                    .localdebug*
                    **/project_config_back/**""", projectGitignorePath);
        }
    }

    default List<String> getProjectConfigPaths(String projectPath) {
        List<String> filePaths = new ArrayList<>();
        File directory = new File(projectPath);
        InternalFileUtil.searchProjectFiles(directory, filePaths, Constants.QUERY_APPLICATION_YAML);
        InternalFileUtil.searchProjectFiles(directory, filePaths, Constants.QUERY_BOOTSTRAP_YAML);
        return filePaths;
    }

    /**
     * merge两个对象，target会覆盖source
     */
    default JSONObject deepMergeJsonObjects(JSONObject source, JSONObject target) {
        // 创建一个新的JSONObject，确保配置一致性
        JSONConfig config = JSONConfig.create();
        config.setIgnoreNullValue(true);
        JSONObject result = new JSONObject(config);

        // 先复制source中的所有键值对
        for (String key : source.keySet()) {
            result.set(key, source.get(key));
        }

        // 处理target中的键值对
        for (String key : target.keySet()) {
            Object targetValue = target.get(key);
            Object sourceValue = result.get(key);

            // 如果target值为null，则移除该key
            if (ObjectUtil.isNull(targetValue)) {
                result.remove(key);
            }
            // 如果两个值都是JSONObject，则递归合并
            else if (targetValue instanceof JSONObject && sourceValue instanceof JSONObject) {
                result.set(key, deepMergeJsonObjects((JSONObject) sourceValue, (JSONObject) targetValue));
            }
            // 如果target值是JSONArray，则特殊处理其中的null元素
            else if (targetValue instanceof JSONArray) {
                JSONArray targetArray = (JSONArray) targetValue;
                JSONArray processedArray = new JSONArray();

                // 处理数组中的每个元素
                for (int i = 0; i < targetArray.size(); i++) {
                    Object item = targetArray.get(i);
                    // 如果是JSONObject，递归处理
                    if (item instanceof JSONObject) {
                        // 对于数组中的JSONObject，创建一个空的JSONObject作为source进行合并
                        JSONObject emptySource = new JSONObject(config);
                        processedArray.add(deepMergeJsonObjects(emptySource, (JSONObject) item));
                    } else if (!ObjectUtil.isNull(item)) {
                        // 只添加非null元素
                        processedArray.add(item);
                    }
                }
                result.set(key, processedArray);
            } else {
                // 否则直接用目标值覆盖源值（除非目标值为null）
                result.set(key, targetValue);
            }
        }

        return result;
    }

    default void createOrUpdateProjectNewYaml(JSONObject jsonObject, String dumpPath) {
        if (jsonObject != null) {
            Yaml yaml = new Yaml();
            Map<String, Object> map = yaml.load(jsonObject.toString());
            String yamlStr = yaml.dumpAsMap(map);
            FileUtil.writeUtf8String(yamlStr, dumpPath);
        }
    }
}
