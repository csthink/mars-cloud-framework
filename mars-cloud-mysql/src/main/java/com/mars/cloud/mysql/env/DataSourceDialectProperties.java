package com.mars.cloud.mysql.env;

import com.baomidou.mybatisplus.annotation.DbType;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 持久化方言与分页配置。
 *
 * <p><b>为什么要做成可配置</b>：分页方言默认由 JDBC URL 自动识别（MyBatis-Plus 的
 * {@code JdbcUtils.getDbType}）。换成国产数据库时，自动识别可能认不出驱动或 URL 前缀，
 * 这时需要能显式指定，而不必改代码。
 *
 * <p><b>达梦怎么配</b>：MyBatis-Plus 把 {@code DM} 归入 Oracle 方言家族
 * （{@code DbType.oracleSameType()} 包含 DM），所以显式指定 {@code dm} 即可，
 * 分页 SQL 会按 Oracle 语法生成：
 *
 * <pre>
 * mars:
 *   datasource:
 *     dialect-auto-detect: false
 *     db-type: dm
 * </pre>
 *
 * @since 2026-09-19
 */
@ConfigurationProperties(prefix = "mars.datasource")
@Getter
@Setter
public class DataSourceDialectProperties {

    /**
     * 是否让 MyBatis-Plus 依据 JDBC URL 自动识别方言。
     *
     * <p>默认 {@code true}。换成国产数据库且自动识别认不出时，置为 {@code false}
     * 并通过 {@link #dbType} 显式指定。
     */
    private boolean dialectAutoDetect = true;

    /**
     * 显式方言。仅当 {@link #dialectAutoDetect} 为 {@code false} 时生效，且此时必填。
     *
     * <p>取值是 MyBatis-Plus 的 {@link DbType} 枚举名（大小写不敏感），例如
     * {@code mysql} / {@code dm} / {@code kingbase_es} / {@code gauss} / {@code oscar}
     * / {@code gbase} / {@code xu_gu} / {@code high_go}。
     */
    private DbType dbType;

    /**
     * 分页最大条数限制，{@code null} 表示不限制。
     */
    private Long maxLimit;

    /**
     * 页码超出总页数时是否回到首页。
     */
    private boolean overflow;

    /**
     * 是否优化 join 的 count 查询。
     */
    private boolean optimizeJoin = true;
}
