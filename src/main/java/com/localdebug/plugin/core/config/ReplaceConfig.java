package com.localdebug.plugin.core.config;

import cn.hutool.json.JSONObject;
import lombok.Data;

/**
 * @author yuzhao.li
 * @name ReplaceConfig
 * @date 2023/12/29
 * @filename ReplaceConfig.java
 **/
@Data
public class ReplaceConfig {
    private String appName;
    private String appMainClass;
    private String rNacosGroup = "localdebug";
    private String nacosGroup;
    private String nacosDataId;
    private String nacosNamespaceId;
    private String tag;
    private String configDir;
    private String k8sServiceNamespace;
    private String k8sServiceName;
    private String serviceType;
    private int bffWebServerPort;
    private int rpcWebServerPort;

    private JSONObject projectBootstrapConfig;
    private JSONObject projectApplicationConfig;
}
