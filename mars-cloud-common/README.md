# mars-cloud-common

纯工具与模型库：**不依赖任何 Spring / Servlet / Swagger 组件**。

它是框架里唯一能被所有技术栈复用的模块——Servlet 业务服务、响应式网关都用得到，
所以约束很硬：任何 Spring 依赖进来，这个模块就失去了复用价值。

## 坐标

```xml
<dependency>
    <groupId>com.mars.cloud</groupId>
    <artifactId>mars-cloud-common</artifactId>
</dependency>
```

## 内容

| 包 | 内容 |
| --- | --- |
| `com.mars.cloud.common.response` | 统一响应信封 `UnifyResponse` |
| `com.mars.cloud.common.error` | 错误码契约 `ErrorCode` |
| `com.mars.cloud.common.exception` | 与实现无关的通用异常，目前是 `LockFailureException` |
| `com.mars.cloud.common.domain.util` | 通用工具：`IDGenerator`、`JsonUtil`、`TimeUtils` |

### UnifyResponse

统一响应信封。刻意做成纯 POJO，让 Servlet 栈与响应式栈能共用同一种响应格式。
信封**不做 i18n 查找**——文案由各栈的 advice 层解析完成后传入。

```java
// 成功
UnifyResponse<OrderVO> ok = UnifyResponse.success(orderVO);

// 失败
UnifyResponse<Void> bad = UnifyResponse.fail(66001, "订单不存在");
```

成功响应里 `code` / `message` 为 `null`，且不参与序列化：

```json
{ "success": true, "result": { "id": 123 } }
```

### ErrorCode

错误码契约，只有一个 `getCode()` 方法。枚举实现即可，文案 key 由默认方法给出
（`error.code.<数字>`）。完整约定见 [../docs/error-code.md](../docs/error-code.md)。

### LockFailureException

分布式锁获取失败的异常类型。**框架不绑定任何具体锁实现**（不引 lock4j，也不引
Redisson）：谁实现锁，谁在获取失败时抛这个异常，Servlet 侧的全局异常处理会把它
映射成 HTTP 409 + 统一信封。

### JsonUtil

基于 **Jackson 3**（`tools.jackson.*`）的 JSON 工具，提供 `toJson` / `toBean` /
`toMap` 等常用方法，并预置三个实例（默认、忽略空值、严格）。

注意 Jackson 3 的注解包名没有变，仍是 `com.fasterxml.jackson.annotation`。

## 依赖

只依赖 `commons-lang3`、`commons-codec`、`commons-collections4`、
`tools.jackson.core:jackson-databind`（Jackson 3）、`jackson-annotations`、`slf4j-api`；
`lombok` 为 `provided`，不向下游传递。

**不要在这里引入 Spring。** 也不要引 `spring-boot-starter-jackson`——它会通过
`spring-boot-starter` 把 `spring-core` / `spring-context` / 日志实现一起带进来，
本模块的「无 Spring」约束就没了。需要 Spring 的能力请放到对应的 starter 里。
