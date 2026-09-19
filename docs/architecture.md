# 架构设计

本文说明 mars-cloud 框架的模块划分、依赖方向与设计取舍。

## 目标

给基于 Spring Cloud 的微服务提供一套**统一地基**，让下面这些横切关注点只实现一次：

- 对外响应格式与异常语义（统一响应信封、错误码、i18n）
- 持久化约定（基础实体、逻辑删除、审计字段、ID 生成）
- 服务治理能力的接入方式（注册配置、网关、服务调用、限流、认证授权）

## 仓与模块

框架库集中在 `mars-cloud-framework`，可部署应用集中在配套的服务仓。两者是**平级**的
独立仓库，服务仓单向依赖框架库发布出来的 artifact。

```
mars-cloud-framework/            # 本仓：只出 jar，不部署
├── mars-cloud-dependencies/     # 依赖管理 BOM
├── mars-cloud-common/           # 纯工具与模型
├── mars-cloud-core-spring-boot-starter/
├── mars-cloud-mvc-spring-boot-starter/
├── mars-cloud-mysql/
└── （后续：nacos / feign / sentinel / security starter）
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
                      │ parent
   ┌──────────────────┼──────────────────┬──────────────────┐
   ▼                  ▼                  ▼                  ▼
common ◄──────── core-starter ◄─── mvc-starter          mysql
（无 Spring）      （自动装配）      （Web 横切）      （持久化约定）
```

- `common` **不依赖任何 Spring / Servlet / Swagger**。它是唯一能被所有栈复用的模块，
  包括将来的 WebFlux 网关。
- starter 只向下依赖 `common` 与 `dependencies`，starter 之间不互相依赖。
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
| WebFlux 栈 | 网关侧自行实现过滤器与异常处理，复用 `common` 的信封 |

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

现在的做法：starter 往链首插一个**只声明 JSON 媒体类型**的字符串转换器
（`JsonStringHttpMessageConverter`，行为继承自 `StringHttpMessageConverter`）。
它只在协商结果是 JSON 时参与，显式要求 `text/plain` 的接口走原转换器，不受影响。

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

## 版本策略

- 开发期统一 `1.0.0-SNAPSHOT`，首个可用版本打 release 后发布到制品库
- 服务依赖 release 版本，不依赖 SNAPSHOT
- 依赖版本只在 `mars-cloud-dependencies` 里声明一次，模块内**不写 `<version>`**
- 不使用 git submodule
