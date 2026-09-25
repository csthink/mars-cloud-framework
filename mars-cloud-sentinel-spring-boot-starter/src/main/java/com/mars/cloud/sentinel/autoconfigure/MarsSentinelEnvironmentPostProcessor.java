package com.mars.cloud.sentinel.autoconfigure;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.ClassUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 在 Nacos 配置导入之后检查 Sentinel 相关配置，并写入本组件的默认值。
 *
 * <p>下列配置会绕过「规则只从 Nacos 进入、拦截异常交给应用处理、不开放端口」的约定，出现即启动失败：
 * 控制台与命令端口（{@code spring.cloud.sentinel.transport.*}）、Spring Cloud Alibaba 的属性数据源
 * （{@code spring.cloud.sentinel.datasource.*}）、网关兜底响应（{@code spring.cloud.sentinel.scg.fallback.*}）、
 * Servlet 拦截页（{@code spring.cloud.sentinel.block-page}、{@code spring.cloud.sentinel.servlet.block-page}）
 * 与 Spring Cloud Alibaba 的 Feign 集成（{@code feign.sentinel.enabled=true}）。
 *
 * <p>默认值（最低优先级）：资源名带 HTTP 方法（{@code spring.cloud.sentinel.http-method-specify=true}）；
 * 非响应式应用关闭 Spring Cloud Alibaba 的网关过滤器（{@code spring.cloud.sentinel.scg.enabled=false}），
 * 它的装配只看 classpath 上有没有 Gateway，在 Servlet 应用里会因缺少 WebFlux 的编解码配置而启动失败。
 * 系统属性 {@code csp.sentinel.metric.flush.interval} 未设置时写 0，关闭每秒写一次的指标文件。本组件自带的
 * {@code sentinel.properties} 已写了同一个值，不依赖 Spring 启动先后；系统属性再覆盖应用自己的
 * {@code sentinel.properties} 里可能给出的其他值。
 * 显式关闭 Sentinel（{@code spring.cloud.sentinel.enabled=false}）时同时关闭网关过滤器，不做其他处理。
 *
 * @since 2026-09-25
 */
public final class MarsSentinelEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String DEFAULTS_SOURCE = "marsSentinelDefaults";
    static final String METRIC_FLUSH_INTERVAL = "csp.sentinel.metric.flush.interval";
    static final String HTTP_COMMAND_CENTER = "com.alibaba.csp.sentinel.transport.command.SimpleHttpCommandCenter";

    private static final List<ConfigurationPropertyName> FORBIDDEN_PREFIXES = List.of(
            ConfigurationPropertyName.of("spring.cloud.sentinel.transport"),
            ConfigurationPropertyName.of("spring.cloud.sentinel.datasource"),
            ConfigurationPropertyName.of("spring.cloud.sentinel.scg.fallback"),
            ConfigurationPropertyName.of("spring.cloud.sentinel.block-page"),
            ConfigurationPropertyName.of("spring.cloud.sentinel.servlet.block-page"));

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty("spring.cloud.sentinel.enabled", Boolean.class, true)) {
            addDefaults(environment, Map.of("spring.cloud.sentinel.scg.enabled", false));
            return;
        }
        if (ClassUtils.isPresent(HTTP_COMMAND_CENTER, application.getClassLoader())) {
            throw new IllegalStateException("classpath 上有 Sentinel 的 HTTP 命令中心（sentinel-transport-simple-http）："
                    + "它开放一个没有认证、能在运行期改规则的端口，规则只能从 Nacos 进入；从依赖里排除它");
        }
        TreeSet<String> forbidden = new TreeSet<>();
        for (ConfigurationPropertySource source : ConfigurationPropertySources.get(environment)) {
            if (source instanceof IterableConfigurationPropertySource iterable) {
                iterable.stream()
                        .filter(name -> FORBIDDEN_PREFIXES.stream()
                                .anyMatch(prefix -> prefix.equals(name) || prefix.isAncestorOf(name)))
                        .forEach(name -> forbidden.add(name.toString()));
            }
        }
        if (environment.getProperty("feign.sentinel.enabled", Boolean.class, false)) {
            forbidden.add("feign.sentinel.enabled");
        }
        if (!forbidden.isEmpty()) {
            throw new IllegalStateException("这些 Sentinel 配置与本组件的约定冲突，删除后再启动：" + forbidden
                    + "。规则只从 Nacos 的规则 Data ID 进入，不装控制台、不开放命令端口；拦截异常交给应用的统一错误处理；"
                    + "Feign 客户端的资源由本组件按客户端登记");
        }
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("spring.cloud.sentinel.http-method-specify", true);
        if (webApplicationType(environment, application) != WebApplicationType.REACTIVE) {
            defaults.put("spring.cloud.sentinel.scg.enabled", false);
        }
        addDefaults(environment, defaults);
        if (System.getProperty(METRIC_FLUSH_INTERVAL) == null) {
            System.setProperty(METRIC_FLUSH_INTERVAL, "0");
        }
    }

    /** 显式配置的应用类型优先；它在环境后处理之后才绑定到 {@link SpringApplication}，所以这里直接读属性。 */
    private static WebApplicationType webApplicationType(ConfigurableEnvironment environment, SpringApplication application) {
        return Binder.get(environment)
                .bind("spring.main.web-application-type", WebApplicationType.class)
                .orElseGet(application::getWebApplicationType);
    }

    private static void addDefaults(ConfigurableEnvironment environment, Map<String, Object> defaults) {
        if (!environment.getPropertySources().contains(DEFAULTS_SOURCE)) {
            environment.getPropertySources().addLast(new MapPropertySource(DEFAULTS_SOURCE, defaults));
        }
    }
}
