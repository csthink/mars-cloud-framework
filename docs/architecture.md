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
└── （后续：feign / sentinel / security starter）
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
   ┌──────────────────┬────────┼──────────┬──────────────────┐
   ▼                  ▼        ▼          ▼                  ▼
common ◄──────── core-starter ◄─── mvc-starter           mysql        nacos-starter
（无 Spring）      （自动装配）      （Web 横切）       （持久化）     （注册配置）
```

- `common` **不依赖任何 Spring / Servlet / Swagger**。它是唯一能被所有栈复用的模块，
  包括 WebFlux 栈的网关。
- starter 只**向下**依赖 `common`、`dependencies` 与更底层的 starter
  （例如 mvc starter 依赖 core starter 以获得分布式 ID）；**不允许反向依赖**，
  也不允许出现环。
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
| 服务之间**不加编译期依赖** | 服务本就无编译期耦合，将来拆库拆服务是零成本的 | 跨服务调用要自己写客户端（后续由 Feign starter 提供） |
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
