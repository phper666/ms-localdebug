package com.localdebug.plugin.core.util;

import cn.hutool.http.HttpUtil;
import lombok.extern.slf4j.Slf4j;
import net.lingala.zip4j.ZipFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具安装器（headless）：下载 kubectl / ktctl 到 ~/.localdebug/bin/ 并验证。
 * 由 UI 层（Task.Backgroundable）调用，日志通过 Consumer 输出。
 */
@Slf4j
public class ToolInstaller {

    private ToolInstaller() {
    }

    /**
     * 下载并配置 kubectl 工具（版本比对，已装版本较新则跳过下载）
     */
    public static void installKubectl(String downloadUrl, Consumer<String> logger) {
        try {
            LogOutputUtil.log(logger, "开始检查 kubectl...，下载地址: " + downloadUrl);

            Path toolsDir = DirectoryUtil.ensureToolsBinDirExists();
            LogOutputUtil.log(logger, "工具目录: " + toolsDir);

            String targetVersion = extractVersionFromUrl(downloadUrl);
            LogOutputUtil.log(logger, "目标版本: " + targetVersion);

            Path kubectlPath = toolsDir.resolve(EnvUtil.toolBinaryName("kubectl"));
            String installedVersion = getInstalledKubectlVersion(kubectlPath);
            LogOutputUtil.log(logger, "已安装版本: " + (installedVersion == null ? "未检测到" : installedVersion));

            if (installedVersion != null && compareVersions(targetVersion, installedVersion) <= 0) {
                LogOutputUtil.log(logger, "已安装版本较新或相同，无需重新下载");
                addToPathVariable(toolsDir.toString(), logger);
            } else {
                LogOutputUtil.log(logger, "开始下载 kubectl 到: " + kubectlPath);
                HttpUtil.downloadFile(downloadUrl, kubectlPath.toFile());
                LogOutputUtil.log(logger, "kubectl 下载完成");
                if (!EnvUtil.isWindows()) {
                    Files.setPosixFilePermissions(kubectlPath, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
                }
                addToPathVariable(toolsDir.toString(), logger);
            }

            verifyKubectlInstallation(logger);
            LogOutputUtil.log(logger, "kubectl 处理完成");
        } catch (Exception e) {
            LogOutputUtil.error(logger, "处理 kubectl 失败: " + e.getMessage());
        }
    }

    /**
     * 下载并配置 ktconnect 工具（下载 zip -> zip4j 解压 -> 验证）
     */
    public static void installKtconnect(String downloadUrl, Consumer<String> logger) {
        try {
            LogOutputUtil.log(logger, "开始下载 ktconnect...，下载地址: " + downloadUrl);

            Path toolsDir = DirectoryUtil.ensureToolsBinDirExists();
            Path downloadedFile = toolsDir.resolve("ktctl.zip");
            LogOutputUtil.log(logger, "正在下载到: " + downloadedFile);
            HttpUtil.downloadFile(downloadUrl, downloadedFile.toFile());

            LogOutputUtil.log(logger, "正在解压文件...");
            ZipFile zipFile = new ZipFile(downloadedFile.toFile());
            zipFile.extractAll(toolsDir.toString());
            LogOutputUtil.log(logger, "解压完成");

            Path ktctlBinary = toolsDir.resolve(EnvUtil.toolBinaryName("ktctl"));
            if (Files.exists(ktctlBinary)) {
                LogOutputUtil.log(logger, EnvUtil.toolBinaryName("ktctl") + " 文件已提取");
                if (!EnvUtil.isWindows()) {
                    Files.setPosixFilePermissions(ktctlBinary, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
                }
            } else {
                LogOutputUtil.error(logger, "未找到 " + EnvUtil.toolBinaryName("ktctl") + " 文件");
            }
            if (EnvUtil.isWindows()) {
                Path wintunDll = toolsDir.resolve("wintun.dll");
                LogOutputUtil.log(logger, Files.exists(wintunDll) ? "wintun.dll 文件已提取" : "警告: 未找到 wintun.dll 文件");
            }

            Files.deleteIfExists(downloadedFile);
            addToPathVariable(toolsDir.toString(), logger);
            verifyKtctlInstallation(logger);
            LogOutputUtil.log(logger, "ktconnect 已成功安装");
        } catch (Exception e) {
            LogOutputUtil.error(logger, "安装 ktconnect 失败: " + e.getMessage());
        }
    }

    private static String getInstalledKubectlVersion(Path kubectlPath) {
        try {
            if (!Files.exists(kubectlPath)) {
                return null;
            }
            ProcessBuilder processBuilder = new ProcessBuilder();
            processBuilder.command(kubectlPath.toString(), "version", "--client");
            Process process = processBuilder.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            process.waitFor();

            Pattern pattern = Pattern.compile("Client Version:\\s+v?(\\d+\\.\\d+\\.\\d+)");
            Matcher matcher = pattern.matcher(output.toString());
            if (matcher.find()) {
                return "v" + matcher.group(1);
            }
            pattern = Pattern.compile("v\\d+\\.\\d+\\.\\d+");
            matcher = pattern.matcher(output.toString());
            if (matcher.find()) {
                return matcher.group();
            }
        } catch (Exception e) {
            log.error("获取已安装的 kubectl 版本失败: {}", e.getMessage());
        }
        return null;
    }

    private static String extractVersionFromUrl(String downloadUrl) {
        Pattern pattern = Pattern.compile("release/(v\\d+\\.\\d+\\.\\d+)/");
        Matcher matcher = pattern.matcher(downloadUrl);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "unknown";
    }

    private static int compareVersions(String version1, String version2) {
        if (version1.equals(version2)) {
            return 0;
        }
        String v1 = version1.startsWith("v") ? version1.substring(1) : version1;
        String v2 = version2.startsWith("v") ? version2.substring(1) : version2;
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        int length = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < length; i++) {
            int part1 = i < parts1.length ? Integer.parseInt(parts1[i]) : 0;
            int part2 = i < parts2.length ? Integer.parseInt(parts2[i]) : 0;
            if (part1 > part2) {
                return 1;
            }
            if (part1 < part2) {
                return -1;
            }
        }
        return 0;
    }

    private static void verifyKubectlInstallation(Consumer<String> logger) {
        try {
            LogOutputUtil.log(logger, "验证 kubectl 安装，执行命令: kubectl version --client");
            ProcessBuilder versionProcessBuilder = new ProcessBuilder();
            versionProcessBuilder.command(EnvUtil.toolBinaryName("kubectl"), "version", "--client");
            Process versionProcess = versionProcessBuilder.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(versionProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    LogOutputUtil.log(logger, line);
                }
            }
            versionProcess.waitFor();
            if (versionProcess.exitValue() == 0) {
                LogOutputUtil.log(logger, "kubectl 安装验证成功");
            } else {
                LogOutputUtil.error(logger, "kubectl 安装验证失败");
            }
        } catch (Exception e) {
            LogOutputUtil.error(logger, "验证 kubectl 安装失败: " + e.getMessage());
        }
    }

    private static void verifyKtctlInstallation(Consumer<String> logger) {
        try {
            LogOutputUtil.log(logger, "验证 ktctl 安装，执行命令: ktctl --version");
            ProcessBuilder processBuilder = new ProcessBuilder();
            processBuilder.command(EnvUtil.toolBinaryName("ktctl"), "--version");
            Process process = processBuilder.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    LogOutputUtil.log(logger, line);
                }
            }
            process.waitFor();
            if (process.exitValue() == 0) {
                LogOutputUtil.log(logger, "ktctl 安装验证成功");
            } else {
                LogOutputUtil.error(logger, "ktctl 安装验证失败");
            }
        } catch (Exception e) {
            LogOutputUtil.error(logger, "验证 ktctl 安装失败: " + e.getMessage());
        }
    }

    /**
     * 将工具目录加入 PATH：Windows 用 setx 写用户环境变量；Unix 只提示手动配置
     */
    private static void addToPathVariable(String pathToAdd, Consumer<String> logger) {
        try {
            String currentPath = System.getenv("PATH");
            String[] paths = currentPath.split(";");
            boolean pathExists = Arrays.stream(paths)
                    .anyMatch(p -> p.equalsIgnoreCase(pathToAdd) ||
                            p.equalsIgnoreCase(pathToAdd + "\\") ||
                            (p + "\\").equalsIgnoreCase(pathToAdd));
            if (pathExists) {
                LogOutputUtil.log(logger, "路径已存在于环境变量中: " + pathToAdd);
                return;
            }

            if (EnvUtil.isWindows()) {
                ProcessBuilder processBuilder = new ProcessBuilder("cmd", "/c", "setx", "PATH", currentPath + ";" + pathToAdd);
                Process process = processBuilder.start();
                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    LogOutputUtil.log(logger, "已将路径添加到环境变量: " + pathToAdd + "，请重启 IDEA 使其生效");
                } else {
                    LogOutputUtil.error(logger, "添加环境变量失败，可能需要手动添加: " + pathToAdd);
                }
            } else {
                LogOutputUtil.log(logger, "请手动将以下路径添加到 PATH（如 ~/.zshrc 或 ~/.bashrc）: " + pathToAdd);
            }
        } catch (Exception e) {
            LogOutputUtil.error(logger, "添加环境变量失败: " + e.getMessage());
            LogOutputUtil.log(logger, "请手动将以下路径添加到系统环境变量 PATH 中: " + pathToAdd);
        }
    }
}
