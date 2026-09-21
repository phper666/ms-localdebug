package com.localdebug.plugin.core.engine;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONNull;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.localdebug.plugin.core.model.StackDescriptor;
import com.localdebug.plugin.core.util.DirectoryUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 栈描述符加载器。
 * <ul>
 *   <li>load(null/空) → 内置默认栈（classpath: stacks/sca-dubbo-ktctl.json）</li>
 *   <li>load("xxx") → 用户栈 ~/.localdebug/config/stacks/xxx.json（根目录可用系统属性
 *       localdebug.config.dir 覆盖，测试隔离用，沿用 P0 ConfigManager 模式）</li>
 *   <li>inherits 解析：builtin:&lt;名&gt; 追内置库，裸文件名追用户目录；
 *       合并语义 = 父层字段打底、子层覆盖（嵌套对象递归合并，子层 null 值删除父层键）；
 *       防循环继承（深度上限 5）</li>
 * </ul>
 * 纯 Java，无 IntelliJ 依赖。
 */
public class DescriptorLoader {
    private static final String BUILTIN_PREFIX = "builtin:";
    private static final String DEFAULT_STACK_NAME = "sca-dubbo-ktctl";
    private static final String BUILTIN_STACK_DIR = "com/localdebug/plugin/core/stacks/";
    private static final String USER_STACKS_SUBDIR = "stacks";
    private static final String CONFIG_DIR_OVERRIDE_PROP = "localdebug.config.dir";
    private static final int MAX_INHERIT_DEPTH = 5;

    /**
     * 加载栈描述符（含 inherits 合并后的最终结果）
     *
     * @param stackNameOrNull null/空白 → 内置默认栈；builtin:&lt;名&gt; → 内置库；裸名 → 用户目录
     * @return 合并完成的描述符
     * @throws IllegalArgumentException 栈不存在 / 继承循环 / 超过深度上限
     */
    public StackDescriptor load(String stackNameOrNull) {
        if (StrUtil.isBlank(stackNameOrNull)) {
            return loadInternal(BUILTIN_PREFIX + DEFAULT_STACK_NAME, new ArrayList<>(), 0);
        }
        return loadInternal(stackNameOrNull, new ArrayList<>(), 0);
    }

    private StackDescriptor loadInternal(String stackName, List<String> chain, int depth) {
        if (depth > MAX_INHERIT_DEPTH) {
            throw new IllegalArgumentException("栈继承深度超过上限 " + MAX_INHERIT_DEPTH
                    + "（疑似循环继承）: " + String.join(" -> ", chain) + " -> " + stackName);
        }
        JSONObject merged = loadMergedJson(stackName, chain, depth);
        return JSONUtil.toBean(merged, StackDescriptor.class);
    }

    /**
     * 递归合并继承链，返回最终 JSON
     */
    private JSONObject loadMergedJson(String stackName, List<String> chain, int depth) {
        JSONObject raw = readRawJson(stackName);
        String inherits = raw.getStr("inherits");
        if (StrUtil.isBlank(inherits)) {
            return raw;
        }
        if (chain.contains(inherits)) {
            throw new IllegalArgumentException("栈继承循环: " + String.join(" -> ", chain) + " -> " + inherits);
        }
        List<String> nextChain = new ArrayList<>(chain);
        nextChain.add(stackName);
        JSONObject parent = loadMergedJson(inherits, nextChain, depth + 1);
        return merge(parent, raw);
    }

    /**
     * 深合并：base 打底，override 覆盖；嵌套 JSONObject 递归合并；override 中 null/JSONNull 删除键
     */
    private JSONObject merge(JSONObject base, JSONObject override) {
        JSONObject merged = new JSONObject();
        merged.putAll(base);
        for (String key : override.keySet()) {
            Object value = override.get(key);
            if (value == null || value instanceof JSONNull) {
                merged.remove(key);
                continue;
            }
            Object baseValue = merged.get(key);
            if (baseValue instanceof JSONObject && value instanceof JSONObject) {
                merged.set(key, merge((JSONObject) baseValue, (JSONObject) value));
            } else {
                merged.set(key, value);
            }
        }
        return merged;
    }

    /**
     * 读取原始 JSON（不做继承合并）
     */
    private JSONObject readRawJson(String stackName) {
        String json;
        if (stackName.startsWith(BUILTIN_PREFIX)) {
            String builtinName = stackName.substring(BUILTIN_PREFIX.length());
            json = ResourceUtil.readUtf8Str(BUILTIN_STACK_DIR + builtinName + ".json");
            if (json == null) {
                throw new IllegalArgumentException("内置栈不存在: " + builtinName);
            }
        } else {
            Path userFile = getUserStacksDir().resolve(stackName + ".json");
            if (!Files.exists(userFile)) {
                throw new IllegalArgumentException("用户栈不存在: " + stackName + "（" + userFile + "）");
            }
            json = FileUtil.readUtf8String(userFile.toFile());
        }
        return JSONUtil.parseObj(json);
    }

    /**
     * 用户栈目录：&lt;配置根&gt;/stacks（配置根默认 ~/.localdebug/config，可用系统属性覆盖）
     */
    private Path getUserStacksDir() {
        String override = System.getProperty(CONFIG_DIR_OVERRIDE_PROP);
        Path configDir = StrUtil.isNotBlank(override)
                ? Paths.get(override)
                : DirectoryUtil.getToolsConfigDir();
        return configDir.resolve(USER_STACKS_SUBDIR);
    }
}
