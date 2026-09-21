package com.localdebug.plugin.core.config;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 阿里云临时凭证配置类
 * 用于存储通过阿里云接口获取的临时AK/SK凭证
 *
 * TODO(P3): STS 临时凭证为短时效（默认 8h）仍以 JSON 落盘于 ~/.localdebug/config/aliyun_temp_credentials.json，如需更高安全性可迁移到 IntelliJ PasswordSafe
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ALiYunTemporaryCredentials {
    // 临时访问密钥ID
    private String stsAccessKey = "";
    
    // 临时秘密访问密钥
    private String stsSecretKey = "";
    
    // 安全令牌
    private String stsSecurityToken = "";
    
    // 临时凭证过期时间
    private String stsTokenExpiration = "";
}