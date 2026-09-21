package com.localdebug.plugin.core.config;

import cn.hutool.core.util.ObjectUtil;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * @author yuzhao.li
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ALiYunConfig {
    private String nacosServerAddr;
    private String ramRole;
    private String roleArn;
    private String mqAddr;
    private String accessKey = "";
    private String secretKey = "";
    // 临时授权时长，单位秒
    private long stsAkSkDurationSeconds = 28800;
    private String schedulerNamespace = "";
    private String schedulerGroupId = "";
    private String schedulerAppKey = "";
    private List<MqConsumer> mqConsumers = new ArrayList<>() {
        {
            add(new MqConsumer());
        }
    };
    private List<Producers> producers = new ArrayList<>();

    /**
     * 验证配置是否有效：仅 nacosServerAddr 必填，其余字段可选
     * （可选字段缺失时由调用方跳过对应功能并记录日志，见 isValidConfig 调用点）
     *
     * @return nacosServerAddr 非空返回 true，否则返回 false
     */
    public boolean isValidConfig() {
        return !ObjectUtil.isEmpty(nacosServerAddr);
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class MqConsumer {
        private String groupId = "";
        private List<MqTopic> topics = new ArrayList<>();
    }
    
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class MqTopic {
        private String topic;
        private String handler;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Producers {
        private String name;
        private String addr;
        private String ramRole;
    }
}