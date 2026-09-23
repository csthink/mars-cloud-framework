package com.mars.cloud.observability.internal;

import org.springframework.boot.actuate.endpoint.EndpointId;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 判断管理端点能否受认证保护、生效的暴露清单是否越界，以及当前是否处于开发 profile。
 *
 * <p>这些判断在两个时点都要问：环境后处理阶段用来决定暴露面，启动期核验与认证链的装配条件用来决定
 * 失败、告警还是建链。所有地方都调用这里的同一组方法，避免暴露面、认证链与核验结论脱节。
 */
public final class ManagementAccess {

    /** 管理端点认证账号的属性名。 */
    public static final String USERNAME_PROPERTY = "mars.observability.management.username";
    /** 管理端点认证口令的属性名。 */
    public static final String PASSWORD_PROPERTY = "mars.observability.management.password";
    /** 部署时给出账号的短环境变量名。 */
    public static final String USERNAME_VARIABLE = "MARS_MANAGEMENT_USERNAME";
    /** 部署时给出口令的短环境变量名。 */
    public static final String PASSWORD_VARIABLE = "MARS_MANAGEMENT_PASSWORD";

    /** 不能认证时唯一允许的暴露清单：health 只给聚合状态，info 不含运行细节。 */
    public static final String UNAUTHENTICATED_EXPOSURE = "health,info";

    private static final String EXPOSURE_INCLUDE = "management.endpoints.web.exposure.include";
    private static final String EXPOSURE_EXCLUDE = "management.endpoints.web.exposure.exclude";
    private static final String WILDCARD = "*";
    private static final Set<String> UNAUTHENTICATED_ENDPOINTS = Set.of("health", "info");

    private static final String SERVLET_SECURITY_MARKER =
            "org.springframework.security.config.annotation.web.builders.HttpSecurity";
    private static final String SERVLET_ENDPOINT_MATCHER =
            "org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest";
    private static final String REACTIVE_SECURITY_MARKER =
            "org.springframework.security.config.web.server.ServerHttpSecurity";
    private static final String REACTIVE_ENDPOINT_MATCHER =
            "org.springframework.boot.security.autoconfigure.actuate.web.reactive.EndpointRequest";

    private ManagementAccess() {
    }

    /**
     * classpath 上具备建立管理端点认证链所需的类：同一种 Web 栈的 Spring Security 与 Spring Boot 的端点匹配器。
     *
     * <p>这组类与两条认证链自动配置的类条件逐一对应。只看 Spring Security 不够：部署物带了它却没有声明
     * {@code spring-boot-starter-security} 时，链装配不起来，而暴露面若按有认证放开，管理端点就无认证可达。
     *
     * @param classLoader 应用自己的类加载器，不是本组件的类加载器
     */
    public static boolean authenticationChainSupported(ClassLoader classLoader) {
        return (ClassUtils.isPresent(SERVLET_SECURITY_MARKER, classLoader)
                && ClassUtils.isPresent(SERVLET_ENDPOINT_MATCHER, classLoader))
                || (ClassUtils.isPresent(REACTIVE_SECURITY_MARKER, classLoader)
                && ClassUtils.isPresent(REACTIVE_ENDPOINT_MATCHER, classLoader));
    }

    /** 管理端点的账号；没有配置时返回 {@code null}。解析规则见 {@link #resolve}。 */
    public static String username(Environment environment) {
        return resolve(environment, USERNAME_PROPERTY, USERNAME_VARIABLE);
    }

    /** 管理端点的口令；没有配置时返回 {@code null}。解析规则见 {@link #resolve}。 */
    public static String password(Environment environment) {
        return resolve(environment, PASSWORD_PROPERTY, PASSWORD_VARIABLE);
    }

    /** 账号与口令都解析到非空白值才算提供了凭据。 */
    public static boolean hasCredentials(Environment environment) {
        return username(environment) != null && password(environment) != null;
    }

    /** 管理端点能否受认证保护：既要有凭据，也要具备建立认证链所需的类。 */
    public static boolean canAuthenticate(Environment environment, ClassLoader classLoader) {
        return hasCredentials(environment) && authenticationChainSupported(classLoader);
    }

    /**
     * 生效的暴露清单里除 health 与 info 之外的端点，按名称排序；通配符原样记为 {@code *}。
     *
     * <p>读 Actuator 实际使用的 {@code management.endpoints.web.exposure.include} 与 {@code exclude}，
     * 不读本组件写入的默认值：显式配置会覆盖默认值。端点名按 Actuator 的规则归一化后比较。
     */
    public static Set<String> exposedEndpointsRequiringAuthentication(Environment environment) {
        Set<String> excluded = endpointIds(environment, EXPOSURE_EXCLUDE);
        Set<String> exposed = new TreeSet<>();
        if (excluded.contains(WILDCARD)) {
            return exposed;
        }
        for (String id : endpointIds(environment, EXPOSURE_INCLUDE)) {
            if (!UNAUTHENTICATED_ENDPOINTS.contains(id) && !excluded.contains(id)) {
                exposed.add(id);
            }
        }
        return exposed;
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

    /**
     * 取属性，属性没有配置或为空白时退回短环境变量，仍为空白则视为没有配置。
     *
     * <p>属性一侧用 {@code Binder}，环境变量的宽松形式（{@code MARS_OBSERVABILITY_MANAGEMENT_USERNAME}）
     * 与短名两条路都能进来。环境后处理把解析结果以最低优先级写回属性之后，再调用本方法得到的仍是同一个值：
     * 显式属性非空白时它优先，空白时两次都退回同一个环境变量。
     */
    private static String resolve(Environment environment, String property, String variable) {
        String value = Binder.get(environment).bind(property, String.class).orElse(null);
        if (value == null || value.isBlank()) {
            value = environment.getProperty(variable);
        }
        return value == null || value.isBlank() ? null : value;
    }

    private static Set<String> endpointIds(Environment environment, String key) {
        List<String> values = Binder.get(environment).bind(key, Bindable.listOf(String.class)).orElse(List.of());
        Set<String> ids = new TreeSet<>();
        for (String value : values) {
            String trimmed = value.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            ids.add(WILDCARD.equals(trimmed) ? WILDCARD : EndpointId.fromPropertyValue(trimmed).toLowerCaseString());
        }
        return ids;
    }
}
