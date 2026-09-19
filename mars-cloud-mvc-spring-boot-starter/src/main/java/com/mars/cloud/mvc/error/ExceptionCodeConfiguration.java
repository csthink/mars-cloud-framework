package com.mars.cloud.mvc.error;


import lombok.Getter;
import lombok.Setter;
import org.apache.commons.collections4.MapUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * @since 2025-10-29 17:32
 * 作为 MessageSource 的兜底：从本服务的 config/exception-code.properties 读取文案
 * 仅维护本服务的错误码 -> 文案映射
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "mars")
public class ExceptionCodeConfiguration {


    /**
     * key: 错误码（整数）
     * val: 文案（本地化默认值；建议中文或英文默认）
     * <p>
     * 对应 properties 写法：mars.codes[20001]=应用名称重复
     */
    private Map<Integer, String> codes = new HashMap<>();

    public String getMessage(int code, String defaultMessage) {
        if (MapUtils.isEmpty(codes)) {
            return defaultMessage;
        }
        return codes.getOrDefault(code, defaultMessage);
    }

    /**
     * 语义更清晰的别名
     */
    public String getMessageOrDefault(int code, String defaultMessage) {
        return getMessage(code, defaultMessage);
    }
}
