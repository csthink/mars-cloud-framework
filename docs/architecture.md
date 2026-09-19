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

### 错误码：区间化，启动即校验

每个模块与每个服务声明自己的错误码区间，框架在启动时校验「不越界 + 不重复」，
把冲突从运行期提前到启动期。分配表与配置方式见 [error-code.md](error-code.md)。

### 持久化：约定优于配置

`mars-cloud-mysql` 把 `BaseEntity`、逻辑删除、审计字段自动填充、ID 生成器固化下来，
业务侧只写实体与 Mapper。**Entity 不作为对外 API 的 DTO。**

## 版本策略

- 开发期统一 `1.0.0-SNAPSHOT`，首个可用版本打 release 后发布到制品库
- 服务依赖 release 版本，不依赖 SNAPSHOT
- 依赖版本只在 `mars-cloud-dependencies` 里声明一次，模块内**不写 `<version>`**
- 不使用 git submodule
