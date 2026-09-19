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
| 错误码区间校验 | `ErrorCodeRangeValidator` 启动时校验越界与重复 |
| i18n | `I18nUtil` 读取 `MessageSource`，三级兜底 |
| 请求上下文 | `HttpContextUtilFilter` + `HttpContextUtil`，线程内可取到 `HttpServletRequest` |
| 通用工作线程 | `AbstractWorkThread` + `RequestFacade`，适合把大请求拆成并行子任务 |

统一响应的字段、错误码区间与 i18n 约定见 [../docs/error-code.md](../docs/error-code.md)。

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
    validate: true           # 建议保持开启
    range:
      service: order-service
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

## 自定义覆盖

所有自动装配的 Bean 都带 `@ConditionalOnMissingBean`，业务侧声明同类型 Bean 即可覆盖：

| Bean | 覆盖方式 |
| --- | --- |
| `GlobalResponseAdvice` | 自定义响应包装逻辑 |
| `GlobalExceptionAdvice` | 自定义异常映射 |
| `AppContextHolder` | 自定义环境判定 |
| `ErrorCodeRangeValidator` | 自定义错误码校验 |

个别接口不想被包装时，在类或方法上标注 `@IgnoreResponseAnnotation`。
