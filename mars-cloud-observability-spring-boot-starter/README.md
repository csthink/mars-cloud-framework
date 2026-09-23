# mars-cloud-observability-spring-boot-starter

可观测性 starter：链路追踪（Micrometer Tracing 加 OpenTelemetry 桥，OTLP 导出）、Prometheus 指标、
带 `traceId` 的结构化日志，以及管理端点的端口、暴露面与 Basic 认证约定。每个可部署应用都引入它。

## 坐标

```xml
<dependency>
    <groupId>com.mars.cloud</groupId>
    <artifactId>mars-cloud-observability-spring-boot-starter</artifactId>
</dependency>
```

它带来 `spring-boot-starter-actuator`、`spring-boot-starter-opentelemetry`、`micrometer-registry-prometheus`
与 OTLP 导出器的 JDK `HttpClient` 发送实现。已排除 okhttp 发送器（连同 kotlin-stdlib）与 OTLP 指标注册表：
指标只走 Prometheus。部署物删掉自己的 Actuator、指标注册表依赖与 `management.*` 配置，由本 starter 统一给出。

管理端点的认证链需要 Spring Boot 的 Web 安全模块。需要认证的部署物在自己的 POM 里声明：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
```

没有它时 starter 把管理端点收窄为 `health` 与 `info`（见「管理端点」）。

## 运行环境变量

| 变量 | 作用 |
| --- | --- |
| `MARS_MANAGEMENT_USERNAME` / `MARS_MANAGEMENT_PASSWORD` | 管理端点 Basic 认证的唯一账号。对应属性 `mars.observability.management.username` / `password` |
| `OTLP_TRACING_ENDPOINT` | 调用链导出端点，OTLP over HTTP 的完整地址，例如 `http://127.0.0.1:4318/v1/traces`。留空则不导出，应用照常启动 |

显式配置的属性优先于这几个环境变量；属性的宽松绑定形式（如 `MARS_OBSERVABILITY_MANAGEMENT_USERNAME`）同样有效。

## 默认值

starter 在环境末尾追加一个最低优先级的属性源，任何显式配置（环境变量、配置中心、`application.yml`）都覆盖它。

| 属性 | 默认值 | 说明 |
| --- | --- | --- |
| `management.server.port` | 业务端口 + 1000 | 见「管理端口」 |
| `management.endpoints.web.exposure.include` | `health,info,prometheus,metrics,loggers,threaddump,heapdump` | 不能认证时为 `health,info`，见「管理端点」 |
| `management.endpoint.health.show-details` / `show-components` | `when-authorized` | 匿名只看到聚合状态，带凭据可看到组件明细 |
| `management.endpoint.health.probes.enabled` | `true` | 提供 `liveness` 与 `readiness` 探针组 |
| `management.metrics.tags.application` | `${spring.application.name}` | 每个指标带应用名标签 |
| `management.tracing.sampling.probability` | `1.0` | 全量采样；生产按流量在配置中心调低 |
| `management.opentelemetry.tracing.export.otlp.endpoint` | 取 `OTLP_TRACING_ENDPOINT` | Spring Boot 4 的属性名。Boot 3 的 `management.otlp.tracing.endpoint` 在 Boot 4 已废弃，配上去不报错也不生效 |
| `spring.reactor.context-propagation` | `auto` | 请求在 Reactor 线程之间切换后，日志仍带当前的 `traceId`（见「日志」） |
| `logging.structured.format.console` | `ecs` | `mars.observability.logging.console-format=plain` 时不写这一项 |

`mars.observability.*` 自己的配置项：

| 属性 | 默认值 | 说明 |
| --- | --- | --- |
| `mars.observability.management.port-offset` | `1000` | 管理端口相对业务端口的偏移量；`0` 表示与业务端口共用，只允许开发 profile |
| `mars.observability.management.exposure` | 见上表 | 能认证时的暴露清单 |
| `mars.observability.management.restricted-exposure` | `health,info` | 不能认证时的暴露清单 |
| `mars.observability.logging.console-format` | `ecs` | `ecs` 为结构化 JSON；`plain` 为 Spring Boot 的文本格式，用于本机调试与测试 |

开发 profile 由 `mars.env.dev-profiles` 列出，与 mvc starter 使用同一个属性。本 starter 只读应用配置里的值，
不使用 mvc starter 在代码里的默认列表：应用要在配置里显式列出，没有配置时任何 profile 都不算开发环境。

## 管理端口

管理端点不在业务端口上：`management.server.port` 未显式配置时，starter 写入「业务端口 + 偏移量」。
业务端口为 `0`（随机端口）或未配置时不推导，管理端点留在业务端口上。

启动期核验，不满足即启动失败，异常消息写出期望值与实际值：

- 显式配置的 `management.server.port` 必须等于业务端口加偏移量；`0`（让容器分配随机管理端口）除外。
- `management.server.port` 不能为 `-1`：禁用管理端口会让实例监控看不到该实例。
- 偏移量为 `0` 只允许开发 profile。

接入 Nacos 服务发现的应用注册时，starter 把管理端口写进实例元数据 `management.port`。Spring Boot Admin
按这个键找 Actuator 端点；缺少它时会退回业务端口，而业务端口上没有 Actuator 端点。

## 管理端点

两种 Web 栈各有一条只匹配 Actuator 端点的认证链：`health` 匿名可读，其余端点要 Basic 认证，realm 为
`mars-management`。链自带单用户的认证管理器，口令在启动时用 bcrypt 编码，不注册全局
`UserDetailsService`，部署物自己的安全链与认证方式不受影响，两条链共存。

「能认证」指凭据齐备，并且同一种 Web 栈的 Spring Security 与 Spring Boot 的端点匹配器都在 classpath 上。
starter 据此决定暴露面，并在启动期核验：

| 情况 | 暴露面 | 启动期 |
| --- | --- | --- |
| 能认证，认证链已装配 | 完整清单，`health` 之外要认证 | 正常 |
| 缺凭据，能建认证链 | `health,info` | 开发 profile 告警，其他 profile 启动失败 |
| 建不起认证链（没有 Spring Security 或 Web 安全模块） | `health,info` | 告警 |
| 能认证，但 Web 应用的认证链没有装配（部署物关掉了 Web 安全装配） | 完整清单 | 开发 profile 告警，其他 profile 启动失败 |

采集指标与实例监控（Spring Boot Admin）访问管理端点时使用同一组凭据。

## 链路追踪

采用 Micrometer Tracing 的 OpenTelemetry 桥，传播格式为 W3C `traceparent`（Spring Boot 默认）。Feign 调用、
Spring Cloud Gateway 转发与 RocketMQ 消息各自经 Micrometer 的观测传播 trace，本 starter 提供 `Tracer` 与
`Propagator` 的运行时实现与导出。结束的 span 以 OTLP over HTTP（protobuf）送到 `OTLP_TRACING_ENDPOINT`，
`service.name` 取 `spring.application.name`。

## 日志

默认把控制台日志写成 Elastic Common Schema 的 JSON，每行一条：

```json
{"@timestamp":"…","log":{"level":"INFO","logger":"…"},"process":{"pid":1,"thread":{"name":"…"}},
 "service":{"name":"mars-cloud-sample-service","node":{}},"message":"…",
 "traceId":"4bf92f3577b34da6a3ce929d0e0e4736","spanId":"00f067aa0ba902b7","ecs":{"version":"8.11"}}
```

`traceId` 与 `spanId` 是 Micrometer 写入 MDC 的键名，原样成为顶层字段，不改写成 ECS 习惯的 `trace.id`；
日志后端按 `traceId` 关联调用链。响应式栈的请求在 Reactor 线程之间切换，`spring.reactor.context-propagation=auto`
让切换后的日志行仍带当前请求的 `traceId`。

测试 profile 建议设为 `plain`：构建与测试工具按「级别 类名 消息」的文本形态识别告警，JSON 行不匹配。

```yaml
mars:
  observability:
    logging:
      console-format: plain
```
