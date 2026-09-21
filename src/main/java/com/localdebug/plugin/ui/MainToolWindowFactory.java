package com.localdebug.plugin.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

/**
 * 主 ToolWindow 工厂：项目配置 / 调试命令 / 工具安装 三个分区
 */
public class MainToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        JTabbedPane tabbedPane = new JBTabbedPane();

        tabbedPane.addTab("项目配置", new MainToolWindowPanel(project));
        tabbedPane.addTab("调试命令", new DebugCommandsPanel(project));
        tabbedPane.addTab("工具安装", new ToolsPanel(project));

        JPanel root = new JPanel(new BorderLayout());
        root.add(tabbedPane, BorderLayout.CENTER);

        ContentFactory contentFactory = ContentFactory.getInstance();
        Content content = contentFactory.createContent(root, "", false);
        toolWindow.getContentManager().addContent(content);
    }
}
