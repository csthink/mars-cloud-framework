package com.mars.cloud.observability.autoconfigure;

import com.mars.cloud.observability.internal.ManagementAccess;
import com.mars.cloud.observability.internal.ManagementAddress;
import com.mars.cloud.observability.internal.ManagementPort;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 以最低优先级提供可观测性的默认值，并把管理端口与管理端口的绑定地址从业务端口推导出来。
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
    public static final String USERNAME_PROPERTY = ManagementAccess.USERNAME_PROPERTY;
    /** 管理端点认证口令的属性名，对应环境变量 MARS_MANAGEMENT_PASSWORD。 */
    public static final String PASSWORD_PROPERTY = ManagementAccess.PASSWORD_PROPERTY;
    /** 管理端口的绑定地址。Spring Boot 不让它继承 server.address。 */
    public static final String MANAGEMENT_ADDRESS_PROPERTY = ManagementAddress.PROPERTY;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            return;
        }
        Map<String, Object> defaults = new LinkedHashMap<>();

        // 几个部署时要填的取值用短环境变量名，它们与属性名不同段，Boot 的宽松绑定接不上，
        // 所以在这里显式映射。部署物因此只需要认这几个名字，不用记完整属性路径。
        // 解析与认证链条件、核验器调用同一个方法，几处对「有没有凭据」的结论因此一致。
        String username = ManagementAccess.username(environment);
        String password = ManagementAccess.password(environment);
        if (username != null) {
            defaults.put(USERNAME_PROPERTY, username);
        }
        if (password != null) {
            defaults.put(PASSWORD_PROPERTY, password);
        }

        // 管理端点的暴露面必须在 Actuator 读取它之前定好，所以默认清单在这里收窄：
        // 没有凭据或建不起认证链时，除 health 与 info 外的端点在管理端口上是无认证可读的。
        // 这只是最低优先级的默认值，显式配置的暴露清单由 ManagementChainVerifier 按生效值核验。
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

        // 管理端口是否独立要按推导后的端口判断，所以在默认值加入环境之后再算。推导值单独放一个属性源，
        // 注册到服务发现时据此分辨管理地址是推导出来的还是显式配置的（见 ManagementAddress#derived）。
        String managementAddress = deriveManagementAddress(environment);
        if (managementAddress != null) {
            environment.getPropertySources().addLast(new MapPropertySource(
                    ManagementAddress.DERIVED_PROPERTY_SOURCE_NAME, Map.of(MANAGEMENT_ADDRESS_PROPERTY, managementAddress)));
        }
    }

    /**
     * 能认证就用可配置的完整清单，否则只暴露 health 与 info。不能认证时的清单不提供配置项：
     * 放宽它只会让需要认证的端点在没有认证链时暴露。
     *
     * <p>用 {@code Binder} 按列表绑定，而不是 {@code getProperty}：后者只按字面键查找，
     * 部署时用环境变量覆盖、或在配置文件里写成列表时都读不到，暴露面就悄悄退回内置默认值。
     */
    private String effectiveExposure(ConfigurableEnvironment environment, boolean authenticated) {
        if (!authenticated) {
            return ManagementAccess.UNAUTHENTICATED_EXPOSURE;
        }
        return Binder.get(environment)
                .bind("mars.observability.management.exposure", Bindable.listOf(String.class))
                .map(values -> String.join(",", values))
                .orElse(ObservabilityProperties.Management.DEFAULT_EXPOSURE);
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

    /**
     * 管理端口独立时，让它与业务端口绑定同一个地址。
     *
     * <p>Spring Boot 的管理端口按 {@code management.server.address} 绑定，没有配置时绑定全部网卡，
     * 不继承 {@code server.address}。管理端点与业务端点共用端口时配置这一项会启动失败，
     * 所以只在 Spring Boot 判定管理端口独立时推导，判定直接用它自己的 {@link ManagementPortType}。
     * 业务端口的地址不是具体的 IP 字面量（未配置、通配地址或主机名）时不推导，管理端口保持 Spring Boot 的默认。
     */
    private String deriveManagementAddress(ConfigurableEnvironment environment) {
        if (environment.containsProperty(MANAGEMENT_ADDRESS_PROPERTY)) {
            return null;
        }
        String serverAddress = ManagementAddress.specificLiteral(environment.getProperty("server.address"));
        if (serverAddress == null || ManagementPortType.get(environment) != ManagementPortType.DIFFERENT) {
            return null;
        }
        return serverAddress;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
