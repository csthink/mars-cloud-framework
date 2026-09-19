package com.mars.cloud.mvc.autoconfigure;

import com.mars.cloud.mvc.env.HttpContextFilterProperties;
import com.mars.cloud.mvc.filter.HttpContextUtilFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * @since 2025-10-30 10:38
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(HttpContextUtilFilter.class)
@EnableConfigurationProperties(HttpContextFilterProperties.class)
public class HttpContextUtilFilterAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "mars.http-context.filter", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(name = "httpContextUtilFilterRegistration") // 避免应用侧自定义时重复注册
    public FilterRegistrationBean<HttpContextUtilFilter> httpContextUtilFilterRegistration(HttpContextFilterProperties props) {
        FilterRegistrationBean<HttpContextUtilFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new HttpContextUtilFilter());
        reg.setName(props.getName());
        reg.setOrder(props.getOrder());
        reg.setDispatcherTypes(props.getDispatcherTypes());
        reg.addUrlPatterns(props.getUrlPatterns().toArray(String[]::new));
        return reg;
    }
}
