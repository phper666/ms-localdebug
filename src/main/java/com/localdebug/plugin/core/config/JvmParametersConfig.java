package com.localdebug.plugin.core.config;

import lombok.Data;

import java.util.Map;

/**
 * @author yuzhao.li
 * @name JvmParameters
 * @date 2024/1/7
 * @filename JvmParameters.java
 **/
@Data
public class JvmParametersConfig {
    private String mainClass;
    private String jvmArgs;
    private Map<String, String> jvmEnvs;
}
