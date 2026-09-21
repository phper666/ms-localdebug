package com.localdebug.plugin.core.bridge;

import java.util.function.Consumer;

/**
 * 流量通路抽象（L3 预留接缝，TECH-DESIGN §3 / §8）。
 * 实现① KtConnectBridge（信号文件停止语义）；未来 TelepresenceBridge / DirectBridge / 非 K8s 通路。
 * 内核（描述符/校验/audit/向导）不随通路变。
 */
public interface ProxyBridge {

    /**
     * 启动代理命令（若同名命令已在运行则先停止再重启）
     *
     * @param name    命令名（进程注册键，UI 展示用）
     * @param command 完整命令字符串（内部按 OS 用 sh -c / cmd /c 整串执行，不做空白切分）
     * @param workDir 工作目录
     * @param logger  日志回调
     */
    void start(String name, String command, String workDir, Consumer<String> logger);

    /**
     * 停止代理：优先信号文件（ktctl 语义），失败则 destroy；超时强制终止
     */
    void stop(String name, Consumer<String> logger);

    /**
     * 指定命令是否运行中
     */
    boolean isRunning(String name);

    /**
     * 是否有运行中的进程
     */
    boolean hasRunningProcesses();

    /**
     * 运行中进程信息（供 UI 查询）
     */
    java.util.List<String> getRunningProcessesInfo();

    /**
     * 停止全部运行中进程（项目关闭/服务 dispose 时调用）
     */
    void stopAll();
}
