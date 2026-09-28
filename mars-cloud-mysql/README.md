# mars-cloud-mysql

MySQL 持久化约定：MyBatis-Plus 配置、基础实体、逻辑删除、审计字段自动填充、ID 生成器。

## 坐标

```xml
<dependency>
    <groupId>com.mars.cloud</groupId>
    <artifactId>mars-cloud-mysql</artifactId>
</dependency>
```

## 提供的能力

| 能力 | 实现 |
| --- | --- |
| 分页 | `MybatisPlusInterceptor` + `PaginationInnerInterceptor` |
| 乐观锁 | `OptimisticLockerInnerInterceptor`（实体加 `@Version` 字段后生效） |
| ID 生成 | 优先接入 `mars-cloud-core-spring-boot-starter` 的雪花算法，否则回退 MyBatis-Plus 默认实现 |
| 审计填充 | `DefaultAuditMetaObjectHandler` 自动填 `create_time` / `create_by` / `modify_time` / `modify_by` |

## 基础实体

```java
@TableName("biz_order")
public class Order extends BaseEntity {
    private String orderNo;
}
```

`BaseEntity` 提供：

| 字段 | 说明 |
| --- | --- |
| `id` | 主键，`ASSIGN_ID`，由框架的 ID 生成器赋值 |
| `createTime` / `createBy` | 插入时自动填充 |
| `modifyTime` / `modifyBy` | 插入与更新时自动填充 |

需要软删除的实体继承 `LogicDeleteEntity`（在 `BaseEntity` 之上增加 `deleted` 与
`delete_time`，`deleted` 带 `@TableLogic`，查询自动过滤已删数据）。

**Entity 不作为对外 API 的 DTO。** 审计字段填充属于持久化契约，改动会影响下游，
需要连带验证。

## 自定义扩展

| 扩展点 | 做法 |
| --- | --- |
| 操作人来源 | 提供自定义 `AuditorProvider`，替换默认的 `system` |
| ID 生成 | 提供自定义 `IdentifierGenerator` Bean |
| 拦截器 | 提供自定义 `MybatisPlusInterceptor` Bean |

## 契约测试

`PersistenceContractTest` 用内存库固定了框架承诺的持久化行为——审计字段填充、
雪花 ID 分配、分页拦截器、以及「实体没有 `version` 字段时填充不报错」。
改 `BaseEntity` / 填充逻辑 / 拦截器时这些测试必须跑绿。

## 数据库方言

分页方言默认由 MyBatis-Plus 依据 JDBC URL 自动识别，**默认不需要配置**。

换成国产数据库、自动识别认不出驱动或 URL 前缀时，可以显式指定：

```yaml
mars:
  datasource:
    dialect-auto-detect: false
    db-type: dm            # MyBatis-Plus 的 DbType 枚举名，大小写不敏感
```

支持的值即 MyBatis-Plus `DbType` 的枚举名：`mysql` / `dm`（达梦）/ `kingbase_es`（人大金仓）
/ `gauss` / `oscar`（神通）/ `gbase` / `xu_gu`（虚谷）/ `high_go` / `ocean_base` …
完整清单见 MyBatis-Plus 文档。

**达梦（`dm`）说明**：MyBatis-Plus 没有为达梦单独提供分页方言实现，而是把 `DM` 归入
**Oracle 方言家族**（`DbType.oracleSameType()` 包含 DM），分页 SQL 因此按 Oracle 语法生成
（`ROWNUM` 包裹）。这在达梦的 Oracle 兼容模式下可用；若部署时用了非兼容模式，
需要在达梦侧开启兼容或改用自定义 `IDialect`。

### 分页参数

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `mars.datasource.max-limit` | 不限制 | 单页最大条数，防止 `size` 被传成极大值 |
| `mars.datasource.overflow` | `false` | 页码超出总页数时是否回到首页 |
| `mars.datasource.optimize-join` | `true` | 是否优化 join 的 count 查询 |

**关闭自动识别却没给 `db-type` 时应用会启动失败**，这是有意的：方言错了要到运行时生成
分页 SQL 才暴露，代价比启动失败大得多。

业务侧声明自己的 `MybatisPlusInterceptor` Bean 即可完全接管，默认的不再创建。

## 连接池与慢查询

自动配置的 DataSource 使用以下 HikariCP 默认值，并在启动时检查实际连接池。自定义 DataSource 必须能通过
`unwrap(HikariDataSource.class)` 提供底层连接池；无法验证时启动失败。

| 配置 | 默认值 | 允许值 |
| --- | --- | --- |
| `spring.datasource.hikari.maximum-pool-size` | `10` | 1 到 10 |
| `spring.datasource.hikari.minimum-idle` | 跟随 maximum-pool-size | 0 到 maximum-pool-size |
| `spring.datasource.hikari.connection-timeout` | `1000` | 251 到 1000 毫秒 |
| `spring.datasource.hikari.validation-timeout` | `500` | 250 到 500 毫秒，且小于 connection-timeout |
| `spring.datasource.hikari.register-mbeans` | `false` | 默认关闭管理接口 |
| `mars.datasource.slow-query-threshold` | `1s` | 正值，最大 1 秒 |

配置刷新先验证新值；非法值被拒绝。DataSource 不参与 Spring Cloud 的属性重新绑定，已有连接池不会在线重建；
调整合法连接池参数后需重启生效。自定义属性重新绑定器需继承 `DataSourcePreservingRebinder`，保留此行为。

超过阈值的 JDBC execute 或 batch 在完成或失败后记录一条 WARN，覆盖 JdbcTemplate、MyBatis 与直接 JDBC 调用。
日志仅含事件类型、耗时、逻辑 DataSource 名、语句类型、批量大小、成功状态及 SQL 模板 SHA-256 指纹；
不记录 SQL、参数、结果集、连接 URL 或异常正文。已存在的 MDC traceId 由结构化日志输出关联。
获取连接、提交、回滚和快速执行不生成慢查询事件。包装保留 unwrap、事务代理、健康检查、连接池指标及关闭行为。
