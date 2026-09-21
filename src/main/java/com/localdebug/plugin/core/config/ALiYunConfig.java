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
     * 验证配置是否完整，所有必要属性都不为空
     *
     * @return 如果所有属性都不为空返回true，否则返回false
     */
    public boolean isValidConfig() {
        // 检查基本字符串属性
        if (ObjectUtil.isEmpty(nacosServerAddr) ||
                ObjectUtil.isEmpty(ramRole) ||
                ObjectUtil.isEmpty(mqAddr) ||
                ObjectUtil.isEmpty(roleArn) ||
                ObjectUtil.isEmpty(accessKey) ||
                ObjectUtil.isEmpty(secretKey)) {
            return false;
        }

        // 检查MQ消费者列表
        if (ObjectUtil.isEmpty(mqConsumers)) {
            return false;
        }

        // 检查每个MQ消费者的groupId
        for (MqConsumer consumer : mqConsumers) {
            if (ObjectUtil.isEmpty(consumer) || ObjectUtil.isEmpty(consumer.getGroupId())) {
                return false;
            }
        }

        return true;
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