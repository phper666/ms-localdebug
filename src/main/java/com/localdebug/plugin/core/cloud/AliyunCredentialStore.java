package com.localdebug.plugin.core.cloud;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import lombok.extern.slf4j.Slf4j;

/**
 * 阿里云 AK/SK 凭证存取（P2）：不再落明文 json，改存 IntelliJ PasswordSafe。
 * AK 存为 userName，SK 存为 password。
 */
@Slf4j
public class AliyunCredentialStore {

    private static final String SERVICE_NAME = "com.localdebug.plugin.aliyun";

    private AliyunCredentialStore() {
    }

    private static CredentialAttributes attributes() {
        return new CredentialAttributes(SERVICE_NAME);
    }

    public static String getAccessKey() {
        try {
            Credentials credentials = PasswordSafe.getInstance().get(attributes());
            return credentials != null ? credentials.getUserName() : null;
        } catch (Exception e) {
            log.error("读取阿里云 AccessKey 失败", e);
            return null;
        }
    }

    public static String getSecretKey() {
        try {
            Credentials credentials = PasswordSafe.getInstance().get(attributes());
            return credentials != null ? credentials.getPasswordAsString() : null;
        } catch (Exception e) {
            log.error("读取阿里云 SecretKey 失败", e);
            return null;
        }
    }

    public static void setCredentials(String accessKey, String secretKey) {
        try {
            if (accessKey == null || accessKey.isEmpty()) {
                PasswordSafe.getInstance().set(attributes(), null);
            } else {
                PasswordSafe.getInstance().set(attributes(), new Credentials(accessKey, (secretKey == null ? "" : secretKey).toCharArray()));
            }
        } catch (Exception e) {
            log.error("保存阿里云凭证失败", e);
        }
    }
}
