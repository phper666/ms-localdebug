package com.localdebug.plugin.core.bridge;

import com.localdebug.plugin.core.util.DirectoryUtil;
import com.localdebug.plugin.core.util.EnvUtil;
import com.localdebug.plugin.core.util.LogOutputUtil;
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
 * ktctl 隧道通路实现（原 DebugCommandService 进程管理搬入，行为保留）：
 * <ul>
 *   <li>信号文件停止语义：ktctl-*-signal-&lt;pid&gt; 内容 "stop"，失败回退 destroy + 超时强杀</li>
 *   <li>PATH 注入：启动前把 ~/.localdebug/bin 前置进环境 PATH（ktctl 依赖 bin 内工具）</li>
 *   <li>整串执行：命令按 OS 用 sh -c / cmd /c 包裹，消除空白切分 bug</li>
 * </ul>
 */
@Slf4j
public class KtConnectBridge implements ProxyBridge {

    private final Map<String, Process> runningProcesses = new ConcurrentHashMap<>();
    private final Map<String, String> runningCommandContents = new ConcurrentHashMap<>();

    @Override
    public synchronized void start(String name, String command, String workDir, Consumer<String> logger) {
        // M5 入口守卫：命令含未填写占位符（<...>）或引号不配对 → 拒绝启动（避免 ktctl 收到脏命令后静默失败）
        if (hasUnfilledPlaceholderOrUnbalancedQuotes(command)) {
            LogOutputUtil.error(logger, "命令包含未填写的占位符（<...>）或引号不配对，已拒绝启动；请先填写服务名/命名空间: " + command);
            return;
        }
        if (isRunning(name)) {
            LogOutputUtil.log(logger, "命令 '" + name + "' 正在运行中，先停止后重新启动");
            stop(name, logger);
        }
        try {
            LogOutputUtil.log(logger, "正在启动命令: " + name + ", 命令内容: " + command);
            // 整串执行：sh -c / cmd /c，避免空白切分（M8 顺带修复）
            List<String> cmdArray = EnvUtil.isWindows()
                    ? List.of("cmd", "/c", command)
                    : List.of("sh", "-c", command);
            ProcessBuilder processBuilder = new ProcessBuilder(cmdArray);
            processBuilder.directory(new File(workDir));
            processBuilder.redirectErrorStream(true);
            // PATH 注入：~/.localdebug/bin 前置（M8 顺带修复）
            String binDir = DirectoryUtil.getToolsBinDir().toString();
            Map<String, String> env = processBuilder.environment();
            String oldPath = env.get("PATH");
            env.put("PATH", oldPath == null || oldPath.isEmpty() ? binDir : binDir + File.pathSeparator + oldPath);
            Process process = processBuilder.start();
            runningProcesses.put(name, process);
            runningCommandContents.put(name, command);
            startOutputReader(process, name, logger);
            LogOutputUtil.log(logger, "命令已启动，PID: " + process.pid());
        } catch (Exception ex) {
            LogOutputUtil.error(logger, "启动命令时发生错误: " + ex.getMessage());
        }
    }

    @Override
    public synchronized void stop(String name, Consumer<String> logger) {
        Process process = runningProcesses.get(name);
        if (process == null) {
            LogOutputUtil.log(logger, "命令 '" + name + "' 当前未运行");
            return;
        }
        try {
            LogOutputUtil.log(logger, "正在终止命令 '" + name + "'");
            boolean signalSent = sendStopSignal(String.valueOf(process.pid()), runningCommandContents.get(name), logger);
            if (!signalSent) {
                process.destroy();
            }
            Thread waitThread = new Thread(() -> {
                try {
                    boolean finished = process.waitFor(20, TimeUnit.SECONDS);
                    if (!finished) {
                        LogOutputUtil.log(logger, "命令 '" + name + "' 清理超时，将强制终止");
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

    @Override
    public boolean isRunning(String name) {
        Process process = runningProcesses.get(name);
        return process != null && process.isAlive();
    }

    @Override
    public boolean hasRunningProcesses() {
        return runningProcesses.values().stream().anyMatch(p -> p != null && p.isAlive());
    }

    @Override
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

    @Override
    public synchronized void stopAll() {
        for (String name : new ArrayList<>(runningProcesses.keySet())) {
            try {
                stop(name, null);
            } catch (Exception e) {
                log.error("停止进程失败: {}", name, e);
            }
        }
    }

    private void startOutputReader(Process process, String name, Consumer<String> logger) {
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
                runningProcesses.remove(name);
                runningCommandContents.remove(name);
                LogOutputUtil.log(logger, "命令 '" + name + "' 已终止，退出码: " + exitCode);
            } catch (Exception e) {
                runningProcesses.remove(name);
                runningCommandContents.remove(name);
                LogOutputUtil.error(logger, "读取命令输出时发生错误: " + e.getMessage());
            }
        });
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /**
     * M5 命令守卫（可测）：含 &lt; / &gt;（未填写占位符）或单/双引号不配对 → true（拒绝启动）
     */
    static boolean hasUnfilledPlaceholderOrUnbalancedQuotes(String command) {
        if (command == null) {
            return false;
        }
        if (command.contains("<") || command.contains(">")) {
            return true;
        }
        long singleQuotes = command.chars().filter(c -> c == '\'').count();
        long doubleQuotes = command.chars().filter(c -> c == '"').count();
        return singleQuotes % 2 != 0 || doubleQuotes % 2 != 0;
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
