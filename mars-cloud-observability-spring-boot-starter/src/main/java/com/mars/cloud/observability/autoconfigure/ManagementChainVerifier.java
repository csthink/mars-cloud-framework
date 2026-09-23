package com.mars.cloud.observability.autoconfigure;

import com.mars.cloud.observability.internal.ManagementAccess;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.Set;

/**
 * 在启动期核验 Web 应用的管理端点暴露面与认证链一致：认证链没有装配时，生效的暴露清单只能包含 health 与 info。
 *
 * <p>认证链没有装配有三种原因：缺凭据；classpath 上缺 Spring Security 或 Spring Boot 的 Web 安全模块；
 * 凭据与这些类都在，链却没有装配，即部署物关掉了 Web 安全装配、排除了认证链的自动配置，
 * 或 classpath 上的安全类属于另一种 Web 栈。前两种情况下环境后处理已把默认暴露清单收窄，
 * 第三种情况下默认清单已按有认证放开；显式配置的 {@code management.endpoints.web.exposure.include}
 * 在三种情况下都能覆盖默认值。所以这里按生效的清单判断，越界时非开发 profile 拒绝启动、开发 profile 告警。
 * 只对 Web 应用装配：非 Web 应用不开 HTTP 端口，认证链本来就不会装配。
 */
public final class ManagementChainVerifier implements InitializingBean, BeanClassLoaderAware {

    /** 越界消息的固定前缀，完整消息再写出没有认证链的原因与越界的端点。 */
    static final String UNPROTECTED_EXPOSURE_MESSAGE = "管理端点没有认证链保护，暴露清单只能包含 health 与 info";

    private static final Log logger = LogFactory.getLog(ManagementChainVerifier.class);

    private final Environment environment;
    private final ListableBeanFactory beanFactory;
    private ClassLoader classLoader = ManagementChainVerifier.class.getClassLoader();

    public ManagementChainVerifier(Environment environment, ListableBeanFactory beanFactory) {
        this.environment = environment;
        this.beanFactory = beanFactory;
    }

    /** 按应用的类加载器判断类是否存在，与环境后处理及认证链自动配置的判断一致。 */
    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @Override
    public void afterPropertiesSet() {
        boolean built = beanFactory.containsBeanDefinition(ManagementAccess.SERVLET_CHAIN_BEAN)
                || beanFactory.containsBeanDefinition(ManagementAccess.REACTIVE_CHAIN_BEAN);
        if (built) {
            return;
        }
        Set<String> exposed = ManagementAccess.exposedEndpointsRequiringAuthentication(environment);
        if (exposed.isEmpty()) {
            return;
        }
        String message = UNPROTECTED_EXPOSURE_MESSAGE + "：" + reason() + "，实际暴露了 " + exposed;
        if (!ManagementAccess.developmentProfile(environment)) {
            throw new IllegalStateException(message + "；当前激活的 profile 是 "
                    + Arrays.toString(environment.getActiveProfiles()));
        }
        logger.warn(message);
    }

    private String reason() {
        if (!ManagementAccess.hasCredentials(environment)) {
            return "缺少 mars.observability.management.username 与 password";
        }
        if (!ManagementAccess.authenticationChainSupported(classLoader)) {
            return "classpath 上没有 Spring Security 或 Spring Boot 的 Web 安全模块";
        }
        // 能否建链按任一种 Web 栈的类判断，只有另一种栈的安全类时也会走到这里。
        return "认证链没有装配，部署物关掉了 Web 安全装配、排除了认证链的自动配置，或 classpath 上的安全类属于另一种 Web 栈";
    }
}
