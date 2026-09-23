package com.mars.cloud.observability.autoconfigure;

import com.mars.cloud.observability.internal.ManagementAccess;
import com.mars.cloud.observability.internal.ManagementPort;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 以最低优先级提供可观测性的默认值，并把管理端口从业务端口推导出来。
 *
 * <p>这里写入的每一项都会被显式配置（环境变量、配置中心、application.yml）覆盖，
 * 覆盖后的取值由 {@link ObservabilityConventionVerifier} 在启动期核验。
 */
public final class MarsObservabilityDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "marsObservabilityDefaults";
    /** Boot 4 的 OTLP 追踪导出端点属性名；Boot 3 的 management.otlp.tracing.endpoint 已失效。 */
    public static final String TRACING_ENDPOINT_PROPERTY =
            "management.opentelemetry.tracing.export.otlp.endpoint";
    /** 管理端点认证账号的属性名，对应环境变量 MARS_MANAGEMENT_USERNAME。 */
    public static final String USERNAME_PROPERTY = "mars.observability.management.username";
    /** 管理端点认证口令的属性名，对应环境变量 MARS_MANAGEMENT_PASSWORD。 */
    public static final String PASSWORD_PROPERTY = "mars.observability.management.password";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            return;
        }
        Map<String, Object> defaults = new LinkedHashMap<>();

        // 几个部署时要填的取值用短环境变量名，它们与属性名不同段，Boot 的宽松绑定接不上，
        // 所以在这里显式映射。部署物因此只需要认这几个名字，不用记完整属性路径。
        String username = resolve(environment, USERNAME_PROPERTY, "MARS_MANAGEMENT_USERNAME");
        String password = resolve(environment, PASSWORD_PROPERTY, "MARS_MANAGEMENT_PASSWORD");
        if (username != null) {
            defaults.put(USERNAME_PROPERTY, username);
        }
        if (password != null) {
            defaults.put(PASSWORD_PROPERTY, password);
        }

        // 管理端点的暴露面必须在 Actuator 读取它之前定好，所以收窄在这里完成而不是留给核验器：
        // 没有凭据或建不起认证链时，除 health 与 info 外的端点在管理端口上是无认证可读的。
        // 暴露面要用映射后的凭据判断：映射结果此刻还在本方法的局部集合里，没进环境。
        // 类是否存在按应用自己的类加载器判断，与认证链自动配置的类条件求值用同一个加载器。
        boolean authenticated = username != null && password != null
                && ManagementAccess.authenticationChainSupported(application.getClassLoader());
        defaults.put("management.endpoints.web.exposure.include", effectiveExposure(environment, authenticated));
        defaults.put("management.endpoint.health.show-details", "when-authorized");
        defaults.put("management.endpoint.health.show-components", "when-authorized");
        defaults.put("management.endpoint.health.probes.enabled", true);
        defaults.put("management.metrics.tags.application", "${spring.application.name:unknown}");

        // 链路追踪：全量采样，生产按流量在配置中心调低；导出端点由环境变量给出。
        // 属性名用 Boot 4 的前缀。Boot 3 的 management.otlp.tracing.* 在 4.0 已按 error 级废弃，
        // 但配上去既不报错也不生效，导出器会静默地不创建，所以这里不能沿用旧名。
        defaults.put("management.tracing.sampling.probability", "1.0");
        String endpoint = environment.getProperty("OTLP_TRACING_ENDPOINT");
        if (endpoint != null && !endpoint.isBlank()) {
            defaults.put(TRACING_ENDPOINT_PROPERTY, endpoint);
        }

        // 日志的 traceId 取自线程本地的 MDC。响应式栈的请求在 Reactor 线程之间切换，
        // Boot 默认的 limited 不会在切换后把当前观测恢复进来，切换之后的日志行因此丢失 traceId，
        // 而调用链在追踪后端里是完整的。auto 让 Reactor 在每个操作符上恢复它。
        // 不按 Web 栈区分：Servlet 部署物里经 Reactor 执行的调用（例如 WebClient）切换线程时是同一机制。
        defaults.put("spring.reactor.context-propagation", "auto");

        // 结构化日志：默认 ECS JSON 到控制台；plain 只在本机调试时用，那时不写这个键。
        String consoleFormat = environment.getProperty("mars.observability.logging.console-format", "ecs");
        if (!"plain".equalsIgnoreCase(consoleFormat)) {
            defaults.put("logging.structured.format.console", "ecs");
        }

        Integer derived = deriveManagementPort(environment);
        if (derived != null) {
            defaults.put("management.server.port", derived);
        }

        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
    }

    /**
     * 取属性，没有就退回环境变量。属性一侧用 {@code Binder}，这样环境变量的宽松形式
     * （{@code MARS_OBSERVABILITY_MANAGEMENT_USERNAME}）与短名两条路都能进来。
     * 空白按缺失处理，这样「没配」与「配成空字符串」在后续判断里是同一件事。
     */
    private String resolve(ConfigurableEnvironment environment, String property, String variable) {
        String value = Binder.get(environment).bind(property, String.class).orElse(null);
        if (value == null || value.isBlank()) {
            value = environment.getProperty(variable);
        }
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * 能认证就用完整清单，否则退回受限清单。
     *
     * <p>用 {@code Binder} 而不是 {@code getProperty}：后者只按字面键查找，
     * 部署时用环境变量覆盖清单会读不到，暴露面就悄悄退回内置默认值。
     */
    private String effectiveExposure(ConfigurableEnvironment environment, boolean authenticated) {
        String key = authenticated
                ? "mars.observability.management.exposure"
                : "mars.observability.management.restricted-exposure";
        String fallback = authenticated ? "health,info,prometheus,metrics,loggers,threaddump,heapdump" : "health,info";
        return Binder.get(environment).bind(key, String.class).orElse(fallback);
    }

    /**
     * 只有在没人显式配置管理端口时才推导。显式配置保留原值，由核验器判断它是否符合约定，
     * 这样「配错了」是一条可读的启动失败，而不是被默认值悄悄改掉。
     */
    private Integer deriveManagementPort(ConfigurableEnvironment environment) {
        if (environment.getProperty("management.server.port") != null) {
            return null;
        }
        int offset = environment.getProperty("mars.observability.management.port-offset", Integer.class, 1000);
        return ManagementPort.derive(ManagementPort.serverPort(environment), offset);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
