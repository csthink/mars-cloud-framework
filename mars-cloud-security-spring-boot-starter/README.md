# mars-cloud-security-spring-boot-starter

为 Servlet 和 WebFlux API 提供 JWT 身份验证、请求身份上下文和方法权限检查。签名验证使用 Spring Security 的标准 Nimbus 解码器，不依赖具体身份提供方。

## 接入

宿主自行提供 Web 运行时。Servlet 权限查询同时引入 `mars-cloud-security-feign`；WebFlux 权限查询需要 `spring-webflux`、Reactor Netty 和 Spring Cloud LoadBalancer。建议宿主使用 `spring-boot-starter-cache` 与 Caffeine 配置 LoadBalancer 缓存。

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${MARS_SECURITY_ISSUER_URI}
          jwk-set-uri: ${MARS_SECURITY_JWK_SET_URI:}
mars:
  security:
    audience: ${spring.application.name}
    authorization:
      enabled: true
```

issuer 和 audience 必填。未指定 jwk-set-uri 时使用标准 issuer 元数据发现；指定时仍验证 issuer。端点必须使用 HTTPS，只有 `local` 或 `test` profile 允许回环 HTTP。

仅接受 `Authorization: Bearer`。固定使用 RS256，要求 exp、sub、client_id、tenant_id；sub 与 client_id 必须是无首尾空白的非空字符串，tenant_id 必须为 `default`。验证 aud、iss、exp 和可选 nbf，时间偏差为 60 秒。复用 JWKS 缓存和未知 kid 刷新，公钥请求连接上限 1 秒、响应上限 3 秒。

默认过滤链只匿名开放 `/actuator/health` 及其子路径，使用无状态 Bearer API，关闭表单、Basic 和 CSRF。只需要身份验证的资源服务可设置 `authorization.enabled=false`；权限注解仍拒绝访问，JWT 验证仍然执行。此默认链适用于不使用 Cookie 认证的 API。

## 身份与方法权限

Servlet 从 `CallerContextHolder.current()` 获取 `CallerContext(subject, clientId, tenantId)`，过滤器在请求结束时清理，并支持 Callable 和异步重新派发。自建线程不能继承请求身份。

WebFlux 用 `ReactiveCallerContext.current()` 读取 Reactor Context，线程切换不会改用 Servlet ThreadLocal。令牌原文只保存在 Spring Security 上下文中，身份值对象不包含令牌。入站 `X-Mars-*`、roles、scope 或部门声明不产生平台管理权限。

```java
@PreAuthorize("@marsAuthorization.allowed('view','demo:view:domain:kubernetes-ops')")
public Result view() { /* 业务操作 */ }
```

WebFlux 方法返回 `Mono` 或 `Flux`，同一表达式调用返回 `Mono<Boolean>` 的权限 bean。主体始终取自当前已认证身份。action/resource 应由服务声明或根据业务对象确定。

权限服务固定经 `mars-cloud-upms-service` 调用 `POST /upms/v1/decision`。请求体为 `caller_id/action/resource`；只接受完整的成功信封与 allow/deny 结果。调用不重试、不缓存决策、不跟随重定向。下游令牌使用当前用户的已验证令牌，因此它的 audience 必须同时覆盖调用服务与 UPMS。

## 自定义过滤链

宿主定义自己的 `SecurityFilterChain` 或 `SecurityWebFilterChain` 时，默认链退让。注入 `MarsServletSecurityConfigurer` 或 `MarsReactiveSecurityConfigurer`，调用 `configure(http)` 安装相同的 JWT、身份上下文与错误处理，再由宿主声明路径规则并调用 `build()`。宿主必须通过集成测试证明其全部入口的保护范围。

Servlet 方法异常由独立 advice 写信封，Reactive 在安全过滤链内完成。安全响应不附带请求头和下游正文。宿主在 `i18n/error-code*.properties` 中维护下列代码的文案：

| HTTP | 代码 | 情形 |
| --- | --- | --- |
| 401 | 62001 | 缺少 Bearer 令牌 |
| 401 | 62002 | 令牌、声明或公钥验证失败 |
| 403 | 62003 | 权限判断拒绝 |
| 503 | 62004 | 权限服务不可用或超时 |
| 502 | 62005 | 权限响应无效或下游拒绝调用凭据 |
| 403 | 62006 | 请求主体与认证主体不一致 |
| 401 | 62007 | 令牌对应的会话已被撤销（设备被踢出或账号被禁用），令牌本身仍在有效期内 |

Servlet 宿主的 ErrorCodeRegistrar 汇集 `SecurityErrorCode.values()`，并声明 `framework-layers: [common, mvc, security]`。组件不依赖 MVC starter；WebFlux 宿主自行验证错误码没有重复。
