package com.localdebug.plugin.ui.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.Configurable;
import com.localdebug.plugin.core.cloud.AliyunCredentialStore;
import com.localdebug.plugin.core.config.ALiYunConfig;
import com.localdebug.plugin.core.util.DirectoryUtil;
import com.localdebug.plugin.core.util.InternalFileUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * LocalDebug 设置页：阿里云配置（AK/SK 存 PasswordSafe，不落明文）+ K8s kubeconfig
 * <p>
 * TODO(P3): 全局配置导入/导出暂缓（YAGNI）
 */
public class LocalDebugSettingsConfigurable implements Configurable {

    private static final String KUBE_CONFIG_PATH = Paths.get(System.getProperty("user.home"), ".kube", "config").toString();

    private JTextField nacosServerAddrField;
    private JTextField ramRoleField;
    private JTextField roleArnField;
    private JTextField mqAddrField;
    private JTextField stsDurationField;
    private JTextField schedulerNamespaceField;
    private JTextField schedulerGroupIdField;
    private JTextField schedulerAppKeyField;
    private JPasswordField accessKeyField;
    private JPasswordField secretKeyField;

    private JTextArea kubeConfigArea;
    private JComponent mainPanel;

    private volatile boolean modified = false;

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "LocalDebug 微服务调试";
    }

    @Override
    public JComponent createComponent() {
        mainPanel = new JPanel(new BorderLayout());
        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.addTab("阿里云配置", createAliyunPanel());
        tabbedPane.addTab("K8s 配置", createK8sPanel());
        mainPanel.add(tabbedPane, BorderLayout.CENTER);
        reset();
        return mainPanel;
    }

    private JPanel createAliyunPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        nacosServerAddrField = new JTextField(30);
        ramRoleField = new JTextField(30);
        roleArnField = new JTextField(30);
        mqAddrField = new JTextField(30);
        stsDurationField = new JTextField(10);
        schedulerNamespaceField = new JTextField(30);
        schedulerGroupIdField = new JTextField(30);
        schedulerAppKeyField = new JTextField(30);
        accessKeyField = new JPasswordField(30);
        secretKeyField = new JPasswordField(30);

        int row = 0;
        row = addField(panel, gbc, row, "Nacos 地址:", nacosServerAddrField);
        row = addField(panel, gbc, row, "Ram Role:", ramRoleField);
        row = addField(panel, gbc, row, "Role ARN:", roleArnField);
        row = addField(panel, gbc, row, "RocketMQ 地址:", mqAddrField);
        row = addField(panel, gbc, row, "STS 时长(秒):", stsDurationField);
        row = addField(panel, gbc, row, "Scheduler Namespace:", schedulerNamespaceField);
        row = addField(panel, gbc, row, "Scheduler Group ID:", schedulerGroupIdField);
        row = addField(panel, gbc, row, "Scheduler App Key:", schedulerAppKeyField);
        row = addField(panel, gbc, row, "AccessKey (PasswordSafe):", accessKeyField);
        row = addField(panel, gbc, row, "SecretKey (PasswordSafe):", secretKeyField);

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        panel.add(new JLabel("<html>AK/SK 保存在 IntelliJ PasswordSafe 中，不落明文文件。<br/>"
                + "RocketMQ 消费者/生产者等列表配置暂未在 UI 提供，可编辑 ~/.localdebug/config/aliyun_config.json</html>"), gbc);

        // 输入即标记修改
        java.awt.event.KeyListener dirtyListener = new java.awt.event.KeyAdapter() {
            @Override
            public void keyTyped(java.awt.event.KeyEvent e) {
                modified = true;
            }
        };
        JTextField[] fields = {nacosServerAddrField, ramRoleField, roleArnField, mqAddrField,
                stsDurationField, schedulerNamespaceField, schedulerGroupIdField, schedulerAppKeyField};
        for (JTextField field : fields) {
            field.addKeyListener(dirtyListener);
        }
        accessKeyField.addKeyListener(dirtyListener);
        secretKeyField.addKeyListener(dirtyListener);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(panel, BorderLayout.NORTH);
        return wrapper;
    }

    private int addField(JPanel panel, GridBagConstraints gbc, int row, String label, JComponent field) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0;
        panel.add(new JLabel(label), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        panel.add(field, gbc);
        return row + 1;
    }

    private JPanel createK8sPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 5));

        kubeConfigArea = new JTextArea(20, 60);
        kubeConfigArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(kubeConfigArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder("kubeconfig (" + KUBE_CONFIG_PATH + ")"));
        panel.add(scrollPane, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton saveButton = new JButton("保存 kubeconfig");
        saveButton.addActionListener(e -> saveKubeConfig());
        buttonPanel.add(saveButton);
        JButton testButton = new JButton("测试连接");
        testButton.addActionListener(e -> testK8sConnection());
        buttonPanel.add(testButton);
        panel.add(buttonPanel, BorderLayout.SOUTH);

        return panel;
    }

    @Override
    public boolean isModified() {
        return modified;
    }

    @Override
    public void apply() {
        ALiYunConfig config = InternalFileUtil.getGlobalProjectMqConfig();
        config.setNacosServerAddr(nacosServerAddrField.getText().trim());
        config.setRamRole(ramRoleField.getText().trim());
        config.setRoleArn(roleArnField.getText().trim());
        config.setMqAddr(mqAddrField.getText().trim());
        try {
            config.setStsAkSkDurationSeconds(Long.parseLong(stsDurationField.getText().trim()));
        } catch (NumberFormatException ignored) {
            // 保留原值
        }
        config.setSchedulerNamespace(schedulerNamespaceField.getText().trim());
        config.setSchedulerGroupId(schedulerGroupIdField.getText().trim());
        config.setSchedulerAppKey(schedulerAppKeyField.getText().trim());
        config.setAccessKey(new String(accessKeyField.getPassword()));
        config.setSecretKey(new String(secretKeyField.getPassword()));

        InternalFileUtil.saveGlobalProjectMqConfig(config);
        modified = false;
    }

    @Override
    public void reset() {
        ALiYunConfig config = InternalFileUtil.getGlobalProjectMqConfig();
        if (nacosServerAddrField != null) {
            nacosServerAddrField.setText(config.getNacosServerAddr());
            ramRoleField.setText(config.getRamRole());
            roleArnField.setText(config.getRoleArn());
            mqAddrField.setText(config.getMqAddr());
            stsDurationField.setText(String.valueOf(config.getStsAkSkDurationSeconds()));
            schedulerNamespaceField.setText(config.getSchedulerNamespace());
            schedulerGroupIdField.setText(config.getSchedulerGroupId());
            schedulerAppKeyField.setText(config.getSchedulerAppKey());
            accessKeyField.setText(AliyunCredentialStore.getAccessKey() == null ? "" : AliyunCredentialStore.getAccessKey());
            secretKeyField.setText(AliyunCredentialStore.getSecretKey() == null ? "" : AliyunCredentialStore.getSecretKey());
            kubeConfigArea.setText(readKubeConfig());
            modified = false;
        }
    }

    private String readKubeConfig() {
        Path path = Paths.get(KUBE_CONFIG_PATH);
        if (!Files.exists(path)) {
            return "# Kubernetes 配置文件不存在，请添加配置\n";
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "# 无法读取配置文件: " + e.getMessage() + "\n";
        }
    }

    private void saveKubeConfig() {
        try {
            Path path = Paths.get(KUBE_CONFIG_PATH);
            Files.createDirectories(path.getParent());
            Files.writeString(path, kubeConfigArea.getText(), StandardCharsets.UTF_8);
            JOptionPane.showMessageDialog(mainPanel, "Kubernetes 配置已保存到: " + path, "保存成功", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(mainPanel, "保存失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void testK8sConnection() {
        kubeConfigArea.setEditable(false);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String result;
            try {
                ProcessBuilder processBuilder = new ProcessBuilder("kubectl", "get", "pods", "--request-timeout=5s");
                Process process = processBuilder.start();
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder output = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
                StringBuilder errorOutput = new StringBuilder();
                java.io.BufferedReader errorReader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));
                while ((line = errorReader.readLine()) != null) {
                    errorOutput.append(line).append("\n");
                }
                process.waitFor();
                result = process.exitValue() == 0
                        ? "Kubernetes 连接成功:\n" + output
                        : "Kubernetes 连接失败:\n" + errorOutput;
            } catch (Exception ex) {
                result = "测试连接时发生错误: " + ex.getMessage();
            }
            String finalResult = result;
            ApplicationManager.getApplication().invokeLater(() -> {
                kubeConfigArea.setEditable(true);
                JOptionPane.showMessageDialog(mainPanel, finalResult, "Kubernetes 连接测试结果", JOptionPane.INFORMATION_MESSAGE);
            });
        });
    }
}
