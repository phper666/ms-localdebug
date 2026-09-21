package com.localdebug.plugin.ui;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBBox;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.localdebug.plugin.core.config.ConfigManager;
import com.localdebug.plugin.core.util.ToolInstaller;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 工具安装区：一键安装 kubectl / ktctl（下载地址取全局 config.json 配置）
 *
 * 布局约束：整个面板 BorderLayout；NORTH 槽 = 按钮区（首选高，绝不垂直拉伸）；
 * CENTER 槽 = 输出区占满剩余空间。
 */
public class ToolsPanel extends JPanel {

    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Project project;
    private final ConfigManager configManager = new ConfigManager();
    private final JBTextArea logArea = new JBTextArea();

    public ToolsPanel(Project project) {
        this.project = project;
        setLayout(new BorderLayout());

        // NORTH：安装按钮垂直堆叠（窄侧栏下两按钮横排会溢出；NORTH 槽 = 首选高，按钮不被拉伸）
        JBBox controls = UiComponents.verticalPanel();
        JButton kubectlButton = new JButton("安装 kubectl");
        kubectlButton.addActionListener(e -> runInstall("kubectl", false));
        kubectlButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        controls.add(kubectlButton);
        controls.add(Box.createVerticalStrut(6));
        JButton ktctlButton = new JButton("安装 ktctl (ktconnect)");
        ktctlButton.addActionListener(e -> runInstall("ktctl", true));
        ktctlButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        controls.add(ktctlButton);
        add(controls, BorderLayout.NORTH);

        // CENTER：输出区占满剩余空间
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        JBScrollPane scrollPane = new JBScrollPane(logArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder("输出"));
        add(scrollPane, BorderLayout.CENTER);
    }

    private void runInstall(String toolName, boolean ktconnect) {
        String downloadUrl;
        if (ktconnect) {
            ConfigManager.KtconnectConfig config = configManager.getKtconnectConfig();
            downloadUrl = config == null ? null : config.getDownloadUrl();
        } else {
            ConfigManager.KubectlConfig config = configManager.getKubectlConfig();
            downloadUrl = config == null ? null : config.getDownloadUrl();
        }
        if (downloadUrl == null || downloadUrl.isEmpty()) {
            log("错误: 未配置 " + toolName + " 下载地址，请检查 ~/.localdebug/config/config.json");
            return;
        }

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "LocalDebug 安装 " + toolName, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                if (ktconnect) {
                    ToolInstaller.installKtconnect(downloadUrl, ToolsPanel.this::log);
                } else {
                    ToolInstaller.installKubectl(downloadUrl, ToolsPanel.this::log);
                }
            }
        });
    }

    private void log(String message) {
        SwingUtilities.invokeLater(() -> {
            String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
            logArea.append("[" + timestamp + "] " + message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }
}
