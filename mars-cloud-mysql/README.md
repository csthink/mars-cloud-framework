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
