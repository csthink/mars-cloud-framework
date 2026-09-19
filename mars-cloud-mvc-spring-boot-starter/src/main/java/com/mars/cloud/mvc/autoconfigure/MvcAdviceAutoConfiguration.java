package com.mars.cloud.mvc.autoconfigure;

import com.mars.cloud.mvc.advice.GlobalExceptionAdvice;
import com.mars.cloud.mvc.advice.GlobalResponseAdvice;
import com.mars.cloud.mvc.error.ExceptionCodeConfiguration;
import com.mars.cloud.mvc.util.AppContextHolder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * @since 2025-10-30 13:10
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
//@ConditionalOnClass({org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice.class,
//        org.springframework.web.bind.annotation.RestControllerAdvice.class})
public class MvcAdviceAutoConfiguration {

    /**
     * 提供默认的全局响应封装 Advice
     * - 允许应用侧通过自定义同类型 Bean 覆盖（@ConditionalOnMissingBean）
     * - 可再加开关属性控制是否启用
     */
    @Bean
    @ConditionalOnMissingBean(GlobalResponseAdvice.class)
    @ConditionalOnClass(org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice.class)
    @ConditionalOnProperty(name = "mars.mvc.response-wrapper.enabled", havingValue = "true", matchIfMissing = true)
    public GlobalResponseAdvice globalResponseAdvice() {
        return new GlobalResponseAdvice();
    }


    /**
     * 提供默认的全局异常封装 Advice
     *
     * @param holder
     * @param cfg
     * @return
     */
    @Bean
    @ConditionalOnClass(org.springframework.web.bind.annotation.RestControllerAdvice.class)
    @ConditionalOnMissingBean(GlobalExceptionAdvice.class)
    @ConditionalOnProperty(name = "mars.mvc.exception-advice.enabled", havingValue = "true", matchIfMissing = true)
    public GlobalExceptionAdvice globalExceptionAdvice(AppContextHolder holder,
                                                       ExceptionCodeConfiguration cfg) {
        return new GlobalExceptionAdvice(holder, cfg);
    }
}
