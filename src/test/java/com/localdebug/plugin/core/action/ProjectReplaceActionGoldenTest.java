package com.localdebug.plugin.core.action;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.localdebug.plugin.core.config.ALiYunConfig;
import com.localdebug.plugin.core.config.JvmParametersConfig;
import com.localdebug.plugin.core.config.ReplaceConfig;
import com.localdebug.plugin.core.constant.Constants;
import com.localdebug.plugin.core.engine.DescriptorLoader;
import com.localdebug.plugin.core.model.StackDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P1 批次2 golden 回归：ktctl 命令文件 / jvmrc 生成结果 == 改造前 ProjectReplaceAction 硬编码字符串。
 * 内置默认栈 sca-dubbo-ktctl，上下文 = 典型项目（core-bridge-service / dev1 / yuzhao-li）。
 */
class ProjectReplaceActionGoldenTest {

    private final DescriptorLoader loader = new DescriptorLoader();
    private StackDescriptor builtin;

    @BeforeEach
    void setUp() {
        builtin = loader.load(null);
    }

    private ReplaceConfig replaceConfig(String serviceType) {
        ReplaceConfig rc = new ReplaceConfig();
        rc.setAppName("core-bridge-service");
        rc.setAppMainClass("com.phper666.CoreBridgeApplication");
        rc.setTag("yuzhao-li");
        rc.setK8sServiceName("core-bridge-service");
        rc.setK8sServiceNamespace("dev1");
        rc.setBffWebServerPort(8081);
        rc.setRpcWebServerPort(8082);
        rc.setServiceType(serviceType);
        return rc;
    }

    @Test
    void golden_ktctlCmds_bff_linesEqualOldHardcoded() {
        List<String> lines = ProjectReplaceAction.buildKtctlCmdFileLines(builtin, replaceConfig(Constants.BFF), "dev1");

        assertEquals(List.of(
                "# enable proxy, -d 显示调试日志, -n value 指定目标服务的Kubernetes Namespace",
                "##ktconnect代理###core-bridge-service",
                "ktctl -d -n dev1 connect",
                "\r\n",
                "# proxy http request, ktctl mesh <目标服务名> --expose <本地端口>:<目标服务端口>",
                "##接口流量拦截代理###core-bridge-service",
                "ktctl -d -n dev1 mesh core-bridge-service --expose 8081:8080 --versionMark tag:yuzhao-li",
                "\r\n",
                "# proxy rpc request, ktctl preview <新建服务名> --expose <本地端口>:<新建服务端口> ",
                "##rpc流量拦截代理###core-bridge-service",
                "ktctl -d -n dev1 preview core-bridge-service-yuzhao-li --expose 20880:20880",
                "\r\n",
                "# ktctl -d -n [命名空间] recover [服务名]",
                "##清理流量拦截###core-bridge-service",
                "ktctl -d -n dev1 recover core-bridge-service ",
                "\r\n",
                "# ktctl -d -n [命名空间] clean",
                "##清理集群里已超期KT代理容器###core-bridge-service",
                "ktctl -d -n dev1 clean ",
                "\r\n"
        ), lines);
    }

    @Test
    void golden_ktctlCmds_rpc_linesEqualOldHardcoded() {
        List<String> lines = ProjectReplaceAction.buildKtctlCmdFileLines(builtin, replaceConfig(Constants.RPC), "dev1");

        assertEquals(List.of(
                "# enable proxy, -d 显示调试日志, -n value 指定目标服务的Kubernetes Namespace",
                "##ktconnect代理###core-bridge-service",
                "ktctl -d -n dev1 connect",
                "\r\n",
                "# proxy rpc request, ktctl preview <新建服务名> --expose <本地端口>:<新建服务端口> ",
                "##rpc流量拦截代理###core-bridge-service",
                "ktctl -d -n dev1 preview core-bridge-service-yuzhao-li --expose 20880:20880",
                "\r\n",
                "# ktctl -d -n [命名空间] recover [服务名]",
                "##清理流量拦截###core-bridge-service",
                "ktctl -d -n dev1 recover core-bridge-service ",
                "\r\n",
                "# ktctl -d -n [命名空间] clean",
                "##清理集群里已超期KT代理容器###core-bridge-service",
                "ktctl -d -n dev1 clean ",
                "\r\n"
        ), lines);
    }

    @Test
    void golden_jvmrc_jvmArgsEqualOldFormat() {
        ALiYunConfig aliYunConfig = new ALiYunConfig();
        aliYunConfig.setNacosServerAddr("http://nacos.local:8848");
        aliYunConfig.setMqAddr("mq_public:9876");
        String baseJvmArgs = "-DsocksProxyHost=127.0.0.1 -DsocksProxyPort=2223 ";

        List<JvmParametersConfig> list = ProjectReplaceAction.buildJvmParametersList(
                builtin, List.of(replaceConfig(Constants.BFF)), baseJvmArgs, aliYunConfig);

        String expectedJvmArgs = "-DsocksProxyHost=127.0.0.1 -DsocksProxyPort=2223 "
                + " -Dhttp.nonProxyHosts=nacos.local|nacos.local|mq_public|mq_public|mq-public|mq-public"
                + " -DDUBBO_IP_TO_REGISTRY=core-bridge-service-yuzhao-li.dev1.svc.cluster.local";
        assertEquals("com.phper666.CoreBridgeApplication", list.get(0).getMainClass());
        assertEquals(expectedJvmArgs, list.get(0).getJvmArgs());

        // 文件形状：JSONArray 单元素，字段与旧 createRunApplicationAgentFile 输出一致
        String json = JSONUtil.toJsonStr(JSONUtil.parseArray(list), 2);
        JSONArray parsed = JSONUtil.parseArray(json);
        assertEquals(1, parsed.size());
        assertEquals("com.phper666.CoreBridgeApplication", parsed.getJSONObject(0).getStr("mainClass"));
        assertEquals(expectedJvmArgs, parsed.getJSONObject(0).getStr("jvmArgs"));
    }

    @Test
    void golden_jvmrc_mqEmpty_skipsMqVariants() {
        ALiYunConfig aliYunConfig = new ALiYunConfig();
        aliYunConfig.setNacosServerAddr("http://nacos.local:8848");
        // mqAddr 未配置

        String jvmArgs = ProjectReplaceAction.buildJvmArgs(builtin, replaceConfig(Constants.BFF),
                "-DsocksProxyHost=127.0.0.1 -DsocksProxyPort=2223 ", aliYunConfig);

        assertEquals("-DsocksProxyHost=127.0.0.1 -DsocksProxyPort=2223 "
                + " -Dhttp.nonProxyHosts=nacos.local|nacos.local"
                + " -DDUBBO_IP_TO_REGISTRY=core-bridge-service-yuzhao-li.dev1.svc.cluster.local", jvmArgs);
    }

    @Test
    void golden_serviceTypeRules_dataDrivenMatchesOldLogic() {
        // 旧逻辑路径（business/storefront → bff；engagement 特判 → bff；internal-console → bff；
        // shared-service 特判 → bff；toolset-console → bff；已知命名空间 → rpc；未知 → prompt）
        assertEquals(Constants.BFF, ProjectInitAction.evaluateServiceType(ProjectInitAction.defaultProjectRules(), "business-x", "app", "/proj"));
        assertEquals(Constants.BFF, ProjectInitAction.evaluateServiceType(ProjectInitAction.defaultProjectRules(), "storefront-x", "app", "/proj"));
        assertEquals(Constants.BFF, ProjectInitAction.evaluateServiceType(ProjectInitAction.defaultProjectRules(), "engagement-dev1", "app", "/proj/messaging-edge-service-parent/"));
        assertEquals(Constants.BFF, ProjectInitAction.evaluateServiceType(ProjectInitAction.defaultProjectRules(), "engagement-dev1", "app", "/proj/voice-service/"));
        assertEquals(Constants.RPC, ProjectInitAction.evaluateServiceType(ProjectInitAction.defaultProjectRules(), "engagement-dev1", "app", "/proj/other/"));
        assertEquals(Constants.BFF, ProjectInitAction.evaluateServiceType(ProjectInitAction.defaultProjectRules(), "internal-dev1", "internal-console", "/proj"));
        assertEquals(Constants.RPC, ProjectInitAction.evaluateServiceType(ProjectInitAction.defaultProjectRules(), "internal-dev1", "other-app", "/proj"));
        assertEquals(Constants.BFF, ProjectInitAction.evaluateServiceType(ProjectInitAction.defaultProjectRules(), "shared-service-dev1", "app", "/proj/callback-gateway/"));
        assertEquals(Constants.RPC, ProjectInitAction.evaluateServiceType(ProjectInitAction.defaultProjectRules(), "shared-service-dev1", "app", "/proj/other/"));
        assertEquals(Constants.BFF, ProjectInitAction.evaluateServiceType(ProjectInitAction.defaultProjectRules(), "intranet-dev1", "toolset-console", "/proj"));
        assertEquals(Constants.RPC, ProjectInitAction.evaluateServiceType(ProjectInitAction.defaultProjectRules(), "intranet-dev1", "other-app", "/proj"));
        // 完全不认识的命名空间 → prompt 提示（旧行为）
        String prompt = Constants.BFF + " or " + Constants.RPC + "? 需要个人根据项目type选择一下";
        assertEquals(prompt, ProjectInitAction.evaluateServiceType(ProjectInitAction.defaultProjectRules(), "unknown-ns", "app", "/proj"));
        assertEquals(prompt, ProjectInitAction.evaluateServiceType(ProjectInitAction.defaultProjectRules(), "totally-unknown", "app", "/proj"));
    }
}
