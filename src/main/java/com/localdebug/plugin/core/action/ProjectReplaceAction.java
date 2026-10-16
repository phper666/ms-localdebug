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
import com.localdebug.plugin.core.engine.DescriptorLoader;
import com.localdebug.plugin.core.engine.DescriptorValidator;
import com.localdebug.plugin.core.engine.RegistryPatchApplier;
import com.localdebug.plugin.core.model.Dependency;
import com.localdebug.plugin.core.model.FilePath;
import com.localdebug.plugin.core.model.StackDescriptor;
import com.localdebug.plugin.core.util.InternalFileUtil;
import com.localdebug.plugin.core.util.JsonFileUtil;
import com.localdebug.plugin.core.util.LogOutputUtil;
import com.localdebug.plugin.core.maven.MavenUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;

import static com.localdebug.plugin.core.constant.Constants.DEFAULT_DUBBO_PORT;
import static com.localdebug.plugin.core.constant.Constants.QUERY_APPLICATION_YAML;
import static com.localdebug.plugin.core.constant.Constants.QUERY_BOOTSTRAP_YAML;

/**
 * 替换配置操作类
 * 处理项目替换配置相关的功能
 */
@Slf4j
public class ProjectReplaceAction implements Action {

    private final DescriptorLoader descriptorLoader = new DescriptorLoader();
    private final DescriptorValidator descriptorValidator = new DescriptorValidator();
    /** 单次执行内赋值/使用，非线程安全（Action 实例每次执行新建） */
    private StackDescriptor descriptor;

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

        // 加载并校验栈描述符（描述符错误 = 新式静默失败，必须早暴露；加载异常统一转 IllegalArgumentException）
        try {
            this.descriptor = descriptorLoader.load(null);
            List<String> descViolations = descriptorValidator.validate(descriptor);
            if (!descViolations.isEmpty()) {
                LogOutputUtil.error(logger, "栈描述符校验失败: " + String.join("; ", descViolations));
                return;
            }
        } catch (RuntimeException e) {
            LogOutputUtil.error(logger, "栈描述符加载失败: " + e.getMessage());
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
                    // 替换阿里云ram角色（M3：producers 路径读描述符 middlewareGroup.producersKey）
                    JSONObject jsonObject = InternalFileUtil.getYamlByContent(applicationConfig);
                    String producersKey = descriptor.getMiddlewareGroup() != null
                            ? descriptor.getMiddlewareGroup().getProducersKey() : "churchill.rocketmq.producers";
                    Object producers = jsonObject.getByPath(producersKey);
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
                        jsonObject.putByPath(producersKey, newProducers);
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
            // H2：配置文本含 ${mainServiceRamRoleName} 但未配置 ramRole → 报错退出，不再写占位符字面量进 yaml
            // TODO(P2): M5 占位符表数据化——${mainServiceRamRoleName} 替换仍为硬编码，待 middleware patches 挂 requires 声明后收编
            if (applicationConfig.contains("${mainServiceRamRoleName}")) {
                if (ObjectUtil.isEmpty(aliYunConfig.getRamRole())) {
                    throw new IllegalStateException("nacos 配置 [" + replaceConfig.getNacosDataId()
                            + "] 含 ${mainServiceRamRoleName} 占位符，但未配置 ramRole，请先在设置页填写");
                }
                // L1：ramRole 可能含 $ / \ 等特殊字符，用 quoteReplacement 防非法分组引用
                return applicationConfig.replaceAll("\\$\\{mainServiceRamRoleName\\}",
                        Matcher.quoteReplacement(aliYunConfig.getRamRole()));
            }
            return applicationConfig;
        }
        return null;
    }

    private void createKtCtlCmdFile(String projectBasePath, LocalDebugConfig localDebugConfig) {
        String newLine = "\r\n";
        String ktCtlCmdFilePath = projectBasePath + File.separator + Constants.LOCAL_DEBUG_KTCTL_CMD_CONFIG_NAME;

        // 旧行为：connect 用第一个 replaceConfig 的命名空间（所有 connect 共用）
        String connectNamespace = localDebugConfig.getReplaceConfigs().get(0).getK8sServiceNamespace();
        List<String> ktCtlCmds = new LinkedList<>();
        ktCtlCmds.add(newLine);
        for (ReplaceConfig replaceConfig : localDebugConfig.getReplaceConfigs()) {
            ktCtlCmds.addAll(buildKtctlCmdFileLines(descriptor, replaceConfig, connectNamespace));
        }
        FileUtil.writeUtf8String(String.join(newLine, ktCtlCmds), ktCtlCmdFilePath);
    }

    /**
     * M8：ktctl 命令文件行生成（描述符 proxy.templates 展开，保留旧分段格式与命令顺序）。
     * 命令内容与改造前 createKtCtlCmdFile 逐行一致（含注释分段行与 recover/clean 尾随空格）。
     *
     * @param connectNamespace connect 命令使用的命名空间（旧行为：全项目取第一个 replaceConfig 的 ns）
     */
    public static List<String> buildKtctlCmdFileLines(StackDescriptor desc, ReplaceConfig rc, String connectNamespace) {
        String newLine = "\r\n";
        Map<String, String> templates = desc.getProxy() != null ? desc.getProxy().getTemplates() : null;
        List<String> lines = new LinkedList<>();

        lines.add("# enable proxy, -d 显示调试日志, -n value 指定目标服务的Kubernetes Namespace");
        lines.add("##ktconnect代理###" + rc.getAppName());
        Map<String, Object> connectCtx = new HashMap<>();
        connectCtx.put("ns", connectNamespace);
        lines.add(expandTemplate(templates, "connect", connectCtx));
        lines.add(newLine);

        String k8sServiceName = ObjectUtil.isEmpty(rc.getK8sServiceName()) ? "<请填写服务名>" : rc.getK8sServiceName();
        String k8sServiceNamespace = ObjectUtil.isEmpty(rc.getK8sServiceNamespace()) ? "<请填写服务命名空间>" : rc.getK8sServiceNamespace();
        String alias = k8sServiceName + "-" + rc.getTag();
        // M7：rpcPort 与 init 侧同源（routingEnabler.forcedRpcPort，缺省 DEFAULT_DUBBO_PORT）
        Integer forcedRpcPort = desc.getRoutingEnabler() != null ? desc.getRoutingEnabler().getForcedRpcPort() : null;
        int rpcPort = forcedRpcPort != null ? forcedRpcPort : DEFAULT_DUBBO_PORT;
        if (Constants.BFF.equals(rc.getServiceType())) {
            lines.add("# proxy http request, ktctl mesh <目标服务名> --expose <本地端口>:<目标服务端口>");
            lines.add("##接口流量拦截代理###" + rc.getAppName());
            Map<String, Object> meshCtx = new HashMap<>();
            meshCtx.put("ns", k8sServiceNamespace);
            meshCtx.put("svc", k8sServiceName);
            meshCtx.put("port", rc.getBffWebServerPort());
            meshCtx.put("tag", rc.getTag());
            lines.add(expandTemplate(templates, "mesh", meshCtx));
            lines.add(newLine);

            // BFF的服务有可能跟rpc服务在同一个项目
            lines.add("# proxy rpc request, ktctl preview <新建服务名> --expose <本地端口>:<新建服务端口> ");
            lines.add("##rpc流量拦截代理###" + rc.getAppName());
            lines.add(expandTemplate(templates, "preview", previewCtx(k8sServiceNamespace, alias, rpcPort)));
            lines.add(newLine);
        } else if (Constants.RPC.equals(rc.getServiceType())) {
            lines.add("# proxy rpc request, ktctl preview <新建服务名> --expose <本地端口>:<新建服务端口> ");
            lines.add("##rpc流量拦截代理###" + rc.getAppName());
            lines.add(expandTemplate(templates, "preview", previewCtx(k8sServiceNamespace, alias, rpcPort)));
            lines.add(newLine);
        }
        lines.add("# ktctl -d -n [命名空间] recover [服务名]");
        lines.add("##清理流量拦截###" + rc.getAppName());
        Map<String, Object> recoverCtx = new HashMap<>();
        recoverCtx.put("ns", k8sServiceNamespace);
        recoverCtx.put("svc", k8sServiceName);
        lines.add(expandTemplate(templates, "recover", recoverCtx));
        lines.add(newLine);
        lines.add("# ktctl -d -n [命名空间] clean");
        lines.add("##清理集群里已超期KT代理容器###" + rc.getAppName());
        Map<String, Object> cleanCtx = new HashMap<>();
        cleanCtx.put("ns", k8sServiceNamespace);
        lines.add(expandTemplate(templates, "clean", cleanCtx));
        lines.add(newLine);
        return lines;
    }

    private static Map<String, Object> previewCtx(String k8sServiceNamespace, String alias, int rpcPort) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("ns", k8sServiceNamespace);
        ctx.put("alias", alias);
        ctx.put("rpcPort", rpcPort);
        return ctx;
    }

    private static String expandTemplate(Map<String, String> templates, String name, Map<String, Object> context) {
        String template = templates == null ? null : templates.get(name);
        if (template == null) {
            log.warn("proxy.templates 缺少模板: {}", name);
            return "";
        }
        return RegistryPatchApplier.resolveTemplate(template, context);
    }

    private void createRunApplicationAgentFile(String projectBasePath, LocalDebugConfig localDebugConfig, ALiYunConfig aliYunConfig) {
        String jvmRcFilePath = projectBasePath + File.separator + Constants.LOCAL_DEBUG_JVMRC_CONFIG_NAME;
        List<JvmParametersConfig> list = buildJvmParametersList(descriptor, localDebugConfig.getReplaceConfigs(), localDebugConfig.getJvmArgs(), aliYunConfig);
        FileUtil.writeUtf8String(JSONUtil.toJsonStr(JSONUtil.parseArray(list), 2), jvmRcFilePath);
    }

    /**
     * M8：jvmrc 参数列表生成（描述符 routingEnabler 驱动），文件内容与改造前逐项一致
     */
    public static List<JvmParametersConfig> buildJvmParametersList(StackDescriptor desc, List<ReplaceConfig> replaceConfigs,
                                                                   String baseJvmArgs, ALiYunConfig aliYunConfig) {
        List<JvmParametersConfig> list = new ArrayList<>();
        for (ReplaceConfig replaceConfig : replaceConfigs) {
            JvmParametersConfig jvmParameters = new JvmParametersConfig();
            jvmParameters.setMainClass(replaceConfig.getAppMainClass());
            jvmParameters.setJvmArgs(buildJvmArgs(desc, replaceConfig, baseJvmArgs, aliYunConfig));
            list.add(jvmParameters);
        }
        return list;
    }

    /**
     * jvmArgs 生成：`-Dhttp.nonProxyHosts=<nonProxyHosts> -D<rpcRegistryEnvVar>=<rpcRegistryIpTemplate 展开>`
     */
    public static String buildJvmArgs(StackDescriptor desc, ReplaceConfig rc, String baseJvmArgs, ALiYunConfig aliYunConfig) {
        Map<String, Object> nonProxyCtx = new HashMap<>();
        nonProxyCtx.put("configCenterAddr", removeProtocolAndPort(aliYunConfig.getNacosServerAddr()));
        nonProxyCtx.put("mqAddr", removeProtocolAndPort(aliYunConfig.getMqAddr()));
        String nonProxyHosts = buildNonProxyHosts(desc, nonProxyCtx);

        Map<String, Object> registryCtx = new HashMap<>();
        registryCtx.put("svc", rc.getK8sServiceName());
        registryCtx.put("tag", rc.getTag());
        registryCtx.put("ns", rc.getK8sServiceNamespace());
        String registryIp = RegistryPatchApplier.resolveTemplate(desc.getRoutingEnabler().getRpcRegistryIpTemplate(), registryCtx);

        return baseJvmArgs + " -Dhttp.nonProxyHosts=" + nonProxyHosts
                + String.format(" -D%s=%s", desc.getRoutingEnabler().getRpcRegistryEnvVar(), registryIp);
    }

    /**
     * nonProxyHosts 生成（保持旧格式）：首源 2 变体（raw|lower），其余源 4 变体（raw|lower|dash|lower-dash）；空源跳过
     */
    public static String buildNonProxyHosts(StackDescriptor desc, Map<String, Object> context) {
        List<String> sources = desc.getRoutingEnabler() != null ? desc.getRoutingEnabler().getNonProxyHostsSources() : null;
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        if (sources != null) {
            for (int i = 0; i < sources.size(); i++) {
                String value = RegistryPatchApplier.resolveTemplate(sources.get(i), context);
                if (ObjectUtil.isEmpty(value)) {
                    continue;
                }
                if (!first) {
                    sb.append("|");
                }
                sb.append(value).append("|").append(value.toLowerCase(Locale.ROOT));
                if (i > 0) {
                    sb.append("|").append(value.replace("_", "-"))
                            .append("|").append(value.toLowerCase(Locale.ROOT).replace("_", "-"));
                }
                first = false;
            }
        }
        return sb.toString();
    }

    public static String removeProtocolAndPort(String url) {
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
