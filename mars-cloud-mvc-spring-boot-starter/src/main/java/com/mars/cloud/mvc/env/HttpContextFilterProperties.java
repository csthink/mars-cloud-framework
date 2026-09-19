package com.mars.cloud.mvc.env;

import jakarta.servlet.DispatcherType;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;


import java.util.EnumSet;
import java.util.List;

/**
 * @since 2025-10-30 10:38
 */
@Getter
@Setter
@ConfigurationProperties("mars.http-context.filter")
public class HttpContextFilterProperties {

    /**
     * 是否启用过滤器
     */
    private boolean enabled = true;

    /**
     * 过滤顺序（默认最高优先级）
     */
    private int order = org.springframework.core.Ordered.HIGHEST_PRECEDENCE;

    /**
     * URL 匹配
     */
    private List<String> urlPatterns = List.of("/*");

    /**
     * 分发类型
     */
    private EnumSet<DispatcherType> dispatcherTypes = EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC);

    /**
     * 过滤器名称
     */
    private String name = "httpContextUtilFilter";
}
