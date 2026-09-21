package com.localdebug.plugin.core.config;

import com.localdebug.plugin.core.model.Dependency;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class LocalDebugConfig {
    private String jvmArgs;
    /**
     * 链路 tag 覆盖项：非空时优先于 OS 用户名作为 debug tag（默认仍取 OS 用户名）
     */
    private String debugTag;
    private List<Dependency> updateDependencies = new ArrayList<>();
    private List<Dependency> addDependencies = new ArrayList<>();
    private List<ReplaceConfig> replaceConfigs;
    private ALiYunConfig aliYunConfig;
}
