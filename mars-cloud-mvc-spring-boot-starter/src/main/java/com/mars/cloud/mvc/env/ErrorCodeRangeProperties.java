package com.mars.cloud.mvc.env;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @since 2025-10-29 14:49
 * 错误码区间配置类
 */
@ConfigurationProperties(prefix = "mars.error-code.range")
@Getter
@Setter
public class ErrorCodeRangeProperties {

    private int start;   // 例如 20000
    private int end;     // 例如 21999
    private String service; // 规则引擎Web rule-engine-web
}
