package com.mars.cloud.core.id.snowflake;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @since 2025-10-30 15:36
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "mars.id.snowflake")
public class SnowflakeProperties {

    /**
     * 工作节点ID（0~31）
     */
    @Min(0)
    @Max(31)
    private long workerId = 1;

    /**
     * 数据中心ID（0~31）
     */
    @Min(0)
    @Max(31)
    private long dataCenterId = 1;

    /**
     * 是否启用Snowflake，允许后续切换其它实现时关闭
     */
    private boolean enabled = true;

}
