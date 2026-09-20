# mars-cloud-feign-spring-boot-starter

Servlet 服务之间的同步读调用 starter：统一 OpenFeign、Spring Cloud LoadBalancer、调用方身份头、
响应信封解码、失败映射、超时和幂等重试边界。

**仅适用于阻塞调用。** WebFlux / Reactor 链路使用由 `WebClient` 驱动的 Spring HTTP Service Client，
不得在事件线程中调用 Feign。

## 引入

版本由 `mars-cloud-dependencies` 统一管理：

```xml
<dependency>
    <groupId>com.mars.cloud</groupId>
    <artifactId>mars-cloud-feign-spring-boot-starter</artifactId>
</dependency>
```

应用精确列出自己的客户端，不做全包扫描：

```java
@SpringBootApplication
@EnableFeignClients(clients = UpmsDecisionClient.class)
public class ExampleApplication {
}
```

内部客户端必须只写 Nacos 服务名，不能配置固定 URL：

```java
@FeignClient(name = "mars-cloud-upms-service", path = "/upms")
interface UpmsDecisionClient {

    @PostMapping("/v1/decision")
    UnifyResponse<DecisionResult> decide(@RequestBody DecisionRequest request);
}
```

## 调用方上下文

在调用前用 `CallerContextHolder` 打开 scope。starter 会删除请求上已有的同名内部头，再写入
当前上下文，调用结束后由 scope 恢复或清理线程状态：

```java
CallerContext caller = new CallerContext(subject, clientId, tenantId);
try (CallerContextHolder.Scope ignored = CallerContextHolder.open(caller)) {
    return client.decide(request);
}
```

固定传播三个头：

| 上下文字段 | 请求头 |
| --- | --- |
| `subject` | `X-Mars-Subject` |
| `clientId` | `X-Mars-Client-Id` |
| `tenantId` | `X-Mars-Tenant-Id` |

`traceparent` 由 Micrometer Tracing 与 `feign-micrometer` 自动传播，starter 不生成 trace id。

## 下游失败映射

每个 Feign client 必须提供且只能提供一个同名 `DownstreamFailureMapper`：

```java
@Component
final class UpmsFailureMapper implements DownstreamFailureMapper {

    @Override
    public String clientName() {
        return "mars-cloud-upms-service";
    }

    @Override
    public RuntimeException map(DownstreamFailure failure) {
        return new UpmsUnavailableException();
    }
}
```

失败分类固定为：

| `DownstreamFailureKind` | 含义 |
| --- | --- |
| `BUSINESS_ENVELOPE` | HTTP 响应是合法失败信封 |
| `HTTP` | 非成功 HTTP 状态 |
| `UNAVAILABLE` | 无实例或连接失败 |
| `TIMEOUT` | 连接或读取超时 |
| `MALFORMED_RESPONSE` | 空响应、坏 JSON 或不是统一信封 |

失败对象不携带原始响应体，下游 `message` 也不会进入上游异常。mapper 缺失、重复或返回 `null`
都会明确失败，不会把下游错误码直接暴露给调用方。

## 固定默认值与启动期守卫

| 配置 | 默认值 / 上限 |
| --- | --- |
| Feign connect timeout | 1000 ms |
| Feign read timeout | 3000 ms |
| Feign `Retryer` | `Retryer.NEVER_RETRY` |
| 同实例重试 | 0 |
| 下一实例重试 | 最多 1 次 |
| 写请求重试 | 禁止 |
| 可重试状态码 | 502、503、504 |

应用可以关闭重试、减少次数或收紧超时，不能放宽。启动期会拒绝固定 URL、自定义 Feign
`Retryer`、超出上限的超时、同实例重试、写请求重试和扩大的状态码范围。

## 验证

```bash
mvn -pl mars-cloud-feign-spring-boot-starter -am test
```

契约测试覆盖身份头覆盖与清理、成功与失败信封、坏响应、无实例、连接失败、超时、mapper
唯一性、GET 换实例重试、POST 不重试、配置守卫，以及 Micrometer W3C `traceparent` 传播。
