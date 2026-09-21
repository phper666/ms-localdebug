package com.localdebug.plugin.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import com.localdebug.plugin.core.process.DebugCommandService;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 调试命令区：读取选中项目的 .localdebug.ktctl.cmds，逐条启动/终止（走 DebugCommandService）
 */
public class DebugCommandsPanel extends JPanel {

    private final Project project;
    private final DebugCommandService commandService;
    private final JComboBox<String> projectCombo = new JComboBox<>();
    private final JPanel commandListPanel = new JPanel();
    private final Map<String, JLabel> statusLabels = new HashMap<>();

    public DebugCommandsPanel(Project project) {
        this.project = project;
        this.commandService = DebugCommandService.getInstance(project);
        setLayout(new BorderLayout());

        // 头部：项目下拉全宽 + 刷新图标按钮，窄侧栏不挤压
        JPanel header = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        header.setBorder(BorderFactory.createEmptyBorder(8, 10, 4, 10));
        header.add(new JBLabel("Debug 项目:"), BorderLayout.WEST);
        projectCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, projectCombo.getPreferredSize().height));
        header.add(projectCombo, BorderLayout.CENTER);
        header.add(UiComponents.iconBar("LocalDebug.CommandsHeader",
                new UiComponents.IconButton(AllIcons.Actions.Refresh, "刷新",
                        () -> ApplicationManager.getApplication().executeOnPooledThread(this::refreshCommands))
        ), BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        commandListPanel.setLayout(new BoxLayout(commandListPanel, BoxLayout.Y_AXIS));
        JBScrollPane scrollPane = new JBScrollPane(commandListPanel);
        scrollPane.setBorder(BorderFactory.createTitledBorder("调试命令 (来源: .localdebug.ktctl.cmds)"));
        add(scrollPane, BorderLayout.CENTER);

        refreshProjectCombo();
    }

    private void refreshProjectCombo() {
        projectCombo.removeAllItems();
        ConfigManagerHolder.CONFIG_MANAGER.getDebugConfig().getProjectPaths()
                .forEach(projectCombo::addItem);
        if (projectCombo.getItemCount() > 0) {
            projectCombo.setSelectedIndex(0);
            refreshCommands();
        }
    }

    private void refreshCommands() {
        Object selected = projectCombo.getSelectedItem();
        String projectPath = selected == null ? null : selected.toString();

        List<DebugCommandService.CommandEntry> commands = new ArrayList<>();
        if (projectPath != null && Files.exists(Paths.get(projectPath, ".localdebug.ktctl.cmds"))) {
            commands = DebugCommandService.parseCommands(projectPath);
        }

        List<DebugCommandService.CommandEntry> finalCommands = commands;
        SwingUtilities.invokeLater(() -> rebuildCommandList(projectPath, finalCommands));
    }

    private void rebuildCommandList(String projectPath, List<DebugCommandService.CommandEntry> commands) {
        commandListPanel.removeAll();
        statusLabels.clear();

        if (projectPath == null) {
            commandListPanel.add(new JBLabel("请先在「项目配置」页添加 debug 项目"));
        } else if (commands.isEmpty()) {
            commandListPanel.add(new JBLabel("未找到调试命令，请先执行「替换配置」生成 .localdebug.ktctl.cmds"));
        } else {
            for (DebugCommandService.CommandEntry command : commands) {
                commandListPanel.add(createCommandRow(projectPath, command));
                commandListPanel.add(Box.createVerticalStrut(JBUI.scale(4)));
            }
        }
        // 末端胶水吸收视口撑高余量：内容不足一屏时行不垂直拉伸，永远保持首选高
        commandListPanel.add(Box.createVerticalGlue());
        commandListPanel.revalidate();
        commandListPanel.repaint();
    }

    private JPanel createCommandRow(String projectPath, DebugCommandService.CommandEntry command) {
        JPanel row = new JPanel(new BorderLayout(JBUI.scale(6), JBUI.scale(4)));
        row.setBorder(BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), 1, 1, 1, 1),
                JBUI.Borders.empty(6, 8)));

        // 首行：名称 + 状态（左），启动/终止图标按钮（右，不另起一行）
        JPanel nameLine = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        JBBox left = JBBox.createHorizontalBox();
        left.add(new JBLabel(command.getDisplayName() + "  [" + command.getAppName() + "]"));
        left.add(Box.createHorizontalStrut(JBUI.scale(8)));
        JBLabel statusLabel = new JBLabel();
        statusLabels.put(command.getDisplayName(), statusLabel);
        left.add(statusLabel);
        nameLine.add(left, BorderLayout.WEST);
        nameLine.add(UiComponents.iconBar("LocalDebug.CommandRow." + command.getDisplayName(),
                new UiComponents.IconButton(AllIcons.Actions.Execute, "启动",
                        () -> commandService.start(projectPath, command, line -> appendCommandLog(command.getDisplayName(), line))),
                new UiComponents.IconButton(AllIcons.Actions.Cancel, "终止", () -> {
                    commandService.stop(command.getDisplayName(), line -> appendCommandLog(command.getDisplayName(), line));
                    updateStatus(command.getDisplayName());
                })
        ), BorderLayout.EAST);
        row.add(nameLine, BorderLayout.NORTH);

        JBTextArea commandArea = new JBTextArea(command.getCommand(), 2, 40);
        commandArea.setEditable(false);
        commandArea.setLineWrap(true);
        commandArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        row.add(new JBScrollPane(commandArea), BorderLayout.CENTER);

        updateStatus(command.getDisplayName());
        return row;
    }

    private void appendCommandLog(String commandName, String line) {
        ApplicationManager.getApplication().invokeLater(() -> {
            JLabel statusLabel = statusLabels.get(commandName);
            if (statusLabel != null) {
                statusLabel.setText("输出: " + line);
            }
        });
    }

    private void updateStatus(String commandName) {
        ApplicationManager.getApplication().invokeLater(() -> {
            JLabel statusLabel = statusLabels.get(commandName);
            if (statusLabel != null) {
                statusLabel.setText(commandService.isRunning(commandName) ? "[运行中]" : "[已停止]");
            }
        });
    }

    /**
     * 共享的 ConfigManager（避免每个面板重复读取全局配置文件）
     */
    private static final class ConfigManagerHolder {
        private static final com.localdebug.plugin.core.config.ConfigManager CONFIG_MANAGER =
                com.localdebug.plugin.core.config.ConfigManager.getInstance();
    }
}
