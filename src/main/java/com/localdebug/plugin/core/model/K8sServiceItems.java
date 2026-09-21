package com.localdebug.plugin.core.model;

import lombok.Data;

/**
 * @author yuzhao.li
 **/
@Data
public class K8sServiceItems {
    private Metadata metadata;

    @Data
    public static class Metadata {
        private String name;
        private String namespace;
    }
}
