package com.localdebug.plugin.core.config;

import cn.hutool.core.annotation.Alias;
import lombok.Data;

/**
 * @author yuzhao.li
 * @name BootstrapConfig
 * @date 2023/12/28
 * @filename BootstrapConfig.java
 **/
@Data
public class SpringBootConfig {
    private Application application;
    private Cloud cloud;
    private Config config;

    @Data
    public static class Application {
        private String name;
    }

    @Data
    public static class Cloud {
        private Nacos nacos;
    }

    @Data
    public static class Nacos {
        private NacosConfig config;
    }

    @Data
    public static class Config {
        @Alias("import")
        private String importValue;
    }

    @Data
    public static class NacosConfig {
        @Alias("file-extension")
        private String fileExtension;
        @Alias("data-id")
        private String dataId;
        private String group;
        private String namespace;
        @Alias("server-addr")
        private String serverAddr;
    }
}
