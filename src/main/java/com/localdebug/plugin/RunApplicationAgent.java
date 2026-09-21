package com.localdebug.plugin;

import cn.hutool.core.io.FileUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.runners.JavaProgramPatcher;
import com.localdebug.plugin.core.constant.Constants;
import com.localdebug.plugin.core.config.JvmParametersConfig;

import java.io.File;
import java.util.List;

/**
 * 运行 Java 应用前注入 .localdebug.jvmrc.json 中定义的 JVM 参数与环境变量
 */
public class RunApplicationAgent extends JavaProgramPatcher {
    private static final Log LOG = LogFactory.get();

    @Override
    public void patchJavaParameters(Executor executor, RunProfile runProfile, JavaParameters javaParameters) {
        if (runProfile instanceof RunConfiguration runConfiguration) {
            String root = runConfiguration.getProject().getBasePath();
            File file = FileUtil.file(root, Constants.LOCAL_DEBUG_JVMRC_CONFIG_NAME);
            if (file.exists()) {
                try {
                    List<JvmParametersConfig> jvmParametersList = getProjectJvmParameters(file);
                    for (JvmParametersConfig jvmParameters : jvmParametersList) {
                        if (javaParameters.getMainClass() != null && javaParameters.getMainClass().equals(jvmParameters.getMainClass())) {
                            javaParameters.getVMParametersList().addParametersString(jvmParameters.getJvmArgs());
                            javaParameters.getEnv().putAll(jvmParameters.getJvmEnvs());
                        }
                    }
                } catch (Exception e) {
                    LOG.info("patchJavaParameters error: {}", e.getMessage());
                }
            }
        }
    }

    private List<JvmParametersConfig> getProjectJvmParameters(File file) {
        JSONArray jsonArray = JSONUtil.parseArray(FileUtil.readUtf8String(file));
        return JSONUtil.toList(jsonArray, JvmParametersConfig.class);
    }
}
