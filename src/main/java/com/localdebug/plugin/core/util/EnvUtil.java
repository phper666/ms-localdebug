package com.localdebug.plugin.core.util;

import lombok.experimental.UtilityClass;

import java.util.Locale;

@UtilityClass
public class EnvUtil {

    public static String getUsername() {
        return System.getProperty("user.name");
    }

    public static String getUsernameAsTag() {
        return getUsername().replace(".", "-").toLowerCase(Locale.ROOT);
    }

    /**
     * 判断当前是否 Windows 系统（用于工具二进制后缀：Windows 用 .exe，Unix 用无后缀名）
     */
    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 按当前 OS 返回工具二进制文件名（如 kubectl / kubectl.exe、ktctl / ktctl.exe）
     */
    public static String toolBinaryName(String baseName) {
        return isWindows() ? baseName + ".exe" : baseName;
    }
}
