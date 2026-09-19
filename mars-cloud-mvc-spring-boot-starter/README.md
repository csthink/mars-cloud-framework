# mars-cloud-mvc-spring-boot-starter

Servlet 栈的 Web 横切能力：统一响应、全局异常、错误码区间校验、i18n、请求上下文。

**仅适用于 Servlet 栈。** 响应式网关请复用 `mars-cloud-common` 的信封与错误码契约，
另行实现过滤器与异常处理。

## 坐标

```xml
<dependency>
    <groupId>com.mars.cloud</groupId>
    <artifactId>mars-cloud-mvc-spring-boot-starter</artifactId>
</dependency>
```

## 提供的能力

| 能力 | 实现 |
| --- | --- |
| 统一响应 | `GlobalResponseAdvice` 把返回值包成 `UnifyResponse` |
| 全局异常 | `GlobalExceptionAdvice` 把异常映射成信封 + HTTP 状态码 |
| 错误码区间校验 | `ErrorCodeRangeValidator` 启动时校验归属、越界、重复、段间重叠 |
| i18n | `I18nUtil` 读取 `MessageSource`，三级兜底 |
| 请求上下文 | `HttpContextUtilFilter` + `HttpContextUtil`，线程内可取到 `HttpServletRequest` |
| 通用工作线程 | `AbstractWorkThread` + `RequestFacade`，适合把大请求拆成并行子任务 |
| 转换器协商 | `HttpMessageConverterAutoConfiguration` 修正 String 返回值的 `Content-Type` |
| 接口文档 | 随依赖带入 springdoc-openapi，业务服务无需再声明 |

统一响应的字段、错误码区间与 i18n 约定见 [../docs/error-code.md](../docs/error-code.md)。

## 异常映射一览

| 异常 | HTTP | 说明 |
| --- | --- | --- |
| `HttpException` 及其子类 | 按异常自带状态码 | 业务异常基类，`BusinessException` / `FailedException` 为 200 |
| `AuthenticationException` | 401 | |
| `AuthorizationException` | 403 | |
| `BadRequestException` | 400 | |
| `ResourceNotFoundException` | 404 | |
| `ConflictException` | 409 | |
| `LockFailureException` | 409 | **框架自带**的锁失败异常，见下 |
| 参数校验类异常 | 400 | 含 `MethodArgumentNotValidException` / `BindException` / `ConstraintViolationException` / `HandlerMethodValidationException` / `MissingServletRequestParameterException` / `MethodArgumentTypeMismatchException` / `IllegalArgumentException` |
| `NoHandlerFoundException` | 404 | 找不到处理器 |
| `HttpRequestMethodNotSupportedException` | 405 | 方法不支持 |
| `HttpMediaTypeNotAcceptableException` | 406 | 无法按 `Accept` 协商 |
| 未预期异常 | 500 | 兜底，错误码 500 |

框架兜底用的状态码（400 / 404 / 405 / 406 / 500）**不经错误码区间校验**，
详见 [../docs/error-code.md](../docs/error-code.md)。

### 分布式锁失败

框架**不引入任何锁实现**。谁实现锁，谁在获取失败时抛
`com.mars.cloud.common.exception.LockFailureException`，本 starter 会把它映射成
HTTP 409 + 统一信封：

```java
if (!lock.tryLock(key)) {
    throw new LockFailureException("资源正在处理中：" + key);
}
```

不要为了让这个分支工作而引入第三方锁组件——它们会把无条件自动装配带进你的
classpath，反而破坏应用启动。

## 配置项

```yaml
mars:
  # 统一响应包装
  mvc:
    response-wrapper:
      enabled: true          # 关掉则原样返回业务对象
    exception-advice:
      enabled: true

  # 错误码区间校验
  error-code:
    validate: true                    # 建议保持开启
    framework-layers: [ common, mvc ]  # 用到的框架层，区间取自框架分配表
    ranges:                            # 业务服务自己那一段（多服务各自声明、互不重叠）
      # business 区段是 66000–99999，由所有业务服务共用；
      # 这里声明的只是本服务占用的那一段，不要照抄成整个区段。
      - owner: business
        start: 66000
        end: 66999

  # 请求上下文过滤器
  http-context:
    filter:
      enabled: true
      order: -2147483648     # 默认最高优先级
      url-patterns: [ "/*" ]
      dispatcher-types: [ REQUEST, ASYNC ]

  # 哪些 profile 视为开发/测试环境（影响异常响应是否回带调试详情）
  env:
    dev-profiles: [ local, dev, test, testing ]

  # 错误码文案兜底（不写 i18n 资源时的简易方式）
  # codes:
  #   "[66001]": 订单不存在
```

### 开发环境才会回带的调试详情

非开发环境（按 `mars.env.dev-profiles` 判定）下，失败响应的 `result` 恒为 `null`。
开发环境下会带上异常详情、请求 IP、方法、URI 与请求头，方便排查。**上线前确认
profile 没有把 `dev` 之类误带上。**

## i18n 配置

```yaml
spring:
  messages:
    basename: i18n/error-code     # 必需，缺它会退化成裸错误码
```

三份资源同步维护：`i18n/error-code.properties`、`_zh_CN`、`_en_US`，key 形如
`error.code.66001`。

## 请求上下文与工作线程

`HttpContextUtil` 在同一请求线程内提供 `HttpServletRequest` / `HttpServletResponse`：

```java
HttpServletRequest request = HttpContextUtil.getRequest();
```

需要把一个请求拆成多个并行子任务时，继承 `AbstractWorkThread` 并实现 `RequestFacade`；
子线程会继承请求上下文与 MDC，避免日志串线。线程池为进程内单例，队列满时由调用方
线程直接执行（`CallerRunsPolicy`）。

## String 返回值

控制器直接返回 `String` 时，`GlobalResponseAdvice` 会区分两种情况：

| 情况 | 行为 | Content-Type |
| --- | --- | --- |
| 本身就是合法 JSON | 原样透传（不再二次序列化） | `application/json` |
| 普通字符串 | 包成统一信封返回 JSON | `application/json` |
| `produces = "text/plain"` | 原样返回 | `text/plain` |

starter 会自动把 `JsonStringHttpMessageConverter` 插到消息转换器链首，保证前两种情况的
`Content-Type` 是 `application/json`；显式要求 `text/plain` 的接口不受影响。
细节见 [../docs/architecture.md](../docs/architecture.md) 的「已知行为与偏差」。

## 接口文档

引入本 starter 即已带入 **springdoc-openapi**（版本由 BOM 管），无需在业务服务里重复声明：

| 端点 | 内容 |
| --- | --- |
| `/v3/api-docs` | OpenAPI 3 JSON |
| `/v3/api-docs.yaml` | OpenAPI 3 YAML |
| `/swagger-ui/index.html` | Swagger UI 页面 |

常见配置：

```yaml
springdoc:
  api-docs:
    path: /v3/api-docs
  swagger-ui:
    path: /swagger-ui.html
    # 生产环境建议关闭
    enabled: true
```

⚠️ **版本纪律**：springdoc 必须停在 `3.0.x`。`3.1.x` 的 parent 是 Spring Boot 4.1.0，
与本项目的 Boot 4.0.x 不同线；升级前先核对 Boot 版本。

## 自定义覆盖

所有自动装配的 Bean 都带 `@ConditionalOnMissingBean`，业务侧声明同类型 Bean 即可覆盖：

| Bean | 覆盖方式 |
| --- | --- |
| `GlobalResponseAdvice` | 自定义响应包装逻辑 |
| `GlobalExceptionAdvice` | 自定义异常映射 |
| `AppContextHolder` | 自定义环境判定 |
| `ErrorCodeRangeValidator` | 自定义错误码校验 |

个别接口不想被包装时，在类或方法上标注 `@IgnoreResponseAnnotation`。
