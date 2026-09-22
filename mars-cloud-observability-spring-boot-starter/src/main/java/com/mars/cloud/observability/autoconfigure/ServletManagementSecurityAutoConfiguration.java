package com.mars.cloud.observability.autoconfigure;

import com.mars.cloud.observability.security.ServletManagementSecurityConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Import;

/**
 * Servlet 栈的管理端点认证链。
 *
 * <p>声明顺序排在安全自动配置之后：安全组件的链带
 * {@code @ConditionalOnMissingBean(SecurityFilterChain.class)} 条件，本链必须在它之后登记，
 * 两条链才能共存。类名以字符串给出，避免对安全组件产生编译期依赖。
 *
 * <p>本链要求部署物启用了 Web 安全（{@code HttpSecurity} 可用）。部署物若刻意关掉了自己的
 * Web 安全装配，要把本类一并排除，否则上下文会因为缺少它而启动失败；核验器会在凭据齐备
 * 却没建成链时告警，让这种状态可见。
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.security.autoconfigure.servlet.SecurityAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
        "com.mars.cloud.security.autoconfigure.ServletSecurityAutoConfiguration"
})
@ConditionalOnClass(name = {"org.springframework.security.config.annotation.web.builders.HttpSecurity",
        "org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest"})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnManagementCredentials
@Import(ServletManagementSecurityConfiguration.class)
public class ServletManagementSecurityAutoConfiguration {
}
