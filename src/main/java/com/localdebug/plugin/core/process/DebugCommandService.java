package com.localdebug.plugin.core.process;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.localdebug.plugin.core.constant.Constants;
import com.localdebug.plugin.core.util.DirectoryUtil;
import com.localdebug.plugin.core.util.LogOutputUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 调试命令（ktctl）进程服务（headless）
 * 从 .localdebug.ktctl.cmds 解析命令，负责进程启动/停止/信号文件管理。
 *
 * 单例选择说明：使用 @Service(Service.Level.PROJECT) 项目级服务而不是 project.getUserData，
 * IDEA 自动随项目关闭调用 dispose()，无需手动 Disposer 注册；通过 project.getService() 获取。
 * 所有运行中的代理进程挂在 Project 生命周期上：项目关闭（dispose）时尝试 stop 全部进程。
 */
@Slf4j
@Service(Service.Level.PROJECT)
public final class DebugCommandService implements Disposable {

    /**
     * 获取项目级单例（随项目关闭自动 dispose）
     */
    public static DebugCommandService getInstance(Project project) {
        return project.getService(DebugCommandService.class);
    }

    private final Map<String, Process> runningProcesses = new ConcurrentHashMap<>();
    private final Map<String, String> runningCommandContents = new ConcurrentHashMap<>();

    /**
     * 命令条目（解析自 .localdebug.ktctl.cmds）
     */
    @Getter
    public static class CommandEntry {
        private final String name;
        private final String appName;
        private final String displayName;
        private String command;

        public CommandEntry(String name, String appName, String displayName) {
            this.name = name;
            this.appName = appName;
            this.displayName = displayName;
        }

        public void setCommand(String command) {
            this.command = command;
        }
    }

    /**
     * 解析 .localdebug.ktctl.cmds，格式：以 "##命令名###应用名" 开头，下一非空行为命令内容
     */
    public static List<CommandEntry> parseCommands(String projectPath) {
        List<CommandEntry> commands = new ArrayList<>();
        Path commandFile = Paths.get(projectPath, Constants.LOCAL_DEBUG_KTCTL_CMD_CONFIG_NAME);
        if (!Files.exists(commandFile)) {
            return commands;
        }
        try {
            List<String> lines = Files.readAllLines(commandFile);
            CommandEntry current = null;
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("##")) {
                    String fullCommandName = line.substring(2).trim();
                    current = new CommandEntry(fullCommandName, extractAppName(fullCommandName), extractDisplayName(fullCommandName));
                } else if (current != null && !line.isEmpty() && !line.startsWith("#")) {
                    current.setCommand(line);
                    commands.add(current);
                    current = null;
                }
            }
        } catch (Exception e) {
            log.error("解析命令文件失败: {}", commandFile, e);
        }
        return commands;
    }

    /**
     * "ktconnect代理###internal-console" -> "internal-console"
     */
    private static String extractAppName(String commandName) {
        int separatorIndex = commandName.indexOf("###");
        if (separatorIndex != -1 && separatorIndex < commandName.length() - 3) {
            return commandName.substring(separatorIndex + 3);
        }
        int lastDashIndex = commandName.lastIndexOf('-');
        if (lastDashIndex != -1 && lastDashIndex < commandName.length() - 1) {
            return commandName.substring(lastDashIndex + 1);
        }
        return commandName;
    }

    /**
     * "ktconnect代理###internal-console" -> "ktconnect代理"
     */
    private static String extractDisplayName(String commandName) {
        int separatorIndex = commandName.indexOf("###");
        if (separatorIndex != -1) {
            return commandName.substring(0, separatorIndex);
        }
        int lastDashIndex = commandName.indexOf('-');
        if (lastDashIndex != -1) {
            return commandName.substring(0, lastDashIndex);
        }
        return commandName;
    }

    /**
     * 是否有运行中的进程
     */
    public boolean hasRunningProcesses() {
        return runningProcesses.values().stream().anyMatch(p -> p != null && p.isAlive());
    }

    /**
     * 指定命令是否运行中
     */
    public boolean isRunning(String commandName) {
        Process process = runningProcesses.get(commandName);
        return process != null && process.isAlive();
    }

    /**
     * 运行中进程信息（供 UI 查询）
     */
    public List<String> getRunningProcessesInfo() {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Process> entry : runningProcesses.entrySet()) {
            Process process = entry.getValue();
            if (process != null && process.isAlive()) {
                result.add(entry.getKey() + " (PID: " + process.pid() + ")");
            }
        }
        return result;
    }

    /**
     * 在项目目录启动命令（若已在运行则先停止再重启）
     */
    public synchronized void start(String projectPath, CommandEntry entry, Consumer<String> logger) {
        String name = entry.getDisplayName();
        if (isRunning(name)) {
            LogOutputUtil.log(logger, "命令 '" + name + "' 正在运行中，先停止后重新启动");
            stop(name, logger);
        }
        try {
            LogOutputUtil.log(logger, "正在启动命令: " + name + ", 命令内容: " + entry.getCommand() + ", 应用名称: " + entry.getAppName());
            String[] cmdArray = entry.getCommand().trim().split("\\s+");
            ProcessBuilder processBuilder = new ProcessBuilder(cmdArray);
            processBuilder.directory(new File(projectPath));
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            runningProcesses.put(name, process);
            runningCommandContents.put(name, entry.getCommand());
            startOutputReader(process, name, logger);
            LogOutputUtil.log(logger, "命令已启动，PID: " + process.pid());
        } catch (Exception ex) {
            LogOutputUtil.error(logger, "启动命令时发生错误: " + ex.getMessage());
        }
    }

    /**
     * 停止命令：优先写信号文件（ktctl-*-signal-<pid> 内容 "stop"），失败则 destroy
     */
    public synchronized void stop(String commandName, Consumer<String> logger) {
        Process process = runningProcesses.get(commandName);
        if (process == null) {
            LogOutputUtil.log(logger, "命令 '" + commandName + "' 当前未运行");
            return;
        }
        try {
            LogOutputUtil.log(logger, "正在终止命令 '" + commandName + "'");
            boolean signalSent = sendStopSignal(String.valueOf(process.pid()), runningCommandContents.get(commandName), logger);
            if (!signalSent) {
                process.destroy();
            }
            Thread waitThread = new Thread(() -> {
                try {
                    boolean finished = process.waitFor(20, TimeUnit.SECONDS);
                    if (!finished) {
                        LogOutputUtil.log(logger, "命令 '" + commandName + "' 清理超时，将强制终止");
                        process.destroyForcibly();
                        process.waitFor(2, TimeUnit.SECONDS);
                    }
                } catch (Exception ex) {
                    LogOutputUtil.error(logger, "等待命令终止时发生错误: " + ex.getMessage());
                }
            });
            waitThread.setDaemon(true);
            waitThread.start();
        } catch (Exception ex) {
            LogOutputUtil.error(logger, "终止命令时发生错误: " + ex.getMessage());
        }
    }

    /**
     * 停止全部运行中进程（项目关闭时调用）
     */
    public synchronized void stopAll() {
        for (String name : new ArrayList<>(runningProcesses.keySet())) {
            try {
                stop(name, null);
            } catch (Exception e) {
                log.error("停止进程失败: {}", name, e);
            }
        }
    }

    @Override
    public void dispose() {
        stopAll();
    }

    private void startOutputReader(Process process, String commandName, Consumer<String> logger) {
        Thread readerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (logger != null) {
                        logger.accept(line);
                    }
                }
                int exitCode = process.waitFor();
                runningProcesses.remove(commandName);
                runningCommandContents.remove(commandName);
                LogOutputUtil.log(logger, "命令 '" + commandName + "' 已终止，退出码: " + exitCode);
            } catch (Exception e) {
                runningProcesses.remove(commandName);
                runningCommandContents.remove(commandName);
                LogOutputUtil.error(logger, "读取命令输出时发生错误: " + e.getMessage());
            }
        });
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /**
     * 根据命令内容和 PID 生成信号文件名
     */
    private static String getSignalFileName(String commandContent, String pid) {
        if (commandContent == null) {
            return null;
        }
        if (commandContent.contains("connect")) {
            return "ktctl-connect-signal-" + pid;
        } else if (commandContent.contains("mesh")) {
            return "ktctl-mesh-signal-" + pid;
        } else if (commandContent.contains("exchange")) {
            return "ktctl-exchange-signal-" + pid;
        } else if (commandContent.contains("preview")) {
            return "ktctl-preview-signal-" + pid;
        }
        return null;
    }

    /**
     * 写信号文件发送停止信号（ktctl 约定：信号文件内容 "stop"）
     */
    private static boolean sendStopSignal(String pid, String commandContent, Consumer<String> logger) {
        String signalFileName = getSignalFileName(commandContent, pid);
        if (signalFileName == null) {
            LogOutputUtil.error(logger, "不支持的命令类型，无法生成信号文件名");
            return false;
        }
        try {
            DirectoryUtil.ensureSignalFilesDirExists();
            Path signalFile = Paths.get(DirectoryUtil.getSignalFilesDir().toString(), signalFileName);
            Files.write(signalFile, "stop".getBytes(StandardCharsets.UTF_8));
            LogOutputUtil.log(logger, "成功发送停止信号到进程，信号文件: " + signalFile);
            return true;
        } catch (Exception e) {
            LogOutputUtil.error(logger, "发送停止信号时发生错误: " + e.getMessage());
            return false;
        }
    }
}
