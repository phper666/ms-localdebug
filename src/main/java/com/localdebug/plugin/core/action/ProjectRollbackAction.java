package com.localdebug.plugin.core.action;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.CharsetUtil;
import cn.hutool.json.JSONUtil;
import com.localdebug.plugin.core.constant.Constants;
import com.localdebug.plugin.core.model.FilePath;
import com.localdebug.plugin.core.util.JsonFileUtil;
import com.localdebug.plugin.core.util.LogOutputUtil;
import com.localdebug.plugin.core.maven.MavenUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/**
 * 回滚配置操作类
 * 处理项目回滚配置相关的功能
 */
public class ProjectRollbackAction implements Action {

    /**
     * 执行回滚配置操作
     *
     * @param projectPath     项目路径
     * @param projectBackPath 项目备份路径
     * @param logger          日志输出回调
     */
    @Override
    public void execute(String projectPath, String projectBackPath, Consumer<String> logger) {
        LogOutputUtil.log(logger, "开始执行回滚配置操作...");

        // 检查项目目录是否存在
        File projectDir = new File(projectPath);
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            LogOutputUtil.error(logger, "项目目录不存在或不是有效目录");
            return;
        }

        File backDirectory = new File(projectBackPath);
        if (!backDirectory.exists()) {
            LogOutputUtil.error(logger, "回滚配置不存在，请先执行初始化配置！");
            return;
        }

        String rollbackJsonFilePath = projectBackPath + File.separator + Constants.ROLLBACK_FILE_PATHS_NAME;
        List<FilePath> rollbackJsonFilePaths = JSONUtil.readJSONArray(new File(rollbackJsonFilePath), CharsetUtil.CHARSET_UTF_8).toList(FilePath.class);
        if (rollbackJsonFilePaths.isEmpty()) {
            LogOutputUtil.error(logger, "回滚配置不存在，请先执行初始化配置！");
            return;
        }

        List<String> searchConfigFilePaths = getProjectConfigPaths(projectPath);
        // 如果扫描出不存在的配置，则移除
        Iterator<String> iterator = searchConfigFilePaths.iterator();
        while (iterator.hasNext()) {
            String searchConfigFilePath = iterator.next();
            for (FilePath rollbackJsonFilePathDO : rollbackJsonFilePaths) {
                // 如果搜索出的配置存在roolbackFilePaths，则直接执行回滚文件配置，并移除paths
                if (rollbackJsonFilePathDO.getOriginalPath().equals(searchConfigFilePath)) {
                    FileUtil.copy(rollbackJsonFilePathDO.getBackPath(), rollbackJsonFilePathDO.getOriginalPath(), true);
                    iterator.remove();

                    if (!isTargetClassesExists(rollbackJsonFilePathDO.getOriginalPath())) {
                        continue;
                    }

                    List<String> targetYamlFilePaths = getYamlFilesInCompiledPath(rollbackJsonFilePathDO.getOriginalPath());
                    if (!targetYamlFilePaths.isEmpty()) {
                        targetYamlFilePaths.forEach(FileUtil::del);
                    }

                    FileUtil.copy(rollbackJsonFilePathDO.getBackPath(), buildTargetClassesPath(rollbackJsonFilePathDO.getOriginalPath()), true);
                }
            }
        }

        // 删除初始化配置时，不存在的配置
        for (String searchConfigFilePath : searchConfigFilePaths) {
            FileUtil.del(searchConfigFilePath);
        }

        // 回滚父pom.xml数据
        String pomPath = projectPath + "/pom.xml";
        if (MavenUtil.hasChangeRecord(pomPath) && JsonFileUtil.checkJsonFileHasData(MavenUtil.getChangeInfoPath(pomPath))) {
            try {
                MavenUtil.rollbackChanges(pomPath);
            } catch (Exception e) {
                LogOutputUtil.error(logger, "回滚配置出错：" + e.getMessage());
                return;
            }
        }

        LogOutputUtil.log(logger, "回滚配置成功！");
    }

    /**
     * 判断target/classes目录是否存在
     */
    private boolean isTargetClassesExists(String originalPath) {
        String targetClassesPath = getCompiledPathFromOriginalPath(originalPath);
        if (!targetClassesPath.isEmpty()) {
            File targetClassesDir = new File(targetClassesPath);
            return targetClassesDir.exists() && targetClassesDir.isDirectory();
        }
        return false;
    }

    /**
     * 根据原始资源路径获取对应的编译目录路径
     * 截取src/main/resources前的路径，然后拼接target/classes目录
     */
    private String getCompiledPathFromOriginalPath(String originalPath) {
        // 查找src/main/resources的位置
        int resourcesIndex = originalPath.indexOf("src/main/resources");

        if (resourcesIndex != -1) {
            // 获取src/main/resources之前的路径部分
            String basePath = originalPath.substring(0, resourcesIndex);

            // 拼接为target/classes路径
            return basePath + "target/classes";
        }

        return "";
    }

    /**
     * 根据原始路径构建target/classes中的路径
     */
    private String buildTargetClassesPath(String originalPath) {
        // 将src/main/resources替换为target/classes
        return originalPath.replace("src/main/resources", "target/classes")
                .replace("src\\main\\resources", "target\\classes");
    }

    /**
     * 根据原始路径获取编译目录中所有存在的YAML文件路径
     */
    private List<String> getYamlFilesInCompiledPath(String originalPath) {
        List<String> yamlFiles = new ArrayList<>();

        // 获取编译目录路径
        String compiledPath = getCompiledPathFromOriginalPath(originalPath);

        if (!compiledPath.isEmpty()) {
            File compiledDir = new File(compiledPath);

            // 检查目录是否存在
            if (compiledDir.exists() && compiledDir.isDirectory()) {
                // 使用正则表达式查找YAML文件
                File[] files = compiledDir.listFiles((dir, name) ->
                        name.matches(".*\\.(yaml|yml)$"));

                if (files != null) {
                    for (File file : files) {
                        yamlFiles.add(file.getAbsolutePath());
                    }
                }
            }
        }

        return yamlFiles;
    }
}
