package com.localdebug.plugin.core.process;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.localdebug.plugin.core.bridge.KtConnectBridge;
import com.localdebug.plugin.core.bridge.ProxyBridge;
import com.localdebug.plugin.core.constant.Constants;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 调试命令（ktctl）进程服务（headless）——facade 角色：
 * 进程管理已收敛进 {@link ProxyBridge}（实现① KtConnectBridge，信号文件停止语义），
 * 本服务保留项目级生命周期（dispose → stopAll）与 .localdebug.ktctl.cmds 解析，公开方法签名不变（UI 兼容）。
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

    private final ProxyBridge bridge = new KtConnectBridge();

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
        return bridge.hasRunningProcesses();
    }

    /**
     * 指定命令是否运行中
     */
    public boolean isRunning(String commandName) {
        return bridge.isRunning(commandName);
    }

    /**
     * 运行中进程信息（供 UI 查询）
     */
    public List<String> getRunningProcessesInfo() {
        return bridge.getRunningProcessesInfo();
    }

    /**
     * 在项目目录启动命令（若已在运行则先停止再重启）
     */
    public void start(String projectPath, CommandEntry entry, Consumer<String> logger) {
        bridge.start(entry.getDisplayName(), entry.getCommand(), projectPath, logger);
    }

    /**
     * 停止命令：优先写信号文件（ktctl-*-signal-<pid> 内容 "stop"），失败则 destroy
     */
    public void stop(String commandName, Consumer<String> logger) {
        bridge.stop(commandName, logger);
    }

    /**
     * 停止全部运行中进程（项目关闭时调用）
     */
    public void stopAll() {
        bridge.stopAll();
    }

    @Override
    public void dispose() {
        stopAll();
    }
}
