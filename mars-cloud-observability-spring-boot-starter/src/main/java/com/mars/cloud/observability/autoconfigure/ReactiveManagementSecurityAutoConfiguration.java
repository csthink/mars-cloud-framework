package com.mars.cloud.observability.autoconfigure;

import com.mars.cloud.observability.security.ReactiveManagementSecurityConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Import;

/** 响应式栈的管理端点认证链，条件与声明顺序的理由都与 Servlet 栈那条对应。 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.security.autoconfigure.actuate.web.reactive.ReactiveManagementWebSecurityAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration",
        "com.mars.cloud.security.autoconfigure.ReactiveSecurityAutoConfiguration"
})
@ConditionalOnClass(name = {"org.springframework.security.config.web.server.ServerHttpSecurity",
        "org.springframework.boot.security.autoconfigure.actuate.web.reactive.EndpointRequest"})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnManagementCredentials
@Import(ReactiveManagementSecurityConfiguration.class)
public class ReactiveManagementSecurityAutoConfiguration {
}
