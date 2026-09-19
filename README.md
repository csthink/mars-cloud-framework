# mars-cloud-framework

mars-cloud 微服务框架的**公共库仓**：依赖管理 BOM + 一组 Spring Boot Starter。

本仓只产出 jar，不部署任何东西。可部署的应用在配套的服务仓中。

## 快速开始

引入依赖管理（BOM）：

```xml
<parent>
    <groupId>com.mars.cloud</groupId>
    <artifactId>mars-cloud-dependencies</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <relativePath/>
</parent>
```

按需引入能力：

```xml
<dependency>
    <groupId>com.mars.cloud</groupId>
    <artifactId>mars-cloud-mvc-spring-boot-starter</artifactId>
</dependency>
```

## 模块

| 模块 | 定位 |
| --- | --- |
| `mars-cloud-dependencies` | 依赖管理 BOM。全仓**版本唯一出口**，所有模块以它为 parent |
| `mars-cloud-common` | 纯工具与模型库。**不依赖任何 Spring / Servlet 组件** |
| `mars-cloud-core-spring-boot-starter` | 核心自动装配，目前提供分布式 ID（雪花算法） |
| `mars-cloud-mvc-spring-boot-starter` | Servlet 栈的 Web 横切能力：统一响应、全局异常、错误码区间校验、i18n、请求上下文 |
| `mars-cloud-mysql` | MyBatis-Plus 约定：基础实体、逻辑删除、审计字段填充、ID 生成器 |

依赖方向是单向的：`starter` → `common` / `dependencies`。`common` 不反向依赖任何 starter。

## 构建

```bash
mvn clean install            # 全量构建
mvn -pl mars-cloud-common install          # 单模块（父 POM 通过显式 relativePath 解析）
mvn -pl mars-cloud-mvc-spring-boot-starter -am test   # 单模块 + 其依赖
```

本仓自带契约测试（mvc starter 32 个、mysql 14 个），随 `mvn clean install` 一起跑：

| 测试类 | 固定下来的契约 |
| --- | --- |
| `MvcContractTest` | 成功/失败信封、HTTP 状态码、错误码 |
| `ResponseAdviceEdgeCaseTest` | 跳过包装、dev/非 dev 的调试详情差异 |
| `AutoconfigurationBoundaryTest` | 错误码越界/重复在启动期拦截、响应式栈不装配 Servlet advice |
| `MessageResolutionTest` | 文案三级兜底的层次 |
| `StringReturnAndFallbackTest` | String 返回值的 Content-Type 与文案兜底 |
| `PersistenceContractTest` | 审计字段填充、雪花 ID、分页拦截器 |
| `DataSourceDialectTest` | 方言显式指定（达梦/金仓/大小写）、配置错误启动期失败、分页参数 |
| `OpenApiSmokeTest` | `/v3/api-docs` 与 Swagger UI 可访问、接口清单非空 |

本仓要求 **JDK 25**（编译目标 `release 25`）。引入本框架的业务服务，编译时请保留
参数名（`-parameters`，Spring Boot 的 parent 默认已开启）——Spring 6.1 起用它解析
`@RequestParam` / `@PathVariable` 的参数名。

根聚合 POM 只做聚合、**不做 parent**：各模块的 parent 始终是 `mars-cloud-dependencies`，
这样「版本唯一出口」的契约不变，且 `mars-cloud-dependencies` 将来仍可被独立提取。

## 版本矩阵

| 组件 | 版本 |
| --- | --- |
| Java | 25 (LTS) |
| Spring Boot | 4.0.8 |
| Spring Cloud | 2025.1.3 |
| Spring Cloud Alibaba | 2025.1.0.0 |
| Jackson | 3.1.5（注解仍是 `com.fasterxml.jackson.annotation` 2.21） |
| MyBatis-Plus | 3.5.17（`mybatis-plus-spring-boot4-starter`） |

## 文档

- [docs/architecture.md](docs/architecture.md) —— 模块划分与依赖方向
- [docs/error-code.md](docs/error-code.md) —— 统一响应信封与错误码区间约定
- 各模块自己的 `README.md` —— 配置项与用法

## 许可

内部项目，暂未公开发布 artifact。
