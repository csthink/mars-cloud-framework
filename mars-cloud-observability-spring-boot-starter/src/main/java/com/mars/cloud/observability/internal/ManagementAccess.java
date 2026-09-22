package com.mars.cloud.observability.internal;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

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
     *
     * <p>该属性由别的组件定义，这里只读它的取值，不依赖它的类。用 {@code Binder} 而不是
     * {@code getProperty(key, String[].class)}：配置文件里的列表会被展开成 {@code …[0]}、{@code …[1]}
     * 这样的索引属性，按整键去取只会得到空值，判断会静默退化成「不是开发环境」。
     * 比较忽略大小写，与该属性原有的语义一致。
     */
    public static boolean developmentProfile(Environment environment) {
        List<String> development = Binder.get(environment)
                .bind("mars.env.dev-profiles", Bindable.listOf(String.class))
                .orElse(List.of());
        if (development.isEmpty()) {
            return false;
        }
        for (String active : environment.getActiveProfiles()) {
            for (String candidate : development) {
                if (candidate != null && candidate.equalsIgnoreCase(active)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
