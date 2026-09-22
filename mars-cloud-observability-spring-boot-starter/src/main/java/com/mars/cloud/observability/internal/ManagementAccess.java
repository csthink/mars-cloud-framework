package com.mars.cloud.observability.internal;

import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

import java.util.Arrays;
import java.util.List;

/**
 * 判断管理端点能否受认证保护，以及当前是否处于开发 profile。
 *
 * <p>这两件事在两个时点都要问：环境后处理阶段用来决定暴露面，启动期核验用来决定失败还是告警。
 * 两处读同一份判断，避免暴露面与核验结论脱节。
 */
public final class ManagementAccess {

    private static final String SERVLET_SECURITY_MARKER =
            "org.springframework.security.config.annotation.web.builders.HttpSecurity";
    private static final String REACTIVE_SECURITY_MARKER =
            "org.springframework.security.config.web.server.ServerHttpSecurity";

    private ManagementAccess() {
    }

    /** classpath 上有 Spring Security 才能建立管理端点的认证链。 */
    public static boolean securityPresent(ClassLoader classLoader) {
        return ClassUtils.isPresent(SERVLET_SECURITY_MARKER, classLoader)
                || ClassUtils.isPresent(REACTIVE_SECURITY_MARKER, classLoader);
    }

    /** 用户名与密码都非空白才算提供了凭据。 */
    public static boolean hasCredentials(Environment environment) {
        return notBlank(environment.getProperty("mars.observability.management.username"))
                && notBlank(environment.getProperty("mars.observability.management.password"));
    }

    /** 管理端点能否受认证保护：既要有凭据，也要有 Spring Security。 */
    public static boolean canAuthenticate(Environment environment, ClassLoader classLoader) {
        return hasCredentials(environment) && securityPresent(classLoader);
    }

    /**
     * 当前激活的 profile 是否被 {@code mars.env.dev-profiles} 列为开发环境。
     * 该属性由 core starter 定义，这里只读取它的原始取值，不依赖它的类。
     */
    public static boolean developmentProfile(Environment environment) {
        String[] configured = environment.getProperty("mars.env.dev-profiles", String[].class);
        if (configured == null || configured.length == 0) {
            return false;
        }
        List<String> development = Arrays.asList(configured);
        for (String active : environment.getActiveProfiles()) {
            if (development.contains(active)) {
                return true;
            }
        }
        return false;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
