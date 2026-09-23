# 架构设计

本文说明 mars-cloud 框架的模块划分、依赖方向与设计取舍。

## 目标

给基于 Spring Cloud 的微服务提供一套**统一地基**，让下面这些横切关注点只实现一次：

- 对外响应格式与异常语义（统一响应信封、错误码、i18n）
- 持久化约定（基础实体、逻辑删除、审计字段、ID 生成）
- 服务治理能力的接入方式（注册配置、网关、服务调用、限流、认证授权）

## 仓与模块

框架库集中在 `mars-cloud-framework`，可部署应用集中在配套的服务仓。两者是**平级**的
独立仓库，服务仓单向依赖框架库发布出来的 artifact。使用本框架不需要任何其他仓库。

```
mars-cloud-framework/            # 本仓：只出 jar，不部署
├── mars-cloud-dependencies/     # 依赖管理 BOM
├── mars-cloud-common/           # 纯工具与模型
├── mars-cloud-core-spring-boot-starter/
├── mars-cloud-mvc-spring-boot-starter/
├── mars-cloud-mysql/
├── mars-cloud-nacos-spring-boot-starter/
├── mars-cloud-feign-spring-boot-starter/
├── mars-cloud-security-spring-boot-starter/
├── mars-cloud-security-feign/
├── mars-cloud-security-test-support/
├── mars-cloud-rocketmq-spring-boot-starter/
├── mars-cloud-observability-spring-boot-starter/
└── （后续：sentinel starter）
```

## 划分三规则

模块边界不靠约定俗成，靠下面三条规则判定：

| 规则 | 内容 | 判据 |
| --- | --- | --- |
| 一、按可部署性切 | 可部署应用（有主类、有端口）与服务仓放一起；只出 jar 的库放本仓 | 这个模块自己启动吗？ |
| 二、按消费者数量切 | 第一个消费者出现时写在业务服务里；**第二个真实消费者出现**才下沉到框架 | 现在有几个服务要用它？ |
| 三、依赖单向 | 服务 → 框架；框架内部只向下依赖 `dependencies` / `common`；**服务之间不加编译期依赖**，只走 HTTP 调用 | 会不会形成环？ |

规则三的直接收益：服务之间本来就没有编译期耦合，将来真要拆库拆服务是零成本。

## 依赖方向

```
                 ┌───────────────────────────┐
                 │  mars-cloud-dependencies  │  ← 版本唯一出口（BOM）
                 └─────────────┬─────────────┘
                               │ 所有模块的 parent
   ┌──────────────────┬────────┼──────────┬─────────────┬────────────────┐
   ▼                  ▼        ▼          ▼             ▼                ▼
common ◄──────── core-starter ◄─── mvc-starter       mysql         nacos-starter    feign-starter
（无 Spring）      （自动装配）      （Web 横切）    （持久化）      （注册配置）      （同步读调用）
```

- `common` **不依赖任何 Spring / Servlet / Swagger**。它是唯一能被所有栈复用的模块，
  包括 WebFlux 栈的网关。
- starter 只**向下**依赖 `common`、`dependencies` 与更底层的 starter
  （例如 mvc starter 依赖 core starter 以获得分布式 ID）；**不允许反向依赖**，
  也不允许出现环。
- `security starter` 依赖 `common` 与 Spring Security 标准资源服务器库，按宿主选择 Servlet 或 Reactive；
  不依赖 MVC starter、Feign 或具体 IdP。`security-feign` 单向依赖 security starter 与 Feign starter，
  供 Servlet 宿主使用；`security-test-support` 只供测试使用，不进入部署包。
- `rocketmq starter` 依赖 `common`、Spring Cloud Stream 与 Spring Cloud Alibaba 的 RocketMQ binder；
  消息信封、消息头名与命名规则在 `common`，供生产方与消费方共用；不依赖 MVC、Feign 或 security starter。
- `observability starter` 依赖 Actuator、Micrometer Tracing 的 OpenTelemetry 桥与 Prometheus 注册表，不依赖 `common`
  与其他 starter；Spring Boot 的 Web 安全模块与 Nacos 服务发现是可选依赖，存在时才装配管理端点认证链与实例元数据。
  Feign、网关与 RocketMQ 的 trace 传播各自经 Micrometer 完成，它们不依赖本 starter。
- 各模块的 `<parent>` 都是 `mars-cloud-dependencies`，根聚合 POM 只聚合、不做 parent。

## 横切能力设计

### 统一响应：信封必须下沉到 common

网关是 WebFlux 栈，用不了 Servlet 的 `ResponseBodyAdvice`。如果信封 POJO 只存在于
Servlet starter 里，外部调用方就会看到「网关一种错误格式、业务服务另一种」的分裂。

因此：**信封 POJO 放 `common`，各栈各自实现填充逻辑。**

| 层 | 实现 |
| --- | --- |
| `common` | `UnifyResponse` 信封、`ErrorCode` 契约（纯 POJO / 纯接口） |
| Servlet 栈 | `mars-cloud-mvc-spring-boot-starter` 的 `ResponseBodyAdvice` + `@ControllerAdvice` |
| WebFlux 栈 | 网关侧自行实现 `ErrorWebExceptionHandler`，复用 `common` 的信封；业务服务的响应原样透传，不二次包装 |

信封本身**不做 i18n 查找**——文案由各栈的 advice 层解析完成后传入。这样 `common`
才能保持无 Spring 依赖。

**「无 Spring 依赖」的判定标准是依赖树，不是 POM 里那几行。** 例如
`spring-boot-starter-jackson` 看着只是 JSON，实际会经 `spring-boot-starter` 把
`spring-core` / `spring-context` / 日志实现一起带进来——所以 `common` 直接依赖
Jackson 3 的 artifact，并因此在 BOM 里单独钉住它的版本。

### 框架不绑定具体锁实现

分布式锁失败的异常类型由 `common` 提供（`LockFailureException`），Servlet 侧的全局
异常处理把它映射成 HTTP 409 + 统一信封。**但框架不引入 lock4j、Redisson 或任何锁实现。**

原因不是洁癖：第三方锁组件（连最轻量的 core 包也一样）会把**无条件自动装配**带进使用方
的 classpath，从而要求使用方提供锁执行器，否则应用启动失败。框架为了一个异常类型
就把使用方的启动过程交给第三方的自动配置，代价不成比例。谁实现锁，谁负责抛这个异常。

### JSON：Jackson 3

Boot 4 带的是 Jackson 3，包名从 `com.fasterxml.jackson.*` 变为 `tools.jackson.*`
（**注解例外**，仍在 `com.fasterxml.jackson.annotation`）。两处必须留意的差异：

- `ObjectMapper` 不再支持 `setConfig` / `configure` 一类的可变配置，改为
  `JsonMapper.builder()` 一次性构建
- 异常基类变为 `JacksonException`，且**不再受检**
- `JsonParser.Feature` 由 `JsonReadFeature` / `JsonWriteFeature` 取代

### 错误码：区间化，启动即校验

每个模块与每个服务声明自己的错误码区间，框架在启动时校验「不越界 + 不重复」，
把冲突从运行期提前到启动期。分配表与配置方式见 [error-code.md](error-code.md)。

### 持久化：约定优于配置

`mars-cloud-mysql` 把 `BaseEntity`、逻辑删除、审计字段自动填充、ID 生成器固化下来，
业务侧只写实体与 Mapper。**Entity 不作为对外 API 的 DTO。**

### Nacos：接入必须满足同一套命名与失败语义

`mars-cloud-nacos-spring-boot-starter` 同时引入 Nacos Discovery 与 Config，并在启动期校验：

- 每个环境使用独立、非空的 Namespace ID，Config 与 Discovery 必须相同
- 共享配置固定为 `COMMON/shared-common.yaml`
- 应用配置固定为 `DEFAULT_GROUP/<spring.application.name>.yaml`
- 共享配置先导入，应用配置后导入，且两层显式开启刷新
- 两层都禁止 `optional:`，避免配置中心不可用时带着不完整配置继续启动

Spring Cloud Alibaba 2025.1.x 使用 `spring.config.import`，不使用 `bootstrap.yml`。
测试或明确不接入 Nacos 的进程必须同时关闭 Config 与 Discovery。配置模板见模块 README。

### 服务间同步读：单层幂等重试与调用方失败映射

`mars-cloud-feign-spring-boot-starter` 只服务 Servlet / 阻塞调用。内部客户端只写服务名，经
Spring Cloud LoadBalancer 与注册中心选择实例；固定 URL 会在启动期被拒绝。WebFlux 链路使用
由 `WebClient` 驱动的 Spring HTTP Service Client，不能在事件线程中调用 Feign。

重试只由 LoadBalancer 执行，Feign 自身固定为 `Retryer.NEVER_RETRY`，避免两层重试相乘。
GET 最多换一个实例重试一次，写请求不重试；连接与读取超时的上限分别是 1 秒和 3 秒。

调用方用 `CallerContextHolder` 在阻塞线程中提供身份，starter 覆盖写入三个内部请求头。
`traceparent` 由 Micrometer Tracing 自动传播。下游成功与失败都使用统一信封，但失败不会把
下游的 message 或原始响应体传给上游；每个客户端必须通过一个 `DownstreamFailureMapper`
把失败翻译为调用方自己的异常和错误码。

### JWT 身份验证与方法权限

`mars-cloud-security-spring-boot-starter` 验证 Bearer JWT 的 RS256 签名、issuer、audience、有效期和身份字段，
从已验证的身份建立请求上下文。Servlet 使用有明确关闭范围的 `CallerContextHolder.Scope`，
Reactive 使用 Reactor Context；入站身份头不能代替令牌验证。

权限检查通过 UPMS 决策接口完成：Servlet 使用 `mars-cloud-security-feign`，Reactive 使用
WebClient 驱动的 HTTP Service Client。两个客户端都按固定服务名调用，只携带当前用户令牌，
禁止重定向和重试。拒绝决策阻止受保护方法，调用失败也不继续执行。
配置、错误码和自定义过滤链接入见 [security starter 使用说明](../mars-cloud-security-spring-boot-starter/README.md)。

### 事件消息：事务发送、消费约定与运行环境前缀

`mars-cloud-rocketmq-spring-boot-starter` 只提供 Spring Cloud Stream 的函数式模型：生产者经 `StreamBridge`，
消费者是 `Consumer<Message<EventEnvelope<T>>>` bean。消息体固定为 `common` 的 `EventEnvelope`，
主题名 `<domain>-event`、消费组名 `<应用名>-<主题>`、生产者组名以应用名开头且进程内唯一、tag 为事件名，由启动期校验强制；
运行环境前缀由环境变量给出，starter 加到全部主题、消费组与生产者组上，重试与死信主题随消费组派生。

写库与发消息一律用 RocketMQ 事务消息：`TransactionalEventPublisher` 先发半消息，再在调用线程执行调用方的本地事务，
正常返回提交、抛异常回滚；broker 回查交给每个主题唯一的 `TransactionStateChecker` 按业务表状态答复。事务消息忽略延迟档，
延迟消息由 `PlainEventPublisher` 经普通生产者发送，可以登记到当前事务提交后再发，提交后发送前的丢失由对账任务兜底。

进程内重试关闭，消费失败交给 RocketMQ 按 `maxReconsumeTimes` 重投，超过进入死信主题；`IdempotentEventHandler`
用 `event_id` 登记吞掉重复投递。调用方身份的三个内部头与 `traceparent` 随消息传递，消费侧在函数执行期间还原。
broker 关闭自动创建时，starter 在启动期核验或创建主题与消费组，缺失即启动失败并给出 `mqadmin` 命令。
接入方式见 [rocketmq starter 使用说明](../mars-cloud-rocketmq-spring-boot-starter/README.md)。

### 可观测性：一条 trace 贯穿进程，日志按 traceId 关联

`mars-cloud-observability-spring-boot-starter` 让每个可部署应用具备同一套可观测性：Micrometer Tracing 经
OpenTelemetry 桥以 W3C `traceparent` 传播，结束的 span 以 OTLP over HTTP 导出；控制台日志是 Elastic Common Schema
的 JSON，每行带 `traceId` 与 `spanId`，日志后端据此把日志关联到调用链；指标经 `/actuator/prometheus` 输出，带应用名标签。
响应式栈的请求在 Reactor 线程之间切换，starter 打开 Reactor 的自动上下文传播，切换后的日志行仍带当前请求的 `traceId`。

管理端点与业务端点分开：管理端口是业务端口加 1000，启动期核验显式配置不偏离这条约定，并写进 Nacos 实例元数据
供 Spring Boot Admin 发现。`health` 匿名可读，其余端点经一条只匹配 Actuator 端点的认证链做 Basic 认证，与应用
自己的安全链共存。暴露面与认证链使用同一组判断：建不起认证链时只暴露 `health` 与 `info`，认证链该有却没有装配时
非开发环境拒绝启动，管理端点不会无认证地暴露指标、日志级别或堆转储。
配置项与环境变量见 [observability starter 使用说明](../mars-cloud-observability-spring-boot-starter/README.md)。

## 已知行为与偏差

### String 返回值：统一协商为 JSON

控制器直接返回 `String` 时：

| 情况 | body | Content-Type |
| --- | --- | --- |
| 字符串本身是合法 JSON | 原样透传，不二次序列化 | `application/json` |
| 普通字符串 | 包成统一信封 | `application/json` |
| 接口显式声明 `produces = "text/plain"` | 原样返回 | `text/plain` |

**这里曾经有个坑**：Spring 默认转换器链里 `StringHttpMessageConverter` 排在 JSON 转换器之前，
它注册的媒体类型是 `text/plain` 加通配符，所以协商结果是 `text/plain`——即使 advice 已经把
body 换成了 JSON 信封。而 `ResponseBodyAdvice` 在转换器**选定之后**才执行，在 advice 里改响应头
是改不动执行者的。

现在的做法：starter 在默认转换器之前加一个**只声明 JSON 媒体类型**的字符串转换器
（`JsonStringHttpMessageConverter`，行为继承自 `StringHttpMessageConverter`）。
它只在协商结果是 JSON 时参与，显式要求 `text/plain` 的接口走原转换器，不受影响。

注册走 Boot 4 的扩展点 `ServerHttpMessageConvertersCustomizer`，调 `HttpMessageConverters.Builder#addCustomConverter`，
这个 API 的契约就是自定义转换器位于默认转换器之前，不再依赖 `WebMvcConfigurer.extendMessageConverters`
（Spring Framework 7 起标记删除）手工调顺序。

**第二个坑**：这个转换器不能注册成 bean。Boot 用 `@ConditionalOnMissingBean(StringHttpMessageConverter.class)`
决定要不要注册自己那个 UTF-8 的字符串转换器；`JsonStringHttpMessageConverter` 是它的子类，一旦以 bean 存在，
Boot 就认为使用者已自定义字符串转换器而跳过，默认链里的 `text/plain` 转换器退回 Spring 的 ISO-8859-1 默认值，
`produces = "text/plain"` 的非拉丁字符响应就乱码。契约测试同时钉住「`text/plain` 非拉丁字符按 UTF-8 写出」与
「容器里没有 `StringHttpMessageConverter` 类型的 bean」。

> 写代码时注意：`*/*` 这个通配符**不能**直接写进 Javadoc 注释，`*/` 会提前闭合注释块。

### 文案兜底的生效层次

`error.code.<数字>` 的 i18n 查找在异常构造时就会执行（`HttpException`），
而 `mars.codes[<数字>]` 本地兜底配置只在 advice 层生效：

| 取值处 | i18n | `mars.codes` 兜底 | 最终回落 |
| --- | --- | --- | --- |
| `HttpException#getErrorMsg()` | ✅ | ❌ | 数字码 |
| 响应体 `message`（advice） | ✅ | ✅ | 数字码 |

也就是说：**要不要用兜底配置，取决于读的是异常自带文案还是响应体**。业务侧读响应体时
兜底是生效的，日志里读异常自带文案时不会。

## 设计取舍一览

下面几条是本框架的**结构性决定**。它们不是随手选的默认值，改动前需要先理解取舍。

| 决定 | 理由 | 代价 |
| --- | --- | --- |
| 信封与错误码契约下沉到 `common` | 网关是 WebFlux 栈，用不了 Servlet 的 `ResponseBodyAdvice`；契约只放 Servlet starter 会导致「网关一种错误格式、业务服务另一种」 | `common` 必须保持无 Spring 依赖，易被无意破坏 |
| 错误码**区间化 + 启动期校验** | 把「两个模块抢同一个码」从运行期提前到启动期 | 每个服务多一段配置 |
| 框架**不绑定具体锁实现** | 第三方锁组件会把无条件自动装配带进使用方 classpath，可能让使用方启动失败 | 使用方需自己实现锁并抛框架的异常类型 |
| 服务之间**不加编译期依赖** | 服务本就无编译期耦合，将来拆库拆服务是零成本的 | 调用方声明自己的 Feign 接口与本地 DTO，由 Feign starter 统一横切契约 |
| 按**消费者数量**决定能力下沉 | 第一个消费者时写在业务服务里，避免框架被单点需求污染 | 第二个消费者出现时需要一次搬迁 |
| 配置项进版本库、环境取值只走环境变量 | 新克隆的仓不因缺配置文件而起不来；也避免把某人本机配置当成默认值 | 环境差异要靠环境变量表达 |
| Nacos 配置导入 fail-fast，禁止 `optional:` | 配置中心不可用或 Data ID 写错时立即停止，避免服务使用残缺配置运行 | 本地离线测试必须显式关闭 Config 与 Discovery |

### 为什么框架不引任何 IdP 的具体 SDK

认证侧只依赖标准协议（OIDC），**不依赖任何具体身份提供方的 artifact**。
引入某家 SDK 会把它的模型、版本节奏和升级风险一并带进框架，而框架的使用方
未必用同一家。协议是稳定的，SDK 不是。

同理，认证（AuthN）与授权（AuthZ）分属两个服务、永不合并：故障域与权限模型都要独立，
且**渠道返回的组织/角色不构成权限事实来源**——权限只由授权服务的决策接口判定。
登录令牌只承载身份，不承载权限清单，否则改权限要等令牌过期才生效。

## 版本策略

- 开发期统一 `1.0.0-SNAPSHOT`，首个可用版本打 release 后发布到制品库
- 服务依赖 release 版本，不依赖 SNAPSHOT
- 依赖版本只在 `mars-cloud-dependencies` 里声明一次，模块内**不写 `<version>`**
- 不使用 git submodule

> **当前状态**：制品库尚未选定（自建 Nexus 或 GitHub Packages 私有包均可），
> 因此现在**没有发布步骤**。下游服务仓的 CI 通过检出本仓源码并 `mvn install` 来消费，
> 详见 [docs/ci.md](ci.md)。
