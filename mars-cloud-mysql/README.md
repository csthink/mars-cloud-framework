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

## 数据库方言

分页方言由 MyBatis-Plus 依据 JDBC URL 自动识别。切换到其它数据库时，可通过
`mybatis-plus.configuration` 或数据源配置显式指定方言，业务代码不需要改动。
