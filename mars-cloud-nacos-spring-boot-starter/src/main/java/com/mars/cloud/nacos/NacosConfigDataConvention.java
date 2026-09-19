package com.mars.cloud.nacos;

import java.util.List;
import java.util.regex.Pattern;

/**
 * mars-cloud 的 Nacos Config Data 命名约定。
 *
 * <p>共享配置先导入，应用配置后导入。Spring 后导入的配置优先级更高，
 * 因此应用可以覆盖共享默认值。
 */
public final class NacosConfigDataConvention {

    public static final String SHARED_GROUP = "COMMON";
    public static final String APPLICATION_GROUP = "DEFAULT_GROUP";
    public static final String SHARED_DATA_ID = "shared-common.yaml";

    private static final Pattern APPLICATION_NAME =
            Pattern.compile("[a-z][a-z0-9]*(?:-[a-z0-9]+)*");

    private NacosConfigDataConvention() {
    }

    /**
     * 返回共享配置的必选导入位置。
     */
    public static String sharedImport() {
        return "nacos:" + SHARED_DATA_ID
                + "?group=" + SHARED_GROUP
                + "&refreshEnabled=true";
    }

    /**
     * 返回指定应用的必选导入位置。
     *
     * @param applicationName Spring application name
     * @return Nacos Config Data 导入位置
     */
    public static String applicationImport(String applicationName) {
        validateApplicationName(applicationName);
        return "nacos:" + applicationName + ".yaml"
                + "?group=" + APPLICATION_GROUP
                + "&refreshEnabled=true";
    }

    /**
     * 返回按优先级排列的两层配置导入。
     */
    public static List<String> requiredImports(String applicationName) {
        return List.of(sharedImport(), applicationImport(applicationName));
    }

    /**
     * 应用名必须是小写 kebab-case，保证 service name 与 Data ID 一致。
     */
    public static void validateApplicationName(String applicationName) {
        if (applicationName == null || !APPLICATION_NAME.matcher(applicationName).matches()) {
            throw new IllegalArgumentException(
                    "spring.application.name 必须是小写 kebab-case: " + applicationName);
        }
    }
}
