package com.localdebug.plugin.core.maven;

import cn.hutool.json.JSONUtil;
import com.localdebug.plugin.core.model.Dependency;
import lombok.Data;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author yuzhao.li
 * @date 2025/09/18 14:22
 */
@Slf4j
@UtilityClass
public class MavenUtil {
    private static final String POM_CHANGE_INFO_CONFIG_NAME = ".localdebug.pom.changeInfo.json";

    public static String getPomChangeInfoConfigName() {
        return POM_CHANGE_INFO_CONFIG_NAME;
    }

    /**
     * 在pom.xml中找到需要修改版本号的依赖，并添加新的依赖项
     *
     * @param pomPath       pom.xml文件路径
     * @param groupId       需要更新版本的依赖的groupId
     * @param artifactId    需要更新版本的依赖的artifactId
     * @param newVersion    新版本号
     * @param newDependency 需要添加的新依赖项
     * @throws Exception
     */
    public static void updateDependencyAndAddNew(String pomPath, String groupId,
                                                 String artifactId, String newVersion,
                                                 Dependency newDependency) throws Exception {
        // 读取原始pom.xml内容
        String originalContent = new String(Files.readAllBytes(Paths.get(pomPath)));

        // 创建变更信息对象
        DependencyChangeInfo changeInfo = new DependencyChangeInfo();

        // 使用正则表达式查找并更新依赖项版本
        String updatedContent = updateDependencyVersionInXml(originalContent, groupId, artifactId, newVersion, changeInfo);

        // 如果提供了新依赖项，则添加新依赖
        String finalContent = updatedContent;
        if (newDependency != null) {
            finalContent = addNewDependencyToXml(updatedContent, newDependency, changeInfo);
        }

        // 写回pom文件，保持原有格式
        Files.writeString(Paths.get(pomPath), finalContent);

        // 将变更信息保存到JSON文件
        String changeInfoPath = getChangeInfoPath(pomPath);
        saveChangeInfoToJson(changeInfo, changeInfoPath);
    }

    public static String getChangeInfoPath(String pomPath) {
        // 获取 pom.xml 文件所在的目录
        File pomFile = new File(pomPath);
        String pomDirectory = pomFile.getParent();

        // 返回目录路径加上配置文件名
        return pomDirectory + File.separator + POM_CHANGE_INFO_CONFIG_NAME;
    }

    /**
     * 使用正则表达式在XML中更新依赖版本
     *
     * @param xmlContent XML内容
     * @param groupId    groupId
     * @param artifactId artifactId
     * @param newVersion 新版本
     * @param changeInfo 变更信息对象
     * @return 更新后的XML内容
     */
    private static String updateDependencyVersionInXml(String xmlContent, String groupId, String artifactId,
                                                       String newVersion, DependencyChangeInfo changeInfo) {
        // 构建依赖项的正则表达式模式
        String patternString = "(<dependency[^>]*>\\s*" +
                "<groupId>" + Pattern.quote(groupId) + "</groupId>\\s*" +
                "<artifactId>" + Pattern.quote(artifactId) + "</artifactId>\\s*" +
                "<version>)([^<]*)(</version>)";

        Pattern pattern = Pattern.compile(patternString, Pattern.DOTALL);
        Matcher matcher = pattern.matcher(xmlContent);

        StringBuilder sb = new StringBuilder();
        if (matcher.find()) {
            // 记录旧版本信息
            String oldVersion = matcher.group(2);
            DependencyInfo oldDependencyInfo = new DependencyInfo(groupId, artifactId, oldVersion, newVersion);
            changeInfo.getUpdateDependencies().add(oldDependencyInfo);

            // 替换为新版本，直接使用捕获组
            matcher.appendReplacement(sb, "$1" + Matcher.quoteReplacement(newVersion) + "$3");
            log.info("Updated dependency " + groupId + ":" + artifactId + " version to " + newVersion);
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    /**
     * 在XML中添加新的依赖项
     *
     * @param xmlContent    XML内容
     * @param newDependency 新依赖项
     * @param changeInfo    变更信息对象
     * @return 添加新依赖后的XML内容
     */
    private static String addNewDependencyToXml(String xmlContent, Dependency newDependency,
                                                DependencyChangeInfo changeInfo) {
        // 查找dependencyManagement下的dependencies标签块
        String patternString = "(<dependencyManagement>\\s*<dependencies>)([\\s\\S]*?)(</dependencies>)";
        Pattern pattern = Pattern.compile(patternString, Pattern.DOTALL);
        Matcher matcher = pattern.matcher(xmlContent);

        StringBuffer sb = new StringBuffer();
        if (matcher.find()) {
            // <dependencyManagement><dependencies>
            String beforeDependencies = matcher.group(1);
            // 依赖内容
            String dependenciesContent = matcher.group(2);
            // </dependencies>
            String afterDependencies = matcher.group(3);

            // 创建新依赖的XML表示
            String newDependencyXml = createDependencyXml(newDependency);

            // 记录添加的依赖信息（用于回滚）
            DependencyInfo addedDependencyInfo = new DependencyInfo(
                    newDependency.getGroupId(),
                    newDependency.getArtifactId(),
                    "",
                    newDependency.getVersion()
            );

            // 检查dependencies标签内是否有内容
            if (dependenciesContent.trim().isEmpty()) {
                // dependencies标签内没有内容
                String newDependenciesContent = "\n            " + newDependencyXml + "\n        ";
                String newFullBlock = beforeDependencies + newDependenciesContent + afterDependencies;

                // 记录位置信息
                int absoluteStart = beforeDependencies.length();
                int absoluteEnd = absoluteStart + newDependenciesContent.length();
                addedDependencyInfo.setStartIndex(absoluteStart);
                addedDependencyInfo.setEndIndex(absoluteEnd);

                changeInfo.getAddDependencies().add(addedDependencyInfo);

                // 执行替换
                matcher.appendReplacement(sb, Matcher.quoteReplacement(newFullBlock));
                log.info("Added new dependency " + newDependency.getGroupId() + ":" +
                        newDependency.getArtifactId() + ":" + newDependency.getVersion());
            } else {
                // dependencies标签内已有内容，找到第一个dependency的位置
                int firstDependencyIndex = dependenciesContent.indexOf("<dependency");
                if (firstDependencyIndex != -1) {
                    // 提取第一个依赖前的空白字符，用于保持一致的缩进
                    String indent = extractIndentBeforeFirstDependency(dependenciesContent, firstDependencyIndex);

                    // 在<dependencies>和第一个<dependency>之间插入新依赖
                    String beforeFirstDependency = dependenciesContent.substring(0, firstDependencyIndex);
                    String afterFirstDependency = dependenciesContent.substring(firstDependencyIndex);

                    // 构造新的依赖内容，使用提取的缩进格式
                    String formattedNewDependency = newDependencyXml + "\n" + indent;

                    // 直接将新依赖插入到第一个依赖之前
                    String newDependenciesContent = beforeFirstDependency + formattedNewDependency + afterFirstDependency;
                    String newFullBlock = beforeDependencies + newDependenciesContent + afterDependencies;

                    // 记录新增依赖的位置信息（用于回滚）
                    // 记录的是新增依赖内容在完整XML中的位置
                    int absoluteStart = beforeDependencies.length() + beforeFirstDependency.length();
                    int absoluteEnd = absoluteStart + formattedNewDependency.length();
                    addedDependencyInfo.setStartIndex(absoluteStart);
                    addedDependencyInfo.setEndIndex(absoluteEnd);

                    changeInfo.getAddDependencies().add(addedDependencyInfo);

                    // 执行替换
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(newFullBlock));
                    log.info("Added new dependency " + newDependency.getGroupId() + ":" +
                            newDependency.getArtifactId() + ":" + newDependency.getVersion());
                } else {
                    // 没有找到dependency标签，直接添加到dependencies内容的开头
                    String formattedNewDependency = "\n            " + newDependencyXml + "\n        ";
                    String newDependenciesContent = formattedNewDependency + dependenciesContent;
                    String newFullBlock = beforeDependencies + newDependenciesContent + afterDependencies;

                    // 记录位置信息
                    int absoluteStart = beforeDependencies.length();
                    int absoluteEnd = absoluteStart + formattedNewDependency.length();
                    addedDependencyInfo.setStartIndex(absoluteStart);
                    addedDependencyInfo.setEndIndex(absoluteEnd);

                    changeInfo.getAddDependencies().add(addedDependencyInfo);

                    // 执行替换
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(newFullBlock));
                    log.info("Added new dependency " + newDependency.getGroupId() + ":" +
                            newDependency.getArtifactId() + ":" + newDependency.getVersion());
                }
            }
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    /**
     * 提取第一个依赖前的缩进空白字符
     *
     * @param content              依赖内容
     * @param firstDependencyIndex 第一个依赖的索引
     * @return 缩进空白字符
     */
    private static String extractIndentBeforeFirstDependency(String content, int firstDependencyIndex) {
        // 从第一个依赖位置向前查找，直到找到换行符
        int lineStart = firstDependencyIndex;
        while (lineStart > 0 && content.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }

        // 提取从行首到第一个依赖之间的字符
        String indent = "";
        for (int i = lineStart; i < firstDependencyIndex; i++) {
            char c = content.charAt(i);
            if (c == ' ' || c == '\t') {
                indent += c;
            } else {
                break;
            }
        }

        return indent;
    }

    /**
     * 创建依赖项的XML表示
     *
     * @param dependency 依赖对象
     * @return XML字符串
     */
    private static String createDependencyXml(Dependency dependency) {
        StringBuilder xml = new StringBuilder();
        xml.append("<dependency>");
        xml.append("<groupId>").append(dependency.getGroupId()).append("</groupId>");
        xml.append("<artifactId>").append(dependency.getArtifactId()).append("</artifactId>");
        xml.append("<version>").append(dependency.getVersion()).append("</version>");

        // 添加scope（如果存在）
        if (dependency.getScope() != null && !dependency.getScope().isEmpty()) {
            xml.append("<scope>").append(dependency.getScope()).append("</scope>");
        }

        xml.append("</dependency>");
        return xml.toString();
    }

    /**
     * 回滚pom.xml的修改（保持幂等性）
     *
     * @param pomPath pom.xml文件路径
     * @throws Exception
     */
    public static void rollbackChanges(String pomPath) throws Exception {
        String changeInfoPath = getChangeInfoPath(pomPath);

        // 检查变更信息文件是否存在
        if (!Files.exists(Paths.get(changeInfoPath))) {
            log.warn("Change info file not found: " + changeInfoPath);
            throw new FileNotFoundException("Change info file not found: " + changeInfoPath);
        }

        // 读取原始pom.xml内容
        String originalContent = new String(Files.readAllBytes(Paths.get(pomPath)));

        // 读取变更信息
        DependencyChangeInfo changeInfo = loadChangeInfoFromJson(changeInfoPath);

        // 恢复更新的依赖项版本
        String rolledBackContent = rollbackDependencyVersions(originalContent, changeInfo);

        // 移除添加的依赖项
        String finalContent = removeAddedDependencies(rolledBackContent, changeInfo);

        // 写回pom文件，保持原有格式
        Files.writeString(Paths.get(pomPath), finalContent);

        // 清空变更信息文件内容
        Files.writeString(Paths.get(changeInfoPath), "");
        log.info("Rollback completed. Change info file content cleared.");
    }

    /**
     * 回滚依赖版本
     *
     * @param xmlContent XML内容
     * @param changeInfo 变更信息
     * @return 回滚后的XML内容
     */
    private static String rollbackDependencyVersions(String xmlContent, DependencyChangeInfo changeInfo) {
        String result = xmlContent;

        for (DependencyInfo updatedDependency : changeInfo.getUpdateDependencies()) {
            // 构建依赖项的正则表达式模式
            String patternString = "(<dependency[^>]*>\\s*" +
                    "<groupId>" + Pattern.quote(updatedDependency.getGroupId()) + "</groupId>\\s*" +
                    "<artifactId>" + Pattern.quote(updatedDependency.getArtifactId()) + "</artifactId>\\s*" +
                    "<version>)[^<]*(</version>)";

            Pattern pattern = Pattern.compile(patternString, Pattern.DOTALL);
            Matcher matcher = pattern.matcher(result);

            StringBuilder sb = new StringBuilder();
            boolean found = false;
            if (matcher.find()) {
                // 替换为旧版本，直接使用捕获组
                matcher.appendReplacement(sb, "$1" + Matcher.quoteReplacement(updatedDependency.getOldVersion()) + "$2");
                log.info("Rolled back dependency " + updatedDependency.getGroupId() + ":" +
                        updatedDependency.getArtifactId() + " to version " + updatedDependency.getOldVersion());
                found = true;
            }
            matcher.appendTail(sb);
            result = sb.toString();

            if (!found) {
                log.warn("Could not find dependency to rollback: " + updatedDependency.getGroupId() + ":" +
                        updatedDependency.getArtifactId());
            }
        }

        return result;
    }

    /**
     * 移除添加的依赖项
     *
     * @param xmlContent XML内容
     *                   * @param changeInfo 变更信息
     * @return 移除添加依赖后的XML内容
     */
    private static String removeAddedDependencies(String xmlContent, DependencyChangeInfo changeInfo) {
        String result = xmlContent;

        // 从后往前移除，避免索引变化影响
        for (int i = changeInfo.getAddDependencies().size() - 1; i >= 0; i--) {
            DependencyInfo addedDependency = changeInfo.getAddDependencies().get(i);

            // 构建匹配依赖项的正则表达式
            String patternString = "<dependency>\\s*" +
                    "<groupId>" + Pattern.quote(addedDependency.getGroupId()) + "</groupId>\\s*" +
                    "<artifactId>" + Pattern.quote(addedDependency.getArtifactId()) + "</artifactId>\\s*" +
                    "<version>" + Pattern.quote(addedDependency.getNewVersion()) + "</version>\\s*" +
                    "(<scope>[^<]*</scope>\\s*)?" +  // 可选的scope
                    "</dependency>";

            Pattern pattern = Pattern.compile(patternString, Pattern.DOTALL);
            Matcher matcher = pattern.matcher(result);

            if (matcher.find()) {
                // 获取匹配到的依赖项内容
                int matchStart = matcher.start();

                // 查找依赖项后面的换行符和缩进空格
                int trailingWhitespaceEnd = matcher.end();
                while (trailingWhitespaceEnd < result.length()) {
                    char c = result.charAt(trailingWhitespaceEnd);
                    if (c == ' ' || c == '\t') {
                        trailingWhitespaceEnd++;
                    } else if (c == '\n') {
                        trailingWhitespaceEnd++;
                        // 继续查找下一行的缩进
                        while (trailingWhitespaceEnd < result.length() &&
                                (result.charAt(trailingWhitespaceEnd) == ' ' || result.charAt(trailingWhitespaceEnd) == '\t')) {
                            trailingWhitespaceEnd++;
                        }
                        break;
                    } else {
                        break;
                    }
                }

                // 构造新的字符串，只移除依赖项本身和其后面的格式字符
                String before = result.substring(0, matchStart);
                String after = result.substring(trailingWhitespaceEnd);
                result = before + after;

                log.info("Removed added dependency " + addedDependency.getGroupId() + ":" +
                        addedDependency.getArtifactId() + " with trailing formatting");
            } else {
                log.warn("Could not find added dependency to remove: " + addedDependency.getGroupId() + ":" +
                        addedDependency.getArtifactId());
            }
        }

        return result;
    }

    /**
     * 使用正则表达式方式删除依赖（备用方案）
     *
     * @param xmlContent XML内容
     * @param dependency 要删除的依赖信息
     * @return 删除依赖后的XML内容
     */
    private static String removeDependencyByPattern(String xmlContent, DependencyInfo dependency) {
        // 构建匹配依赖项的正则表达式，包括前面可能的空白字符
        String patternString = "\\s*<dependency>\\s*" +
                "<groupId>" + Pattern.quote(dependency.getGroupId()) + "</groupId>\\s*" +
                "<artifactId>" + Pattern.quote(dependency.getArtifactId()) + "</artifactId>\\s*" +
                "<version>" + Pattern.quote(dependency.getNewVersion()) + "</version>\\s*" +
                "(<scope>[^<]*</scope>\\s*)?" +  // 可选的scope
                "</dependency>\\s*";

        Pattern pattern = Pattern.compile(patternString, Pattern.DOTALL);
        Matcher matcher = pattern.matcher(xmlContent);

        if (matcher.find()) {
            String result = matcher.replaceFirst("");
            log.info("Removed added dependency " + dependency.getGroupId() + ":" +
                    dependency.getArtifactId() + " by pattern matching (fallback method)");
            return result;
        } else {
            log.warn("Could not find added dependency to remove: " + dependency.getGroupId() + ":" +
                    dependency.getArtifactId() + " by pattern matching");
            return xmlContent;
        }
    }

    /**
     * 检查是否存在变更记录文件
     *
     * @param pomPath pom.xml文件路径
     * @return 是否存在变更记录
     */
    public static boolean hasChangeRecord(String pomPath) {
        String changeInfoPath = getChangeInfoPath(pomPath);
        return Files.exists(Paths.get(changeInfoPath));
    }

    /**
     * 将变更信息保存到JSON文件
     *
     * @param changeInfo 变更信息
     * @param filePath   JSON文件路径
     * @throws IOException
     */
    private static void saveChangeInfoToJson(DependencyChangeInfo changeInfo, String filePath) throws IOException {
        try (FileWriter writer = new FileWriter(filePath)) {
            writer.write(objectToJson(changeInfo));
        }
        log.info("Change info saved to " + filePath);
    }

    /**
     * 从JSON文件加载变更信息
     *
     * @param filePath JSON文件路径
     * @return 变更信息
     * @throws IOException
     */
    private static DependencyChangeInfo loadChangeInfoFromJson(String filePath) throws IOException {
        String content = new String(Files.readAllBytes(Paths.get(filePath)));
        return JSONUtil.toBean(content, DependencyChangeInfo.class);
    }

    /**
     * 简单的对象转JSON实现（实际项目中建议使用Jackson或Gson等库）
     */
    private static String objectToJson(DependencyChangeInfo obj) {
        return JSONUtil.toJsonStr(obj);
    }

    /**
     * 解析依赖信息JSON数组
     */
    private static void parseDependenciesFromJson(String json, List<DependencyInfo> deps, boolean hasOldVersion) {
        // 简化的JSON解析实现
        // 实际项目中应该使用专业JSON库如Jackson或Gson
        if (json.startsWith("[") && json.endsWith("]")) {
            String content = json.substring(1, json.length() - 1);
            if (!content.trim().isEmpty()) {
                String[] depStrings = content.split("(?<=\\})[\\s\n\r]*,[\\s\n\r]*(?=\\{)");
                for (String depStr : depStrings) {
                    DependencyInfo dep = new DependencyInfo();
                    // 解析groupId
                    int groupIdStart = depStr.indexOf("\"groupId\":\"") + 11;
                    int groupIdEnd = depStr.indexOf("\"", groupIdStart);
                    if (groupIdStart > 10 && groupIdEnd > groupIdStart) {
                        dep.setGroupId(depStr.substring(groupIdStart, groupIdEnd));
                    }

                    // 解析artifactId
                    int artifactIdStart = depStr.indexOf("\"artifactId\":\"") + 14;
                    int artifactIdEnd = depStr.indexOf("\"", artifactIdStart);
                    if (artifactIdStart > 13 && artifactIdEnd > artifactIdStart) {
                        dep.setArtifactId(depStr.substring(artifactIdStart, artifactIdEnd));
                    }

                    // 解析oldVersion
                    int oldVersionStart = depStr.indexOf("\"oldVersion\":\"") + 14;
                    int oldVersionEnd = depStr.indexOf("\"", oldVersionStart);
                    if (oldVersionStart > 13 && oldVersionEnd > oldVersionStart) {
                        String oldVersion = depStr.substring(oldVersionStart, oldVersionEnd);
                        // 处理空字符串情况
                        if (!oldVersion.isEmpty()) {
                            dep.setOldVersion(oldVersion);
                        }
                    }

                    // 解析newVersion
                    int newVersionStart = depStr.indexOf("\"newVersion\":\"") + 14;
                    int newVersionEnd = depStr.indexOf("\"", newVersionStart);
                    if (newVersionStart > 13 && newVersionEnd > newVersionStart) {
                        dep.setNewVersion(depStr.substring(newVersionStart, newVersionEnd));
                    }

                    // 解析startIndex和endIndex（仅对addedDependencies）
                    if (!hasOldVersion) {
                        // 解析startIndex
                        int startIndexPos = depStr.indexOf("\"startIndex\":");
                        if (startIndexPos > 0) {
                            int startIndexValueStart = startIndexPos + 12;
                            int startIndexValueEnd = depStr.indexOf(",", startIndexValueStart);
                            if (startIndexValueEnd == -1) {
                                startIndexValueEnd = depStr.indexOf("}", startIndexValueStart);
                            }
                            if (startIndexValueEnd > startIndexValueStart) {
                                String startIndexStr = depStr.substring(startIndexValueStart, startIndexValueEnd).trim();
                                try {
                                    dep.setStartIndex(Integer.parseInt(startIndexStr));
                                } catch (NumberFormatException e) {
                                    dep.setStartIndex(-1);
                                }
                            }
                        }

                        // 解析endIndex
                        int endIndexPos = depStr.indexOf("\"endIndex\":");
                        if (endIndexPos > 0) {
                            int endIndexValueStart = endIndexPos + 11;
                            int endIndexValueEnd = depStr.indexOf("}", endIndexValueStart);
                            if (endIndexValueEnd > endIndexValueStart) {
                                String endIndexStr = depStr.substring(endIndexValueStart, endIndexValueEnd).trim();
                                try {
                                    dep.setEndIndex(Integer.parseInt(endIndexStr));
                                } catch (NumberFormatException e) {
                                    dep.setEndIndex(-1);
                                }
                            }
                        }
                    }

                    deps.add(dep);
                }
            }
        }
    }

    /**
     * 在pom.xml中找到需要修改版本号的依赖
     *
     * @param pomPath
     * @param groupId
     * @param artifactId
     * @param newVersion
     * @throws Exception
     */
    public static void updateDependencyVersion(String pomPath, String groupId,
                                               String artifactId, String newVersion) throws Exception {
        updateDependencyAndAddNew(pomPath, groupId, artifactId, newVersion, null);
    }

    /**
     * 在pom.xml中添加新的依赖项
     *
     * @param pomPath       pom.xml文件路径
     * @param newDependency 要添加的新依赖项
     * @throws Exception
     */
    public static void addNewDependency(String pomPath, Dependency newDependency) throws Exception {
        updateDependencyAndAddNew(pomPath, null, null, null, newDependency);
    }

    /**
     * 批量更新依赖版本
     *
     * @param pomPath      pom.xml文件路径
     * @param dependencies 要更新的依赖列表
     * @throws Exception
     */
    public static void batchUpdateDependencyVersions(String pomPath,
                                                     List<DependencyUpdateInfo> dependencies) throws Exception {
        // 读取原始pom.xml内容
        String originalContent = new String(Files.readAllBytes(Paths.get(pomPath)));

        // 创建变更信息对象
        DependencyChangeInfo changeInfo = new DependencyChangeInfo();

        // 依次处理每个依赖更新
        String updatedContent = originalContent;
        for (DependencyUpdateInfo depInfo : dependencies) {
            updatedContent = updateDependencyVersionInXml(updatedContent,
                    depInfo.getGroupId(),
                    depInfo.getArtifactId(),
                    depInfo.getNewVersion(),
                    changeInfo);
        }

        // 写回pom文件，保持原有格式
        Files.writeString(Paths.get(pomPath), updatedContent);

        // 将变更信息保存到JSON文件
        String changeInfoPath = getChangeInfoPath(pomPath);
        saveChangeInfoToJson(changeInfo, changeInfoPath);
    }

    /**
     * 批量添加新依赖项
     *
     * @param pomPath         pom.xml文件路径
     * @param newDependencies 要添加的新依赖项列表
     * @throws Exception
     */
    public static void batchAddNewDependencies(String pomPath,
                                               List<Dependency> newDependencies) throws Exception {
        // 读取原始pom.xml内容
        String originalContent = new String(Files.readAllBytes(Paths.get(pomPath)));

        // 创建变更信息对象
        DependencyChangeInfo changeInfo = new DependencyChangeInfo();

        // 依次添加每个依赖
        String updatedContent = originalContent;
        for (Dependency dep : newDependencies) {
            updatedContent = addNewDependencyToXml(updatedContent, dep, changeInfo);
        }

        // 写回pom文件，保持原有格式
        Files.writeString(Paths.get(pomPath), updatedContent);

        // 将变更信息保存到JSON文件
        String changeInfoPath = getChangeInfoPath(pomPath);
        saveChangeInfoToJson(changeInfo, changeInfoPath);
    }

    /**
     * 批量更新依赖版本并添加新依赖项
     *
     * @param pomPath              pom.xml文件路径
     * @param dependenciesToUpdate 要更新的依赖列表
     * @param dependenciesToAdd    要添加的新依赖项列表
     * @throws Exception
     */
    public static void batchUpdateAndAddDependencies(String pomPath,
                                                     List<Dependency> dependenciesToUpdate,
                                                     List<Dependency> dependenciesToAdd) throws Exception {
        // 读取原始pom.xml内容
        String originalContent = new String(Files.readAllBytes(Paths.get(pomPath)));

        // 创建变更信息对象
        DependencyChangeInfo changeInfo = new DependencyChangeInfo();

        // 依次处理每个依赖更新
        String updatedContent = originalContent;
        for (Dependency depInfo : dependenciesToUpdate) {
            updatedContent = updateDependencyVersionInXml(updatedContent,
                    depInfo.getGroupId(),
                    depInfo.getArtifactId(),
                    depInfo.getVersion(),
                    changeInfo);
        }

        // 依次添加每个新依赖
        String finalContent = updatedContent;
        for (Dependency dep : dependenciesToAdd) {
            finalContent = addNewDependencyToXml(finalContent, dep, changeInfo);
        }

        // 写回pom文件，保持原有格式
        Files.writeString(Paths.get(pomPath), finalContent);

        // 将变更信息保存到JSON文件
        String changeInfoPath = getChangeInfoPath(pomPath);
        saveChangeInfoToJson(changeInfo, changeInfoPath);
    }

    /**
     * 检查POM文件中是否存在指定版本的依赖
     *
     * @param pomPath    POM文件路径
     * @param groupId    依赖的groupId
     * @param artifactId 依赖的artifactId
     * @param version    依赖的版本号
     * @return 如果存在返回true，否则返回false
     */
    public static boolean checkDependencyExists(String pomPath, String groupId, String artifactId, String version) {
        try {
            // 使用Maven Model读取POM文件
            MavenXpp3Reader reader = new MavenXpp3Reader();
            Model model = reader.read(new FileReader(pomPath));

            // 检查dependencies中的依赖
            List<org.apache.maven.model.Dependency> dependencies = model.getDependencies();
            if (dependencies != null) {
                for (org.apache.maven.model.Dependency dep : dependencies) {
                    if (groupId.equals(dep.getGroupId()) &&
                            artifactId.equals(dep.getArtifactId()) &&
                            version.equals(dep.getVersion())) {
                        return true;
                    }
                }
            }

            // 检查dependencyManagement中的依赖
            DependencyManagement dependencyManagement = model.getDependencyManagement();
            if (dependencyManagement != null && dependencyManagement.getDependencies() != null) {
                for (org.apache.maven.model.Dependency dep : dependencyManagement.getDependencies()) {
                    if (groupId.equals(dep.getGroupId()) &&
                            artifactId.equals(dep.getArtifactId()) &&
                            version.equals(dep.getVersion())) {
                        return true;
                    }
                }
            }

        } catch (Exception e) {
            // 处理异常
            log.error("Error checking dependency: " + e.getMessage());
        }
        return false;
    }

    /**
     * 依赖变更信息类
     */
    @Data
    public static class DependencyChangeInfo {
        private List<DependencyInfo> updateDependencies = new ArrayList<>();
        private List<DependencyInfo> addDependencies = new ArrayList<>();
    }

    /**
     * 依赖信息类
     */
    @Data
    public static class DependencyInfo {
        private String groupId;
        private String artifactId;
        private String oldVersion;
        private String newVersion;
        // 用于精确回滚的额外信息
        // 新增依赖内容的起始位置
        private Integer startIndex = null;
        // 新增依赖内容的结束位置
        private Integer endIndex = null;

        public DependencyInfo() {
        }

        public DependencyInfo(String groupId, String artifactId, String oldVersion, String newVersion) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.oldVersion = oldVersion;
            this.newVersion = newVersion;
        }
    }

    /**
     * 依赖更新信息类
     */
    @Data
    public static class DependencyUpdateInfo {
        private String groupId;
        private String artifactId;
        private String newVersion;

        public DependencyUpdateInfo() {
        }

        public DependencyUpdateInfo(String groupId, String artifactId, String newVersion) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.newVersion = newVersion;
        }
    }
}