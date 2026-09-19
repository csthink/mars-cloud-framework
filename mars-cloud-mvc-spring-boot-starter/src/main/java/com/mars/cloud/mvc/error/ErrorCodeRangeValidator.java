package com.mars.cloud.mvc.error;

import com.mars.cloud.common.error.ErrorCode;
import com.mars.cloud.mvc.env.ErrorCodeRangeProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @since 2025-10-29 14:52
 * 错误码区间校验器
 */
@RequiredArgsConstructor
@EnableConfigurationProperties(ErrorCodeRangeProperties.class)
public class ErrorCodeRangeValidator implements SmartInitializingSingleton {

    /**
     * Spring 会自动注入项目中所有实现了 ErrorCodeRegistrar 的 Bean
     */
    private final List<ErrorCodeRegistrar> registrars;
    // 来自 application.yml
    private final ErrorCodeRangeProperties props;

    @Override
    public void afterSingletonsInstantiated() {
        Set<Integer> seen = new HashSet<>();
        for (ErrorCodeRegistrar r : registrars) {
            for (ErrorCode c : r.codes()) {
                int code = c.getCode();

                // 1) 区间校验
                if (code < props.getStart() || code > props.getEnd()) {
                    throw new IllegalStateException(String.format("错误码 %d 不在服务[%s]允许区间 [%d, %d] 内", code, props.getService(), props.getStart(), props.getEnd()));
                }

                // 2) 本服务内重复校验
                if (!seen.add(code)) {
                    throw new IllegalStateException(String.format("服务[%s] 内部错误码重复: %d", props.getService(), code));
                }
            }
        }
    }
}
