package com.localdebug.plugin.ui;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.ui.components.JBBox;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

/**
 * 窄侧栏通用小部件：原生工具栏图标按钮行、垂直堆叠面板
 */
final class UiComponents {

    private UiComponents() {
    }

    /** 单个动作的图标按钮描述 */
    record IconButton(@NotNull Icon icon, @NotNull String tooltip, @NotNull Runnable action) {
    }

    /**
     * 生成一行原生工具栏图标按钮（ActionToolbar 渲染，自带 hover/主题/尺寸），固定宽不随窗口压缩。
     * place 仅作标识，可传任意字符串。
     */
    @NotNull
    static JComponent iconBar(String place, IconButton... buttons) {
        DefaultActionGroup group = new DefaultActionGroup();
        for (IconButton b : buttons) {
            group.add(new AnAction(b.tooltip(), b.tooltip(), b.icon()) {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e) {
                    b.action().run();
                }
            });
        }
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(place, group, true);
        return toolbar.getComponent();
    }

    /** 垂直堆叠面板（统一内边距，窄侧栏友好） */
    @NotNull
    static JBBox verticalPanel() {
        JBBox box = JBBox.createVerticalBox();
        box.setBorder(BorderFactory.createEmptyBorder(8, 10, 4, 10));
        return box;
    }
}
