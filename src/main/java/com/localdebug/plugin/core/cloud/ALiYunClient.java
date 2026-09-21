package com.localdebug.plugin.core.cloud;

import com.aliyun.sts20150401.Client;
import com.aliyun.sts20150401.models.AssumeRoleResponse;
import com.aliyun.sts20150401.models.AssumeRoleResponseBody;
import com.aliyun.tea.TeaException;
import lombok.extern.slf4j.Slf4j;

/**
 * 阿里云客户端：仅保留 STS 临时凭证获取能力（OSS 相关能力不入插件）
 */
@Slf4j
public class ALiYunClient {

    /**
     * 获取临时凭证
     *
     * @param accessKeyId     Access Key ID
     * @param accessKeySecret Access Key Secret
     * @param durationSeconds 临时凭证有效时间（秒）
     * @param roleArn         角色ARN
     * @param roleSessionName 角色会话名称
     * @return 临时凭证信息
     */
    public static AssumeRoleResponseBody.AssumeRoleResponseBodyCredentials getAssumeRoleCredentials(String accessKeyId, String accessKeySecret, long durationSeconds, String roleArn, String roleSessionName) throws Exception {
        // 工程代码建议使用更安全的无 AK 方式，凭据配置方式请参见：https://help.aliyun.com/document_detail/378657.html。
        com.aliyun.credentials.Client credential = new com.aliyun.credentials.Client();
        com.aliyun.teaopenapi.models.Config config = new com.aliyun.teaopenapi.models.Config()
                .setCredential(credential);
        // Endpoint 请参考 https://api.aliyun.com/product/Sts
        config.endpoint = "sts.cn-hangzhou.aliyuncs.com";
        config.setAccessKeyId(accessKeyId);
        config.setAccessKeySecret(accessKeySecret);
        Client client = new Client(config);

        com.aliyun.sts20150401.models.AssumeRoleRequest assumeRoleRequest = new com.aliyun.sts20150401.models.AssumeRoleRequest()
                .setDurationSeconds(durationSeconds)
                .setRoleArn(roleArn)
                .setRoleSessionName(roleSessionName);
        try {
            AssumeRoleResponse assumeRoleResponse = client.assumeRoleWithOptions(assumeRoleRequest, new com.aliyun.teautil.models.RuntimeOptions());
            return assumeRoleResponse.getBody().getCredentials();
        } catch (TeaException error) {
            log.error(error.getMessage());
            log.error(String.valueOf(error.getData().get("Recommend")));
            com.aliyun.teautil.Common.assertAsString(error.message);
        } catch (Exception e) {
            TeaException error = new TeaException(e.getMessage(), e);
            log.error(error.getMessage());
            log.error(String.valueOf(error.getData().get("Recommend")));
            com.aliyun.teautil.Common.assertAsString(error.message);
        }
        return null;
    }
}
