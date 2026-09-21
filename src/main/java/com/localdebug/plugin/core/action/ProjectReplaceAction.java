package com.localdebug.plugin.core.action;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.CharsetUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import com.localdebug.plugin.core.config.ALiYunConfig;
import com.localdebug.plugin.core.config.JvmParametersConfig;
import com.localdebug.plugin.core.config.LocalDebugConfig;
import com.localdebug.plugin.core.config.ReplaceConfig;
import com.localdebug.plugin.core.constant.Constants;
import com.localdebug.plugin.core.model.Dependency;
import com.localdebug.plugin.core.model.FilePath;
import com.localdebug.plugin.core.util.InternalFileUtil;
import com.localdebug.plugin.core.util.JsonFileUtil;
import com.localdebug.plugin.core.util.LogOutputUtil;
import com.localdebug.plugin.core.maven.MavenUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;

import static com.localdebug.plugin.core.constant.Constants.DEFAULT_DUBBO_PORT;
import static com.localdebug.plugin.core.constant.Constants.QUERY_APPLICATION_YAML;
import static com.localdebug.plugin.core.constant.Constants.QUERY_BOOTSTRAP_YAML;

/**
 * 替换配置操作类
 * 处理项目替换配置相关的功能
 */
@Slf4j
public class ProjectReplaceAction implements Action {

    /**
     * 执行替换配置操作
     *
     * @param projectPath     项目路径
     * @param projectBackPath 项目备份路径
     * @param logger          日志输出回调
     */
    @Override
    public void execute(String projectPath, String projectBackPath, Consumer<String> logger) {
        LogOutputUtil.log(logger, "开始执行替换配置操作...");
        // 检查项目目录是否存在
        File projectDir = new File(projectPath);
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            LogOutputUtil.error(logger, "项目目录不存在或不是有效目录");
            return;
        }

        ALiYunConfig aliYunConfig = InternalFileUtil.getGlobalProjectMqConfig();
        if (!aliYunConfig.isValidConfig()) {
            LogOutputUtil.error(logger, "nacos 地址未配置，请先填写阿里云配置信息");
            return;
        }

        String projectLocalDebugConfigPath = projectPath + File.separator + Constants.LOCAL_DEBUG_CONFIG_NAME;
        LocalDebugConfig localDebugConfig;
        try {
            localDebugConfig = InternalFileUtil.getLocalDebugConfig(projectLocalDebugConfigPath);
        } catch (Exception e) {
            LogOutputUtil.error(logger, "替换配置出错: " + e.getMessage());
            return;
        }

        if (ObjectUtil.isEmpty(aliYunConfig.getNacosServerAddr())) {
            LogOutputUtil.error(logger, "nacos地址不存在，请先填写nacos地址");
            return;
        }

        if (ObjectUtil.isEmpty(localDebugConfig.getReplaceConfigs())) {
            LogOutputUtil.error(logger, "初始化配置不完整, 请重新运行初始化配置");
            return;
        }

        if (!JsonFileUtil.checkJsonFileHasData(projectPath + File.separator + MavenUtil.getPomChangeInfoConfigName())) {
            // 替换父pom.xml的一些依赖信息
            List<Dependency> toUpdate = new ArrayList<>();
            List<Dependency> toAdd = new ArrayList<>();
            localDebugConfig.getAddDependencies().forEach(dependency -> {
                Dependency addDependency = new Dependency();
                addDependency.setGroupId(dependency.getGroupId());
                addDependency.setArtifactId(dependency.getArtifactId());
                addDependency.setVersion(dependency.getVersion());
                toAdd.add(addDependency);
            });

            localDebugConfig.getUpdateDependencies().forEach(dependency -> {
                Dependency updateDependency = new Dependency();
                updateDependency.setGroupId(dependency.getGroupId());
                updateDependency.setArtifactId(dependency.getArtifactId());
                updateDependency.setVersion(dependency.getVersion());
                toUpdate.add(updateDependency);
            });

            try {
                String pomPath = projectPath + File.separator + "pom.xml";
                MavenUtil.batchUpdateAndAddDependencies(pomPath, toUpdate, toAdd);
            } catch (Exception e) {
                LogOutputUtil.error(logger, "替换配置出错: " + e.getMessage());
                return;
            }
        }

        // 读取所有bootstrap配置，获取dataId和group
        String rollbackJsonFilePath = projectBackPath + File.separator + Constants.ROLLBACK_FILE_PATHS_NAME;
        List<FilePath> rollbackJsonFilePaths = JSONUtil.readJSONArray(new File(rollbackJsonFilePath), CharsetUtil.CHARSET_UTF_8).toList(FilePath.class);
        for (FilePath rollbackJsonFilePathDO : rollbackJsonFilePaths) {
            for (ReplaceConfig replaceConfig : localDebugConfig.getReplaceConfigs()) {
                // 替换项目中要使用的bootstrap.yaml文件
                if (rollbackJsonFilePathDO.getOriginalPath().contains(replaceConfig.getConfigDir()) && rollbackJsonFilePathDO.getOriginalPath().contains(QUERY_BOOTSTRAP_YAML)) {
                    JSONObject jsonObject = InternalFileUtil.getProjectYamlByPath(rollbackJsonFilePathDO.getOriginalPath());
                    jsonObject = deepMergeJsonObjects(jsonObject, replaceConfig.getProjectBootstrapConfig());
                    createOrUpdateProjectNewYaml(jsonObject, rollbackJsonFilePathDO.getOriginalPath());
                }

                // 替换项目中要使用的application.yaml文件
                try {
                    // 获取nacos上应用的配置
                    String applicationConfig = getApplicationConfig(aliYunConfig, replaceConfig);
                    // 替换阿里云ram角色
                    JSONObject jsonObject = InternalFileUtil.getYamlByContent(applicationConfig);
                    Object producers = jsonObject.getByPath("churchill.rocketmq.producers");
                    if (ObjectUtil.isNotEmpty(producers)) {
                        JSONArray producersArray = (JSONArray) producers;
                        List<ALiYunConfig.Producers> newProducers = new ArrayList<>();
                        for (int i = 0; i < producersArray.size(); i++) {
                            // 根据你的对象类型进行转换
                            ALiYunConfig.Producers item = producersArray.get(i, ALiYunConfig.Producers.class);
                            if (ObjectUtil.isNotEmpty(item.getRamRole())) {
                                item.setRamRole(null);
                            }
                            if (ObjectUtil.isNotEmpty(item.getAddr())) {
                                item.setRamRole(null);
                                // mqAddr 可选：缺失时跳过 producer 地址替换
                                if (ObjectUtil.isEmpty(aliYunConfig.getMqAddr())) {
                                    log.warn("mqAddr 未配置，跳过 producer 地址替换: {}", item.getAddr());
                                } else {
                                    item.setAddr(aliYunConfig.getMqAddr());
                                }
                            }
                            newProducers.add(item);
                        }
                        jsonObject.putByPath("churchill.rocketmq.producers", newProducers);
                    }
                    jsonObject = deepMergeJsonObjects(jsonObject, replaceConfig.getProjectApplicationConfig());
                    createOrUpdateProjectNewYaml(jsonObject, replaceConfig.getConfigDir() + File.separator + QUERY_APPLICATION_YAML);
                    log.info("getNacosApplicationConfig:{}", jsonObject);
                } catch (Exception e) {
                    LogOutputUtil.error(logger, "get nacos config error, e:" + e.getMessage());
                    return;
                }
            }
        }
        createKtCtlCmdFile(projectPath, localDebugConfig);
        createRunApplicationAgentFile(projectPath, localDebugConfig, aliYunConfig);
        LogOutputUtil.log(logger, "替换配置成功！请安装最新的debug idea插件，idea必须要有这个插件才能本地执行debug");
    }

    private String getApplicationConfig(ALiYunConfig aliYunConfig, ReplaceConfig replaceConfig)
            throws NacosException {
        ConfigService configService = initNacosConfigService(aliYunConfig.getNacosServerAddr(), replaceConfig.getNacosNamespaceId());
        // 指定数据源名称和配置名称
        // different project has different dataId format
        for (String dataId : new String[]{replaceConfig.getNacosDataId(),
                replaceConfig.getNacosDataId() + ".yaml"}) {
            String applicationConfig = configService.getConfig(dataId, replaceConfig.getNacosGroup(), 3000L);
            if (ObjectUtil.isEmpty(applicationConfig)) {
                continue;
            }
            // roleArn 可选：缺失时跳过占位符替换，避免 replaceAll 空值 NPE
            if (ObjectUtil.isEmpty(aliYunConfig.getRamRole())) {
                log.warn("ramRole 未配置，跳过 ${mainServiceRamRoleName} 占位符替换");
                return applicationConfig;
            }
            return applicationConfig.replaceAll("\\$\\{mainServiceRamRoleName\\}", aliYunConfig.getRamRole());
        }
        return null;
    }

    private void createKtCtlCmdFile(String projectBasePath, LocalDebugConfig localDebugConfig) {
        String newLine = "\r\n";
        String ktCtlCmdFilePath = projectBasePath + File.separator + Constants.LOCAL_DEBUG_KTCTL_CMD_CONFIG_NAME;

        String namespace = localDebugConfig.getReplaceConfigs().get(0).getK8sServiceNamespace();
        List<String> ktCtlCmds = new LinkedList<>();
        ktCtlCmds.add(newLine);

        for (ReplaceConfig replaceConfig : localDebugConfig.getReplaceConfigs()) {
            ktCtlCmds.add("# enable proxy, -d 显示调试日志, -n value 指定目标服务的Kubernetes Namespace");
            ktCtlCmds.add("##ktconnect代理###" + replaceConfig.getAppName());
            ktCtlCmds.add(String.format("ktctl -d -n %s connect", namespace));
            ktCtlCmds.add(newLine);

            String k8sServiceName = replaceConfig.getK8sServiceName();
            if (ObjectUtil.isEmpty(k8sServiceName)) {
                k8sServiceName = "<请填写服务名>";
            }
            String k8sServiceNamespace = replaceConfig.getK8sServiceNamespace();
            if (ObjectUtil.isEmpty(k8sServiceNamespace)) {
                k8sServiceNamespace = "<请填写服务命名空间>";
            }
            if (replaceConfig.getServiceType().equals(Constants.BFF)) {
                ktCtlCmds.add("# proxy http request, ktctl mesh <目标服务名> --expose <本地端口>:<目标服务端口>");
                ktCtlCmds.add("##接口流量拦截代理###" + replaceConfig.getAppName());
                ktCtlCmds.add(String.format("ktctl -d -n %s mesh %s --expose %s:8080 --versionMark tag:%s",
                        k8sServiceNamespace, k8sServiceName, replaceConfig.getBffWebServerPort(), replaceConfig.getTag()));
                ktCtlCmds.add(newLine);

                // BFF的服务有可能跟rpc服务在同一个项目
                ktCtlCmds.add("# proxy rpc request, ktctl preview <新建服务名> --expose <本地端口>:<新建服务端口> ");
                ktCtlCmds.add("##rpc流量拦截代理###" + replaceConfig.getAppName());
                String alias = k8sServiceName + "-" + replaceConfig.getTag();
                ktCtlCmds.add(String.format("ktctl -d -n %s preview %s --expose %s:%s",
                        k8sServiceNamespace, alias, DEFAULT_DUBBO_PORT, DEFAULT_DUBBO_PORT));
                ktCtlCmds.add(newLine);
            } else if (replaceConfig.getServiceType().equals(Constants.RPC)) {
                ktCtlCmds.add("# proxy rpc request, ktctl preview <新建服务名> --expose <本地端口>:<新建服务端口> ");
                ktCtlCmds.add("##rpc流量拦截代理###" + replaceConfig.getAppName());
                String alias = k8sServiceName + "-" + replaceConfig.getTag();
                ktCtlCmds.add(String.format("ktctl -d -n %s preview %s --expose %s:%s",
                        k8sServiceNamespace, alias, DEFAULT_DUBBO_PORT, DEFAULT_DUBBO_PORT));
                ktCtlCmds.add(newLine);
            }
            ktCtlCmds.add("# ktctl -d -n [命名空间] recover [服务名]");
            ktCtlCmds.add("##清理流量拦截###" + replaceConfig.getAppName());
            ktCtlCmds.add(String.format("ktctl -d -n %s recover %s ", k8sServiceNamespace, k8sServiceName));
            ktCtlCmds.add(newLine);
            ktCtlCmds.add("# ktctl -d -n [命名空间] clean");
            ktCtlCmds.add("##清理集群里已超期KT代理容器###" + replaceConfig.getAppName());
            ktCtlCmds.add(String.format("ktctl -d -n %s clean ", k8sServiceNamespace));
            ktCtlCmds.add(newLine);
        }
        FileUtil.writeUtf8String(String.join(newLine, ktCtlCmds), ktCtlCmdFilePath);
    }

    private void createRunApplicationAgentFile(String projectBasePath, LocalDebugConfig localDebugConfig, ALiYunConfig aliYunConfig) {
        String jvmRcFilePath = projectBasePath + File.separator + Constants.LOCAL_DEBUG_JVMRC_CONFIG_NAME;

        List<JvmParametersConfig> list = new ArrayList<>();
        for (ReplaceConfig replaceConfig : localDebugConfig.getReplaceConfigs()) {
            JvmParametersConfig jvmParameters = new JvmParametersConfig();
            jvmParameters.setMainClass(replaceConfig.getAppMainClass());
            String nacosServerAddr = removeProtocolAndPort(aliYunConfig.getNacosServerAddr());
            String mqAddr = removeProtocolAndPort(aliYunConfig.getMqAddr());
            String jvmArgs = localDebugConfig.getJvmArgs() + " -Dhttp.nonProxyHosts=" + nacosServerAddr + "|" + nacosServerAddr.toLowerCase();
            // mqAddr 可选：缺失时跳过 mq 地址 nonProxyHosts 配置，避免空值拼接/NPE
            if (ObjectUtil.isNotEmpty(mqAddr)) {
                jvmArgs += "|" + mqAddr + "|" + mqAddr.toLowerCase()
                        + "|" + mqAddr.replace("_", "-")
                        + "|" + mqAddr.toLowerCase().replace("_", "-");
            } else {
                log.warn("mqAddr 未配置，跳过 mq 地址 nonProxyHosts 配置");
            }
            String alias = replaceConfig.getK8sServiceName() + "-" + replaceConfig.getTag();
            jvmParameters.setJvmArgs(jvmArgs + String.format(" -DDUBBO_IP_TO_REGISTRY=%s.%s.svc.cluster.local", alias, replaceConfig.getK8sServiceNamespace()));
            list.add(jvmParameters);
        }
        FileUtil.writeUtf8String(JSONUtil.toJsonStr(JSONUtil.parseArray(list), 2), jvmRcFilePath);
    }

    private String removeProtocolAndPort(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }

        // 移除协议部分 (http:// 或 https://)
        String result = url.replaceFirst("^https?://", "");
        // 移除端口号 (:数字)
        result = result.replaceFirst(":[0-9]+", "");
        // 移除路径部分 (如果存在)
        result = result.replaceFirst("/.*$", "");

        return result;
    }

    private Integer getDubboPort(String filePath) {
        JSONObject jsonObject = InternalFileUtil.getProjectYamlByPath(filePath);
        Integer port = jsonObject.getByPath("dubbo.protocol.port", Integer.class);
        return (port == null || port == -1) ? DEFAULT_DUBBO_PORT : port;
    }
}
