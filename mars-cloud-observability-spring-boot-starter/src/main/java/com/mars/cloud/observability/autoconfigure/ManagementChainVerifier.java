package com.mars.cloud.observability.autoconfigure;

import com.mars.cloud.observability.internal.ManagementAccess;
import com.mars.cloud.observability.security.ManagementCredentials;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.core.env.Environment;

import java.util.Arrays;

/**
 * 在启动期核验 Web 应用的管理端点确实受认证链保护。
 *
 * <p>凭据与建链所需的类都具备时，暴露面已经按有认证放开；此时认证链没有装配，说明部署物关掉了
 * Web 安全装配，指标、日志级别与堆转储在管理端口上无认证可达。非开发 profile 因此拒绝启动，
 * 开发 profile 告警。只对 Web 应用装配：非 Web 应用不开 HTTP 端口，认证链本来就不会装配。
 */
public final class ManagementChainVerifier implements InitializingBean, BeanClassLoaderAware {

    /** 凭据与类都具备，但部署物关掉了 Web 安全装配，链因此没建起来，而暴露面已经放开。 */
    static final String CHAIN_NOT_BUILT_MESSAGE =
            "管理端点的认证链没有装配：部署物关掉了 Web 安全装配，而暴露面已按有认证放开，管理端点不受认证保护";

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
        if (!ManagementAccess.canAuthenticate(environment, classLoader)) {
            return;
        }
        boolean built = beanFactory.containsBeanDefinition(ManagementCredentials.SERVLET_CHAIN_BEAN)
                || beanFactory.containsBeanDefinition(ManagementCredentials.REACTIVE_CHAIN_BEAN);
        if (built) {
            return;
        }
        if (!ManagementAccess.developmentProfile(environment)) {
            throw new IllegalStateException(CHAIN_NOT_BUILT_MESSAGE + "；当前激活的 profile 是 "
                    + Arrays.toString(environment.getActiveProfiles()));
        }
        logger.warn(CHAIN_NOT_BUILT_MESSAGE);
    }
}
