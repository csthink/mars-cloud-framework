package com.mars.cloud.job.autoconfigure;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 两组属性源：环境变量映射（最高优先级）与推导出的执行器端口（最低优先级）。
 *
 * <p>四个与运行环境相关的取值只从环境变量来。它们的名字与属性路径不同段，Spring Boot 的宽松绑定接不上，
 * 所以在这里显式映射，只在变量存在时映射：
 * {@code MARS_MQ_PREFIX} → {@code mars.job.prefix}，{@code MARS_JOB_ADMIN_ADDRESSES} → {@code mars.job.admin.addresses}，
 * {@code MARS_JOB_ACCESS_TOKEN} → {@code mars.job.access-token}，
 * {@code MARS_JOB_REGISTER_HOST} → {@code mars.job.executor.register-host}。
 *
 * <p>执行器端口只在没有显式配置时推导为业务端口加偏移量。显式配置保留原值，由 {@link JobConventionVerifier}
 * 判断它是否符合约定，配错是一条可读的启动失败，而不是被推导值悄悄改掉。排在配置数据处理之后执行，
 * 能看到配置中心导入的取值。
 */
public final class MarsJobDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String ENVIRONMENT_SOURCE = "marsJobEnvironment";
    static final String DEFAULTS_SOURCE = "marsJobDefaults";

    static final String PREFIX_VARIABLE = "MARS_MQ_PREFIX";
    static final String ADMIN_ADDRESSES_VARIABLE = "MARS_JOB_ADMIN_ADDRESSES";
    static final String ACCESS_TOKEN_VARIABLE = "MARS_JOB_ACCESS_TOKEN";
    static final String REGISTER_HOST_VARIABLE = "MARS_JOB_REGISTER_HOST";

    static final String PORT_PROPERTY = "mars.job.executor.port";
    static final String PORT_OFFSET_PROPERTY = "mars.job.executor.port-offset";
    static final int DEFAULT_PORT_OFFSET = 2000;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getPropertySources().contains(ENVIRONMENT_SOURCE)) {
            Map<String, Object> mapped = new LinkedHashMap<>();
            map(environment, PREFIX_VARIABLE, "mars.job.prefix", mapped);
            map(environment, ADMIN_ADDRESSES_VARIABLE, "mars.job.admin.addresses", mapped);
            map(environment, ACCESS_TOKEN_VARIABLE, "mars.job.access-token", mapped);
            map(environment, REGISTER_HOST_VARIABLE, "mars.job.executor.register-host", mapped);
            if (!mapped.isEmpty()) {
                environment.getPropertySources().addFirst(new MapPropertySource(ENVIRONMENT_SOURCE, mapped));
            }
        }
        if (!environment.getPropertySources().contains(DEFAULTS_SOURCE)) {
            Integer derived = derivePort(environment);
            if (derived != null) {
                environment.getPropertySources().addLast(new MapPropertySource(DEFAULTS_SOURCE, Map.of(PORT_PROPERTY, derived)));
            }
        }
    }

    private static Integer derivePort(ConfigurableEnvironment environment) {
        if (environment.getProperty(PORT_PROPERTY) != null) {
            return null;
        }
        Integer serverPort = serverPort(environment);
        Integer offset = integer(environment, PORT_OFFSET_PROPERTY);
        int effectiveOffset = offset == null ? DEFAULT_PORT_OFFSET : offset;
        if (serverPort == null || serverPort <= 0 || effectiveOffset <= 0) {
            // 业务端口是随机端口或未配置时不推导，核验器要求显式配置执行器端口
            return null;
        }
        return serverPort + effectiveOffset;
    }

    /** 业务端口；未配置、空白或不是整数时为 null。 */
    static Integer serverPort(Environment environment) {
        return integer(environment, "server.port");
    }

    private static Integer integer(Environment environment, String key) {
        String value;
        try {
            value = environment.getProperty(key);
        } catch (IllegalArgumentException unresolvablePlaceholder) {
            return null;
        }
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException notAnInteger) {
            return null;
        }
    }

    private static void map(ConfigurableEnvironment environment, String variable, String property, Map<String, Object> target) {
        String value = environment.getProperty(variable);
        if (value != null) {
            target.put(property, value.trim());
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
