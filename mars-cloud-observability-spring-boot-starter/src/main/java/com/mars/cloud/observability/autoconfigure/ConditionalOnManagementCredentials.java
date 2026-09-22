package com.mars.cloud.observability.autoconfigure;

import org.springframework.context.annotation.Conditional;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 只有配置了管理端点的用户名与密码时才满足。
 *
 * <p>缺凭据时不建立认证链：建一条没有账号的链只会把所有管理端点锁死，
 * 而收窄暴露面（环境后处理已做）让 health 与 info 仍然可用，更符合探针的需要。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnManagementCredentialsCondition.class)
public @interface ConditionalOnManagementCredentials {
}
