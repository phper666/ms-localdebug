package com.localdebug.plugin.core.model;

import lombok.Data;

/**
 * @author yuzhao.li
 * @date 2025/09/19 16:53
 */
@Data
public class Dependency {
    private String groupId;
    private String artifactId;
    private String version;
    private String scope;
}
