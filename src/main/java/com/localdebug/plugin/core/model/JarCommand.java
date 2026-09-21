package com.localdebug.plugin.core.model;

import lombok.Data;

/**
 * Jar命令模型类
 * 用于存储用户定义的jar命令
 *
 * @author yuzhao.li
 */
@Data
public class JarCommand {
    /**
     * 命令名称
     */
    private String name;
    
    /**
     * 命令内容
     */
    private String command;
    
    /**
     * 关联的jar文件名
     */
    private String jarFileName;
    
    // 显式添加getter方法
    public String getName() {
        return name;
    }
    
    public String getCommand() {
        return command;
    }
    
    public String getJarFileName() {
        return jarFileName;
    }
    
    // 显式添加setter方法
    public void setName(String name) {
        this.name = name;
    }
    
    public void setCommand(String command) {
        this.command = command;
    }
    
    public void setJarFileName(String jarFileName) {
        this.jarFileName = jarFileName;
    }
}