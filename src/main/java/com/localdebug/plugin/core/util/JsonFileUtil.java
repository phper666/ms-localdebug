package com.localdebug.plugin.core.util;

import cn.hutool.core.io.FileUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.experimental.UtilityClass;

/**
 * JSON文件工具类，提供对JSON文件的操作方法
 *
 * @author yuzhao.li
 * @date 2025/09/19 16:17
 */
@UtilityClass
public class JsonFileUtil {

    /**
     * 检查指定路径的JSON文件是否包含数据
     *
     * @param filePath JSON文件路径
     * @return true表示文件存在且包含数据，false表示文件不存在或不包含数据
     */
    public static boolean checkJsonFileHasData(String filePath) {
        try {
            // 检查文件是否存在
            if (!FileUtil.exist(filePath)) {
                return false;
            }

            // 读取并解析JSON
            String content = FileUtil.readUtf8String(filePath);
            if (JSONUtil.isTypeJSONObject(content)) {
                JSONObject jsonObject = JSONUtil.parseObj(content);
                return !jsonObject.isEmpty();
            } else if (JSONUtil.isTypeJSONArray(content)) {
                JSONArray jsonArray = JSONUtil.parseArray(content);
                return !jsonArray.isEmpty();
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }
}