package com.mars.cloud.observability.autoconfigure;

import com.mars.cloud.observability.security.ServletManagementSecurityConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Import;

/**
 * Servlet 栈的管理端点认证链。
 *
 * <p>声明顺序排在 Spring Boot 的两条默认安全链与安全组件的链之后：它们都只在还没有任何安全链时装配，
 * 本链先登记会让它们退出，部署物的业务请求就不再受保护。类名以字符串给出，避免对这些模块产生编译期依赖。
 *
 * <p>本链要求部署物启用了 Web 安全（{@code HttpSecurity} 可用）。部署物若刻意关掉了自己的
 * Web 安全装配，要把本类一并排除，否则上下文会因为缺少它而启动失败。排除后管理端点没有认证链，
 * 生效的暴露清单只能包含 health 与 info，否则 {@link ManagementChainVerifier} 在非开发 profile
 * 拒绝启动、在开发 profile 告警。
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration",
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
