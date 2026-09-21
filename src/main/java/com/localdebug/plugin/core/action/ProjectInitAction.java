package com.localdebug.plugin.core.action;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.RuntimeUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONConfig;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.aliyun.sts20150401.models.AssumeRoleResponseBody;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.localdebug.plugin.core.cloud.ALiYunClient;
import com.localdebug.plugin.core.config.ALiYunConfig;
import com.localdebug.plugin.core.config.ALiYunTemporaryCredentials;
import com.localdebug.plugin.core.config.LocalDebugConfig;
import com.localdebug.plugin.core.config.ReplaceConfig;
import com.localdebug.plugin.core.config.SpringBootConfig;
import com.localdebug.plugin.core.constant.Constants;
import com.localdebug.plugin.core.engine.DescriptorLoader;
import com.localdebug.plugin.core.engine.DescriptorValidator;
import com.localdebug.plugin.core.engine.RegistryPatchApplier;
import com.localdebug.plugin.core.engine.ValidationEngine;
import com.localdebug.plugin.core.engine.ValidationEngine.ValidationResult;
import com.localdebug.plugin.core.model.Dependency;
import com.localdebug.plugin.core.model.FilePath;
import com.localdebug.plugin.core.model.K8sServiceItems;
import com.localdebug.plugin.core.model.StackDescriptor;
import com.localdebug.plugin.core.util.DirectoryUtil;
import com.localdebug.plugin.core.util.EnvUtil;
import com.localdebug.plugin.core.util.InternalFileUtil;
import com.localdebug.plugin.core.util.LogOutputUtil;
import com.localdebug.plugin.core.util.MiddlewarePatchUtil;
import com.localdebug.plugin.core.util.PortUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static com.localdebug.plugin.core.constant.Constants.BFF;
import static com.localdebug.plugin.core.constant.Constants.CLASS_RESOURCE_CONFIG;
import static com.localdebug.plugin.core.constant.Constants.CLASS_RESOURCE_DESC_CONFIG;
import static com.localdebug.plugin.core.constant.Constants.CLASS_RESOURCE_NACOS_NAMESPACE_MAP;
import static com.localdebug.plugin.core.constant.Constants.DEFAULT_DUBBO_PORT;
import static com.localdebug.plugin.core.constant.Constants.LOCAL_DEBUG_DESC_CONFIG_NAME;
import static com.localdebug.plugin.core.constant.Constants.QUERY_BOOTSTRAP_YAML;
import static com.localdebug.plugin.core.constant.Constants.RPC;

/**
 * 初始化配置操作类
 * 处理项目初始化配置相关的功能
 */
@Slf4j
public class ProjectInitAction implements Action {
    private JSONObject nacosNamespaceJsonObj;
    private JSONObject projectRules;

    private final DescriptorLoader descriptorLoader = new DescriptorLoader();
    private final DescriptorValidator descriptorValidator = new DescriptorValidator();
    private final ValidationEngine validationEngine = new ValidationEngine();
    private final RegistryPatchApplier registryPatchApplier = new RegistryPatchApplier();

    /**
     * 执行初始化配置操作
     *
     * @param projectPath     项目路径
     * @param projectBackPath 项目备份路径
     * @param logger          日志输出回调
     */
    @Override
    public void execute(String projectPath, String projectBackPath, Consumer<String> logger) {
        LogOutputUtil.log(logger, "开始执行初始化配置操作...");

        // 检查项目目录是否存在
        File projectDir = new File(projectPath);
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            LogOutputUtil.error(logger, "项目目录不存在或不是有效目录");
            return;
        }

        // 加载并校验栈描述符（描述符错误 = 新式静默失败，必须早暴露）
        StackDescriptor desc = descriptorLoader.load(null);
        List<String> descViolations = descriptorValidator.validate(desc);
        if (!descViolations.isEmpty()) {
            LogOutputUtil.error(logger, "栈描述符校验失败: " + String.join("; ", descViolations));
            return;
        }
        this.nacosNamespaceJsonObj = loadNamespaceMap();
        this.projectRules = loadProjectRules();

        ALiYunConfig aliYunConfig = InternalFileUtil.getGlobalProjectMqConfig();
        ALiYunTemporaryCredentials tempCredentials = InternalFileUtil.getGlobalALiYunTemporaryCredentials();

        // 获取临时AK/SK凭证
        try {
            updateTemporaryCredentialsIfNeeded(aliYunConfig, tempCredentials, logger);
            // 重新加载配置以确保获取最新的临时凭证
            tempCredentials = InternalFileUtil.getGlobalALiYunTemporaryCredentials();
        } catch (Exception e) {
            LogOutputUtil.error(logger, "获取临时AK/SK凭证失败: " + e.getMessage());
            return;
        }

        if (!aliYunConfig.isValidConfig()) {
            LogOutputUtil.error(logger, "nacos 地址未配置，请先填写阿里云配置信息");
            return;
        }

        // 分级校验引擎（H1）：required 缺失 fail-fast；强制不变式（MQ 独立消费组）恒校验
        Map<String, Object> configSnapshot = new HashMap<>();
        configSnapshot.put("configCenterAddr", aliYunConfig.getNacosServerAddr());
        configSnapshot.put(ValidationEngine.SNAPSHOT_KEY_MQ_CONSUMER_GROUP_ISOLATED,
                !ObjectUtil.isEmpty(aliYunConfig.getMqConsumers()));
        ValidationResult validationResult = validationEngine.check(desc, configSnapshot, new ArrayList<>(), new ArrayList<>());
        if (!validationResult.getMissingRequired().isEmpty()) {
            LogOutputUtil.error(logger, "阿里云配置缺失必需字段: " + String.join(", ", validationResult.getMissingRequired()));
            return;
        }
        if (!validationResult.getForcedViolations().isEmpty()) {
            LogOutputUtil.error(logger, String.join("; ", validationResult.getForcedViolations()));
            return;
        }

        // 生成localdebug和desc文件
        String projectLocalDebugConfigPath = projectPath + File.separator + Constants.LOCAL_DEBUG_CONFIG_NAME;
        String projectLocalDebugDescConfigPath = projectPath + File.separator + LOCAL_DEBUG_DESC_CONFIG_NAME;
        InternalFileUtil.pluginConfigCopyToProjectConfig(CLASS_RESOURCE_CONFIG, projectLocalDebugConfigPath);
        InternalFileUtil.pluginConfigCopyToProjectConfig(CLASS_RESOURCE_DESC_CONFIG, projectLocalDebugDescConfigPath);

        LocalDebugConfig localDebugConfig = InternalFileUtil.getLocalDebugConfig(projectLocalDebugConfigPath);
        // H4 dependency-template 出包：用户模板覆盖内置依赖值（缺失 → 保持 classpath 模板旧值，行为等价）
        applyDependencyTemplate(localDebugConfig);
        List<String> searchConfigFilePaths = getProjectConfigPaths(projectPath);
        List<ReplaceConfig> replaceConfigs = new ArrayList<>();
        List<FilePath> rollbackFilePaths = new ArrayList<>();
        // 生成k8s所有命名空间下的services
        Map<String, List<String>> namespaceServiceMap = generateK8sServiceConfigFile(projectPath);
        if (namespaceServiceMap.isEmpty()) {
            LogOutputUtil.error(logger, "获取k8s命名空间下的服务名称失败. 请使用cmd执行一下命名看看是否有输出: kubectl get services --all-namespaces -l control-by!=kt -o=json，如果有输出，则是idea加载环境变量有问题，需要关闭idea重新打开，如果有使用到toolbox，则需要关闭idea和toolbox，重新在toolbox打开idea");
            return;
        }

        boolean existsProjectConfigBackFile = existsProjectConfigBackFile(projectBackPath);
        for (String searchConfigFilePath : searchConfigFilePaths) {
            File searchConfigFile = new File(searchConfigFilePath);
            try {
                // 备份项目中所有有关的application.yaml、bootstrap.yaml文件，用于回滚
                if (!existsProjectConfigBackFile) {
                    String encryptPath = InternalFileUtil.createProjectConfigBackDirName(searchConfigFile);
                    String backFilePath = projectBackPath + File.separator + encryptPath + File.separator + searchConfigFile.getName();
                    backFilePath = FileUtil.normalize(backFilePath);
                    FilePath filePath = new FilePath();
                    filePath.setOriginalPath(searchConfigFilePath);
                    filePath.setBackPath(backFilePath);
                    rollbackFilePaths.add(filePath);
                    FileUtil.copy(searchConfigFilePath, backFilePath, false);
                    LogOutputUtil.log(logger, "searchConfigFilePath: " + searchConfigFilePath + ", backFilePath: " + backFilePath);
                }
            } catch (Exception e) {
                LogOutputUtil.error(logger, "init config error: " + e);
                return;
            }

            // 根据文件识别应用名称，并追加localdebug文件
            ReplaceConfig replaceConfig = new ReplaceConfig();
            // tag 取值：.localdebug.json 的 debugTag 字段优先，否则取 OS 用户名
            replaceConfig.setTag(StrUtil.isNotBlank(localDebugConfig.getDebugTag())
                    ? localDebugConfig.getDebugTag()
                    : EnvUtil.getUsernameAsTag());
            String projectRunApplication = InternalFileUtil.getProjectRunApplicationFilePackage(new File(new File(searchConfigFile.getParent()).getParent() + "/java"), "");
            replaceConfig.setAppMainClass(projectRunApplication);
            // 使用新的端口管理器获取项目端口
            int[] projectPorts = PortUtil.getPortsForProject(projectPath);
            replaceConfig.setBffWebServerPort(projectPorts[0]);
            replaceConfig.setRpcWebServerPort(projectPorts[1]);
            replaceConfig.setRNacosGroup("localdebug");
            replaceConfig.setConfigDir(FileUtil.normalize(searchConfigFile.getParent()));

            SpringBootConfig springBootConfig;
            if (existsProjectConfigBackFile) {
                String backFilePath = getProjectConfigBackPathByFileName(projectBackPath, InternalFileUtil.createProjectConfigBackDirName(searchConfigFile) + File.separator + searchConfigFile.getName());
                springBootConfig = InternalFileUtil.getProjectYaml(backFilePath);
            } else {
                springBootConfig = InternalFileUtil.getProjectYaml(searchConfigFilePath);
            }

            if (Objects.isNull(springBootConfig) || ObjectUtil.isEmpty(springBootConfig.getApplication())) {
                LogOutputUtil.error(logger, "*.yaml parsing error, Please check if there are spring.application.name and spring.cloud.nacos.config");
                continue;
            }
            if (searchConfigFile.getName().equals(QUERY_BOOTSTRAP_YAML)) {
                JSONObject projectBootstrapConfig = new JSONObject(JSONConfig.create().setIgnoreNullValue(false));
                projectBootstrapConfig.putByPath("spring.cloud.nacos.config.server-addr", aliYunConfig.getNacosServerAddr());
                projectBootstrapConfig.putByPath("spring.application.name", "application-localdebug");
                replaceConfig.setProjectBootstrapConfig(projectBootstrapConfig);
            }

            Optional<SpringBootConfig> optionalSpringBootConfig = Optional.of(springBootConfig);
            JSONObject projectApplicationConfig = new JSONObject(JSONConfig.create().setIgnoreNullValue(false));
            if (optionalSpringBootConfig.map(SpringBootConfig::getConfig).map(SpringBootConfig.Config::getImportValue).orElse(null) != null) {
                projectApplicationConfig.putByPath("spring.cloud.nacos.config.import-check.enabled", false);
            }

            replaceConfig.setAppName(optionalSpringBootConfig.map(SpringBootConfig::getApplication).map(SpringBootConfig.Application::getName).orElse(null));
            if (ObjectUtil.isEmpty(replaceConfig.getAppName())) {
                LogOutputUtil.error(logger, "spring.application.name cannot be empty");
                return;
            }
            setReplaceConfig(replaceConfig, optionalSpringBootConfig, namespaceServiceMap, projectPath);

            // RegistryPatchApplier（H2）：registryPatch + extraApplicationPatch + bootstrapPatch → 路径→值
            Map<String, Object> registryContext = new HashMap<>();
            registryContext.put("app", replaceConfig.getAppName());
            registryContext.put("tag", replaceConfig.getTag());
            registryContext.put("svc", replaceConfig.getK8sServiceName());
            registryContext.put("ns", replaceConfig.getK8sServiceNamespace());
            registryContext.put("configCenterAddr", aliYunConfig.getNacosServerAddr());
            Integer forcedRpcPort = desc.getRoutingEnabler() != null ? desc.getRoutingEnabler().getForcedRpcPort() : null;
            registryContext.put("rpcPort", forcedRpcPort != null ? forcedRpcPort : DEFAULT_DUBBO_PORT);
            Map<String, Object> registryApplied = registryPatchApplier.apply(desc, registryContext);

            // import-check 条件写（旧行为：仅项目 yaml 存在 config.import 时写入，且插入顺序最先）
            String importCheckKey = desc.getRegistryPatch().getImportCheckKey();
            Boolean importCheckValue = desc.getRegistryPatch().getImportCheckValue();
            if (importCheckKey != null) {
                registryApplied.remove(importCheckKey);
                if (optionalSpringBootConfig.map(SpringBootConfig::getConfig).map(SpringBootConfig.Config::getImportValue).orElse(null) != null) {
                    projectApplicationConfig.putByPath(importCheckKey, importCheckValue);
                }
            }
            for (Map.Entry<String, Object> entry : registryApplied.entrySet()) {
                String path = entry.getKey();
                if (path.startsWith("bootstrap:")) {
                    if (replaceConfig.getProjectBootstrapConfig() != null) {
                        replaceConfig.getProjectBootstrapConfig().putByPath(path.substring("bootstrap:".length()), entry.getValue());
                    }
                } else {
                    projectApplicationConfig.putByPath(path, entry.getValue());
                }
            }

            // 中间件相关 yaml patch（churchill.rocketmq.* / churchill.scheduler.* / alicloud.kms.* / server.port）
            // 数据化存放于 classpath: middleware-patches.json，由通用 patch 应用器遍历执行
            Map<String, Object> patchContext = buildPatchContext(aliYunConfig, tempCredentials, replaceConfig);
            MiddlewarePatchUtil.applyPatches(projectApplicationConfig, replaceConfig.getServiceType(), patchContext);

            replaceConfig.setProjectApplicationConfig(projectApplicationConfig);
            if (ObjectUtil.isEmpty(replaceConfig.getAppName())) {
                replaceConfig.setAppName(optionalSpringBootConfig.map(SpringBootConfig::getApplication).map(SpringBootConfig.Application::getName).orElse(""));
            }

            replaceConfigs.add(replaceConfig);
        }

        if (!replaceConfigs.isEmpty()) {
            localDebugConfig.setReplaceConfigs(replaceConfigs);

            FileUtil.writeUtf8String(JSONUtil.parseObj(localDebugConfig, false).toJSONString(2), projectLocalDebugConfigPath);
        }

        if (!rollbackFilePaths.isEmpty()) {
            FileUtil.writeUtf8String(JSONUtil.parseArray(rollbackFilePaths).toJSONString(2), projectBackPath + File.separator + Constants.ROLLBACK_FILE_PATHS_NAME);
        }

        // 初始化git的忽略文件
        checkProjectGitignore(projectPath);

        // audit 文件 + 组织前提未确认提示（TECH-DESIGN §2.4：前提确认非「检查通过」）
        writeAuditFile(projectPath, desc, validationResult);
        notifyPrecheckUnconfirmed(validationResult.getPrecheckEntries().size());

        LogOutputUtil.log(logger, "初始化配置成功！");
    }

    /**
     * H3a namespace-map 出包：用户文件优先，缺失回退 jar 内置
     */
    private JSONObject loadNamespaceMap() {
        Path userFile = getUserConfigDir().resolve("namespace-map.json");
        if (Files.exists(userFile)) {
            log.info("使用用户 namespace-map: {}", userFile);
            return JSONUtil.parseObj(FileUtil.readUtf8String(userFile.toFile()));
        }
        return InternalFileUtil.getPluginResourcesConfig(CLASS_RESOURCE_NACOS_NAMESPACE_MAP);
    }

    /**
     * H3b serviceType 规则出包：用户文件优先，缺失回退与改造前硬编码等价的默认规则
     */
    private JSONObject loadProjectRules() {
        Path userFile = getUserConfigDir().resolve("project-rules.json");
        if (Files.exists(userFile)) {
            log.info("使用用户 project-rules: {}", userFile);
            return JSONUtil.parseObj(FileUtil.readUtf8String(userFile.toFile()));
        }
        // TODO(P3): project-rules 的 UI 编辑入口（设置页），当前仅保留 json 编辑通道
        return defaultProjectRules();
    }

    /**
     * 与改造前硬编码逻辑等价的默认 serviceType 规则（无用户文件时使用）
     */
    static JSONObject defaultProjectRules() {
        return JSONUtil.parseObj(
                "{\"bffNamespaces\":[\"business\",\"storefront\"],"
                        + "\"bffPathRules\":["
                        + "{\"namespaceContains\":\"engagement\",\"pathContains\":\"messaging-edge-service-parent\"},"
                        + "{\"namespaceContains\":\"engagement\",\"pathContains\":\"core-bridge-service-parent\"},"
                        + "{\"namespaceContains\":\"engagement\",\"pathContains\":\"core-bridge-gateway\"},"
                        + "{\"namespaceContains\":\"engagement\",\"pathContains\":\"voice-service\"},"
                        + "{\"namespaceContains\":\"shared-service\",\"pathContains\":\"commerce-core-parent\"},"
                        + "{\"namespaceContains\":\"shared-service\",\"pathContains\":\"callback-gateway\"}],"
                        + "\"bffAppRules\":["
                        + "{\"namespaceContains\":\"internal\",\"appName\":\"internal-console\"},"
                        + "{\"namespaceContains\":\"intranet\",\"appName\":\"toolset-console\"}],"
                        + "\"unknownNamespaceAction\":\"prompt\"}");
    }

    /**
     * H4 dependency-template 出包：用户文件存在则覆盖本地依赖段；缺失保持 classpath 模板旧值（行为等价）
     */
    private void applyDependencyTemplate(LocalDebugConfig localDebugConfig) {
        try {
            Path userFile = getUserConfigDir().resolve("dependency-template.json");
            if (!Files.exists(userFile)) {
                return;
            }
            JSONObject template = JSONUtil.parseObj(FileUtil.readUtf8String(userFile.toFile()));
            JSONArray addDeps = template.getJSONArray("addDependencies");
            if (addDeps != null) {
                localDebugConfig.setAddDependencies(addDeps.toList(Dependency.class));
            }
            JSONArray updateDeps = template.getJSONArray("updateDependencies");
            if (updateDeps != null) {
                localDebugConfig.setUpdateDependencies(updateDeps.toList(Dependency.class));
            }
            log.info("已应用 dependency-template.json: {}", userFile);
        } catch (Exception e) {
            log.error("应用 dependency-template 失败，使用内置默认", e);
        }
    }

    /**
     * 组织前提确认清单（.localdebug.audit.json）：逐条 confirmed=false，不阻塞 init
     */
    private void writeAuditFile(String projectPath, StackDescriptor desc, ValidationEngine.ValidationResult validationResult) {
        try {
            JSONObject audit = new JSONObject();
            audit.set("stack", desc.getStack());
            audit.set("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            JSONArray prechecks = new JSONArray();
            for (String item : validationResult.getPrecheckEntries()) {
                JSONObject entry = new JSONObject();
                entry.set("item", item);
                entry.set("confirmed", false);
                prechecks.add(entry);
            }
            audit.set("prechecks", prechecks);
            String auditPath = projectPath + File.separator + ".localdebug.audit.json";
            FileUtil.writeUtf8String(audit.toJSONString(2), auditPath);
            log.info("已生成前提确认清单: {}", auditPath);
        } catch (Exception e) {
            log.error("写入 audit 文件失败", e);
        }
    }

    /**
     * 组织前提未确认提示（仅一次；audit 文件为权威记录）
     */
    private void notifyPrecheckUnconfirmed(int count) {
        if (count <= 0) {
            return;
        }
        try {
            Notification notification = new Notification("LocalDebug", "LocalDebug",
                    "有 " + count + " 项组织路由前提未确认（详见项目根 .localdebug.audit.json），调试可能静默失败",
                    NotificationType.WARNING);
            Notifications.Bus.notify(notification);
        } catch (Exception e) {
            log.debug("组织前提通知发送失败", e);
        }
    }

    /**
     * 配置根目录（系统属性 localdebug.config.dir 可覆盖，测试隔离用）
     */
    private Path getUserConfigDir() {
        String override = System.getProperty("localdebug.config.dir");
        if (StrUtil.isNotBlank(override)) {
            return Paths.get(override);
        }
        return DirectoryUtil.getToolsConfigDir();
    }

    private Map<String, Object> buildPatchContext(ALiYunConfig aliYunConfig, ALiYunTemporaryCredentials tempCredentials, ReplaceConfig replaceConfig) {
        if (ObjectUtil.isEmpty(aliYunConfig.getMqAddr())) {
            log.warn("mqAddr 未配置，中间件 MQ 相关配置将为空");
        }
        if (ObjectUtil.isEmpty(aliYunConfig.getRoleArn())) {
            log.warn("roleArn 未配置，RAM 角色相关配置将为空");
        }
        Map<String, Object> context = new HashMap<>();
        context.put("mqAddr", aliYunConfig.getMqAddr());
        context.put("roleArn", aliYunConfig.getRoleArn());
        context.put("accessKey", aliYunConfig.getAccessKey());
        context.put("secretKey", aliYunConfig.getSecretKey());
        context.put("schedulerNamespace", aliYunConfig.getSchedulerNamespace());
        context.put("schedulerGroupId", aliYunConfig.getSchedulerGroupId());
        context.put("schedulerAppKey", aliYunConfig.getSchedulerAppKey());
        context.put("mqConsumers", aliYunConfig.getMqConsumers());
        context.put("stsAccessKey", tempCredentials.getStsAccessKey());
        context.put("stsSecretKey", tempCredentials.getStsSecretKey());
        context.put("stsSecurityToken", tempCredentials.getStsSecurityToken());
        context.put("bffWebServerPort", replaceConfig.getBffWebServerPort());
        context.put("rpcWebServerPort", replaceConfig.getRpcWebServerPort());
        return context;
    }

    /**
     * 检查并更新临时凭证（如果需要）
     */
    private void updateTemporaryCredentialsIfNeeded(ALiYunConfig aliYunConfig, ALiYunTemporaryCredentials tempCredentials, Consumer<String> logger) throws Exception {
        // 检查是否需要更新临时凭证（不存在或已过期）
        if (!isTemporaryCredentialsValid(tempCredentials)) {
            // AK/SK/roleArn 为可选字段：缺失时明确报错并跳过 STS 获取（由外层 catch 统一提示）
            if (ObjectUtil.isEmpty(aliYunConfig.getAccessKey()) ||
                    ObjectUtil.isEmpty(aliYunConfig.getSecretKey()) ||
                    ObjectUtil.isEmpty(aliYunConfig.getRoleArn())) {
                throw new RuntimeException("未配置阿里云 AK/SK 或 roleArn，无法获取临时凭证");
            }
            LogOutputUtil.log(logger, "正在获取临时AK/SK凭证...");

            // 调用阿里云接口获取临时凭证
            AssumeRoleResponseBody.AssumeRoleResponseBodyCredentials credentials = ALiYunClient.getAssumeRoleCredentials(
                    aliYunConfig.getAccessKey(),
                    aliYunConfig.getSecretKey(),
                    aliYunConfig.getStsAkSkDurationSeconds(),
                    aliYunConfig.getRoleArn(),
                    EnvUtil.getUsername()
            );

            if (credentials != null) {
                // 创建新的临时凭证对象
                ALiYunTemporaryCredentials newTempCredentials = new ALiYunTemporaryCredentials();
                newTempCredentials.setStsAccessKey(credentials.getAccessKeyId());
                newTempCredentials.setStsSecretKey(credentials.getAccessKeySecret());
                newTempCredentials.setStsSecurityToken(credentials.getSecurityToken());

                // 将UTC时间转换为本地时间格式存储
                String utcExpiration = credentials.getExpiration();
                try {
                    Instant instant = Instant.parse(utcExpiration);
                    ZoneId zoneId = ZoneId.systemDefault();
                    LocalDateTime localDateTime = LocalDateTime.ofInstant(instant, zoneId);
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                    newTempCredentials.setStsTokenExpiration(localDateTime.format(formatter));
                } catch (Exception e) {
                    // 如果转换失败，仍然存储原始UTC时间
                    newTempCredentials.setStsTokenExpiration(utcExpiration);
                }

                // 保存更新后的临时凭证
                InternalFileUtil.saveGlobalALiYunTemporaryCredentials(newTempCredentials);

                LogOutputUtil.log(logger, "临时AK/SK凭证获取成功");
            } else {
                throw new RuntimeException("获取临时AK/SK凭证失败");
            }
        } else {
            LogOutputUtil.log(logger, "临时AK/SK凭证有效，无需重新获取");
        }
    }

    /**
     * 检查临时凭证是否有效（存在且未过期）
     */
    private boolean isTemporaryCredentialsValid(ALiYunTemporaryCredentials tempCredentials) {
        // 检查是否已有临时AK/SK以及过期时间
        boolean hasTemporaryCredentials = tempCredentials.getStsAccessKey() != null && !tempCredentials.getStsAccessKey().isEmpty() &&
                tempCredentials.getStsSecretKey() != null && !tempCredentials.getStsSecretKey().isEmpty() &&
                tempCredentials.getStsTokenExpiration() != null && !tempCredentials.getStsTokenExpiration().isEmpty();

        if (!hasTemporaryCredentials) {
            return false;
        }

        // 检查是否过期
        try {
            // 解析UTC时间字符串
            String expirationStr = tempCredentials.getStsTokenExpiration();
            Instant expirationInstant;

            // 判断是哪种时间格式
            if (expirationStr.contains("T")) {
                // ISO-8601格式 (例如: 2025-11-29T00:49:37Z)
                expirationInstant = Instant.parse(expirationStr);
            } else {
                // 自定义格式 (例如: 2025-11-29 00:49:37)
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                LocalDateTime localDateTime = LocalDateTime.parse(expirationStr, formatter);
                expirationInstant = localDateTime.atZone(ZoneId.systemDefault()).toInstant();
            }

            Instant currentInstant = Instant.now();

            // 判断是否过期（考虑5分钟缓冲时间）
            return currentInstant.isBefore(expirationInstant.minusSeconds(300));
        } catch (Exception e) {
            // 解析时间出错，认为已过期
            return false;
        }
    }

    private void setReplaceConfig(ReplaceConfig replaceConfig, Optional<SpringBootConfig> optionalSpringBootConfig, Map<String, List<String>> namespaceServiceMap, String projectBasePath) {
        if (ObjectUtil.isEmpty(replaceConfig.getNacosGroup())) {
            replaceConfig.setNacosGroup(optionalSpringBootConfig.map(SpringBootConfig::getCloud).map(SpringBootConfig.Cloud::getNacos).map(SpringBootConfig.Nacos::getConfig).map(SpringBootConfig.NacosConfig::getGroup).orElse(""));
        }
        if (ObjectUtil.isEmpty(replaceConfig.getNacosDataId())) {
            replaceConfig.setNacosDataId(optionalSpringBootConfig.map(SpringBootConfig::getCloud).map(SpringBootConfig.Cloud::getNacos).map(SpringBootConfig.Nacos::getConfig).map(SpringBootConfig.NacosConfig::getDataId).orElse(replaceConfig.getAppName() + ".yaml"));
        }
        if (ObjectUtil.isEmpty(replaceConfig.getNacosNamespaceId())) {
            replaceConfig.setNacosNamespaceId(optionalSpringBootConfig.map(SpringBootConfig::getCloud).map(SpringBootConfig.Cloud::getNacos).map(SpringBootConfig.Nacos::getConfig).map(SpringBootConfig.NacosConfig::getNamespace).orElse(""));
        }
        String namespace = nacosNamespaceJsonObj.getStr(replaceConfig.getNacosNamespaceId(), "");
        if (ObjectUtil.isEmpty(replaceConfig.getK8sServiceName())) {
            replaceConfig.setK8sServiceName(getK8sServiceName(optionalSpringBootConfig.map(SpringBootConfig::getApplication).map(SpringBootConfig.Application::getName).orElse(""), namespaceServiceMap.get(namespace)));
        }
        replaceConfig.setK8sServiceNamespace(namespace);
        if (ObjectUtil.isEmpty(replaceConfig.getServiceType())) {
            String serviceType = getServiceType(replaceConfig.getK8sServiceNamespace(), replaceConfig.getAppName(), projectBasePath);
            replaceConfig.setServiceType(serviceType);
        }
    }

    /**
     * H3b serviceType 推导（数据驱动，project-rules.json 出包）：
     * 判定顺序 = bffNamespaces → bffPathRules → bffAppRules → 已知命名空间→RPC → 未知→unknownNamespaceAction。
     * 与改造前硬编码逻辑行为等价（business/storefront→bff；engagement/shared-service 走 path 规则；
     * internal/intranet 走 app 规则；其余→rpc；完全不认识→prompt 提示）。
     */
    private String getServiceType(String k8sServiceNamespace, String applicationName, String projectBasePath) {
        return evaluateServiceType(projectRules, k8sServiceNamespace, applicationName, projectBasePath);
    }

    /**
     * 纯规则求值（可测）：按默认/用户 rules 判定 serviceType
     */
    static String evaluateServiceType(JSONObject rules, String k8sServiceNamespace, String applicationName, String projectBasePath) {
        JSONArray bffNamespaces = rules.getJSONArray("bffNamespaces");
        if (bffNamespaces != null) {
            for (Object ns : bffNamespaces) {
                if (k8sServiceNamespace.contains(String.valueOf(ns))) {
                    return BFF;
                }
            }
        }
        JSONArray bffPathRules = rules.getJSONArray("bffPathRules");
        if (bffPathRules != null) {
            for (Object ruleObj : bffPathRules) {
                JSONObject rule = (JSONObject) ruleObj;
                String nsPattern = rule.getStr("namespaceContains", "");
                String pathPattern = rule.getStr("pathContains", "");
                if (StrUtil.isBlank(nsPattern) || StrUtil.isBlank(pathPattern)) {
                    continue;
                }
                if (k8sServiceNamespace.contains(nsPattern) && projectBasePath.contains(pathPattern)) {
                    return BFF;
                }
            }
        }
        JSONArray bffAppRules = rules.getJSONArray("bffAppRules");
        if (bffAppRules != null) {
            for (Object ruleObj : bffAppRules) {
                JSONObject rule = (JSONObject) ruleObj;
                String nsPattern = rule.getStr("namespaceContains", "");
                String appName = rule.getStr("appName", "");
                if (StrUtil.isBlank(nsPattern) || StrUtil.isBlank(appName)) {
                    continue;
                }
                if (k8sServiceNamespace.contains(nsPattern) && applicationName.equals(appName)) {
                    return BFF;
                }
            }
        }
        // 已知命名空间（bffNamespaces ∪ 各规则 namespaceContains）→ RPC
        if (isKnownNamespace(k8sServiceNamespace, bffNamespaces, bffPathRules, bffAppRules)) {
            return RPC;
        }
        // 未知命名空间 → 按 unknownNamespaceAction（默认 prompt，保旧提示语）
        String unknownAction = rules.getStr("unknownNamespaceAction", "prompt");
        if ("bff".equals(unknownAction)) {
            return BFF;
        }
        if ("rpc".equals(unknownAction)) {
            return RPC;
        }
        return String.format("%s or %s? 需要个人根据项目type选择一下", BFF, RPC);
    }

    private static boolean isKnownNamespace(String k8sServiceNamespace, JSONArray bffNamespaces, JSONArray pathRules, JSONArray appRules) {
        if (bffNamespaces != null) {
            for (Object ns : bffNamespaces) {
                if (k8sServiceNamespace.contains(String.valueOf(ns))) {
                    return true;
                }
            }
        }
        for (JSONArray rules : new JSONArray[]{pathRules, appRules}) {
            if (rules != null) {
                for (Object ruleObj : rules) {
                    JSONObject rule = (JSONObject) ruleObj;
                    String nsPattern = rule.getStr("namespaceContains", "");
                    if (StrUtil.isNotBlank(nsPattern) && k8sServiceNamespace.contains(nsPattern)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 由于不同服务,k8s上注册的service name和app name不一定一致,所以通过相似度匹配算法来通过appName来找到可能的service name
     * (暂定通过: edit distance来判断)
     */
    private String getK8sServiceName(String appName, List<String> candidateServiceNames) {
        if (ObjectUtil.isEmpty(candidateServiceNames) || ObjectUtil.isEmpty(appName)) {
            return "";
        }
        String tip = "需要通过.localdebug.k8sServices.json找到service name";
        Set<String> candidates = new HashSet<>();
        String[] parts = appName.split("-");
        for (String candidate : candidateServiceNames) {
            for (String p : parts) {
                if (!"provider".equals(p) && !"management".equals(p) && !"service".equals(p) && candidate.contains(p)) {
                    candidates.add(candidate);
                }
            }
        }
        if (candidates.isEmpty()) {
            return tip;
        }

        Integer minDis = Integer.MAX_VALUE;
        List<String> result = new LinkedList<>();
        for (String candidate : candidates) {
            Integer dis = getEditDistance(appName, candidate);
            if (dis < minDis) {
                minDis = dis;
                result = new LinkedList<>();
                result.add(candidate);
            } else if (dis.equals(minDis)) {
                result.add(candidate);
            }
        }
        if (result.isEmpty()) {
            return tip;
        }
        if (result.size() == 1) {
            return result.get(0);
        }
        return String.join(" or ", result) + String.format("(%s)", tip);
    }

    private Integer getEditDistance(String a, String b) {
        int aLen = a.length();
        int bLen = b.length();

        if (aLen == 0) {
            return aLen;
        }
        if (bLen == 0) {
            return bLen;
        }

        int[][] v = new int[aLen + 1][bLen + 1];
        for (int i = 0; i <= aLen; ++i) {
            for (int j = 0; j <= bLen; ++j) {
                if (i == 0) {
                    v[i][j] = j;
                } else if (j == 0) {
                    v[i][j] = i;
                } else if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    v[i][j] = v[i - 1][j - 1];
                } else {
                    v[i][j] = 1 + Math.min(v[i - 1][j - 1], Math.min(v[i][j - 1], v[i - 1][j]));
                }
            }
        }
        return v[aLen][bLen];
    }

    private Map<String, List<String>> generateK8sServiceConfigFile(String projectBasePath) {
        Map<String, List<String>> localDebugK8sServicesConfigMap = new HashMap<>();
        // 生成k8s所有命名空间下的services
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<Boolean> future = null;
        try {
            future = executor.submit(() -> {
                // 生成k8s所有命名空间下的services(不包括ktctl部署上去的service,避免影响后续的k8sServiceName的识别)
                // OS 无关：Windows 用 kubectl.exe，Unix 用无后缀名
                String kubectlPath = DirectoryUtil.getToolsBinDir() + File.separator + EnvUtil.toolBinaryName("kubectl");
                String str = RuntimeUtil.execForStr(kubectlPath + " get services --all-namespaces -l control-by!=kt -o=json");
                log.info("str: {}", str);
                if (Objects.equals(JSONUtil.parseObj(str).toString(), "{}")) {
                    return true;
                }
                List<K8sServiceItems> items = JSONUtil.toList(JSONUtil.parseObj(str).getObj("items", "{}").toString(), K8sServiceItems.class);
                String localDebugK8sServicesConfigPath = projectBasePath + File.separator + Constants.LOCAL_DEBUG_K8S_SERVICES_CONFIG_NAME;
                for (K8sServiceItems item : items) {
                    if (item.getMetadata() != null) {
                        String namespace = item.getMetadata().getNamespace();
                        if (namespace.contains("dev1")) {
                            continue;
                        }
                        String name = item.getMetadata().getName();
                        List<String> nameList = localDebugK8sServicesConfigMap.getOrDefault(namespace, new ArrayList<>());
                        nameList.add(name);
                        localDebugK8sServicesConfigMap.put(namespace, nameList);
                    }
                }
                FileUtil.writeUtf8String(JSONUtil.parseObj(localDebugK8sServicesConfigMap).toJSONString(2), localDebugK8sServicesConfigPath);
                return true;
            });
            future.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            // 终止子线程执行逻辑
            future.cancel(true);
        } catch (Exception e) {
            Thread.currentThread().interrupt();
        }
        return localDebugK8sServicesConfigMap;
    }
}
