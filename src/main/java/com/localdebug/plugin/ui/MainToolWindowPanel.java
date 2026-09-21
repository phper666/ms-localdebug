package com.localdebug.plugin.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import com.localdebug.plugin.core.action.ProjectInitAction;
import com.localdebug.plugin.core.action.ProjectReplaceAction;
import com.localdebug.plugin.core.action.ProjectRollbackAction;
import com.localdebug.plugin.core.config.ConfigManager;
import com.localdebug.plugin.core.util.DirectoryUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 项目配置区：管理已注册的 debug 项目列表，执行 初始化/替换/回滚 配置动作
 *
 * 布局约束：整个面板 BorderLayout；NORTH 槽固定高度（= 首选高，绝不垂直拉伸），
 * 控件全部收在 NORTH 内；CENTER 槽 = 输出区占满剩余空间。
 */
public class MainToolWindowPanel extends JPanel {

    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Project project;
    private final ConfigManager configManager = ConfigManager.getInstance();
    private final JComboBox<String> projectCombo = new JComboBox<>();
    private final JBTextArea logArea = new JBTextArea();

    public MainToolWindowPanel(Project project) {
        this.project = project;
        setLayout(new BorderLayout());

        // NORTH：固定高度，两行控件（BorderLayout NORTH/SOUTH 槽 = 首选高，无 weighty，不会拉伸）
        JPanel north = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        north.setBorder(JBUI.Borders.empty(8, 10, 4, 10));

        // 第 1 行：标签 + 项目下拉（水平占满，高度钳制为首选高）+ 图标按钮
        JPanel row1 = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        row1.add(new JBLabel("Debug 项目:"), BorderLayout.WEST);
        projectCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, projectCombo.getPreferredSize().height));
        row1.add(projectCombo, BorderLayout.CENTER);
        row1.add(UiComponents.iconBar("LocalDebug.ProjectRow",
                new UiComponents.IconButton(AllIcons.General.Add, "添加当前项目", this::addCurrentProject),
                new UiComponents.IconButton(AllIcons.General.Remove, "移除选中项目", this::removeSelectedProject),
                new UiComponents.IconButton(AllIcons.General.Settings, "阿里云/K8s 设置...", () -> ShowSettingsUtil.getInstance()
                        .showSettingsDialog(project, com.localdebug.plugin.ui.settings.LocalDebugSettingsConfigurable.class))
        ), BorderLayout.EAST);

        // 第 2 行：初始化/替换/回滚
        JComponent actionRow = UiComponents.iconBar("LocalDebug.ActionRow",
                new UiComponents.IconButton(AllIcons.Actions.Execute, "初始化配置",
                        () -> runAction(new ProjectInitAction(), "LocalDebug 初始化配置", false)),
                new UiComponents.IconButton(AllIcons.Actions.Redo, "替换配置",
                        () -> runAction(new ProjectReplaceAction(), "LocalDebug 替换配置", false)),
                new UiComponents.IconButton(AllIcons.Actions.Undo, "回滚配置",
                        () -> runAction(new ProjectRollbackAction(), "LocalDebug 回滚配置", true))
        );

        north.add(row1, BorderLayout.NORTH);
        north.add(actionRow, BorderLayout.SOUTH);
        add(north, BorderLayout.NORTH);

        // CENTER：输出区占满剩余空间
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        JBScrollPane logScroll = new JBScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("输出"));
        add(logScroll, BorderLayout.CENTER);

        refreshProjectCombo();
    }

    private void refreshProjectCombo() {
        List<String> paths = new ArrayList<>(configManager.getDebugConfig().getProjectPaths());
        projectCombo.removeAllItems();
        for (String path : paths) {
            projectCombo.addItem(path);
        }
        String current = currentProjectPath();
        if (current != null && paths.contains(current)) {
            projectCombo.setSelectedItem(current);
        }
    }

    private String currentProjectPath() {
        String basePath = project.getBasePath();
        return StringUtil.isEmpty(basePath) ? null : basePath;
    }

    private String selectedProjectPath() {
        Object selected = projectCombo.getSelectedItem();
        return selected == null ? null : selected.toString();
    }

    private void addCurrentProject() {
        String path = currentProjectPath();
        if (path == null) {
            log("错误: 当前打开的不是有效项目目录");
            return;
        }
        List<String> paths = configManager.getDebugConfig().getProjectPaths();
        if (paths.contains(path)) {
            log("项目已在列表中: " + path);
            return;
        }
        paths.add(path);
        configManager.saveConfig();
        refreshProjectCombo();
        log("已添加项目: " + path);
    }

    private void removeSelectedProject() {
        String path = selectedProjectPath();
        if (path == null) {
            return;
        }
        configManager.getDebugConfig().getProjectPaths().remove(path);
        configManager.saveConfig();
        refreshProjectCombo();
        log("已移除项目: " + path);
    }

    private void runAction(com.localdebug.plugin.core.action.Action action, String title, boolean needConfirm) {
        String projectPath = selectedProjectPath();
        if (projectPath == null) {
            log("错误: 请先添加并选择 debug 项目");
            return;
        }
        Path projectDir = Paths.get(projectPath);
        if (!Files.exists(projectDir) || !Files.isDirectory(projectDir)) {
            log("错误: 项目目录不存在或不是有效目录: " + projectPath);
            return;
        }

        if (needConfirm) {
            int option = Messages.showOkCancelDialog(project,
                    "确认回滚项目配置？项目中的 application.yaml / bootstrap.yaml / pom.xml 将恢复到初始化前状态。\n项目路径: " + projectPath,
                    "回滚确认", Messages.getQuestionIcon());
            if (option != Messages.OK) {
                return;
            }
        }

        String projectBackPath = DirectoryUtil.getProjectBackPath(projectPath);
        ProgressManager.getInstance().run(new Task.Backgroundable(project, title, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                action.execute(projectPath, projectBackPath, MainToolWindowPanel.this::log);
            }

            @Override
            public void onFinished() {
                ApplicationManager.getApplication().invokeLater(MainToolWindowPanel.this::refreshProjectCombo);
            }
        });
    }

    private void log(String message) {
        ApplicationManager.getApplication().invokeLater(() -> {
            String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
            logArea.append("[" + timestamp + "] " + message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }
}
