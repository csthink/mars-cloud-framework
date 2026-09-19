# mars-cloud-framework

[![CI](https://github.com/csthink/mars-cloud-framework/actions/workflows/ci.yml/badge.svg)](https://github.com/csthink/mars-cloud-framework/actions/workflows/ci.yml)

mars-cloud 微服务框架的**公共库仓**：依赖管理 BOM + 一组 Spring Boot Starter。

本仓只产出 jar，不部署任何东西。可部署的应用在配套的服务仓中。

> 本仓构建成功后会**自动触发** `mars-cloud-service` 的 CI，避免「框架改了、服务没跟上」。
> 流水线内容与配置见 [docs/ci.md](docs/ci.md)。

## 快速开始

### 在服务里引入框架

克隆后先装 pre-commit hook（幂等，只需一次）：

```bash
./tools/install-hooks.sh
```

框架尚未发布到制品库，所以先把它装进本地仓库（框架改动后需重装）：

```bash
mvn clean install
```

在**服务**工程的 POM 里以本仓的 BOM 为 parent：

```xml
<parent>
    <groupId>com.mars.cloud</groupId>
    <artifactId>mars-cloud-dependencies</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <relativePath/>
</parent>
```

按需引入能力——**依赖不写 `<version>`**，版本由 BOM 统一管理：

```xml
<dependency>
    <groupId>com.mars.cloud</groupId>
    <artifactId>mars-cloud-mvc-spring-boot-starter</artifactId>
</dependency>
```

Web 运行时由服务自己提供（starter 刻意不向下游传递它）：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
```

然后在 `application.yml` 里声明两件必需的事——错误码区段与 i18n 资源位置：

```yaml
spring:
  messages:
    basename: i18n/error-code     # 缺它会退化成裸错误码

mars:
  error-code:
    validate: true
    framework-layers: [ common, mvc ]
    ranges:
      # business 区段是 66000–99999，由所有业务服务共用。
      # 这里声明的只是**本服务占用的那一段**，多个服务各自声明一段、互不重叠。
      - owner: business
        start: 66000
        end: 66999
```

到这里业务代码就只需要写「返回业务对象」和「抛异常」，包装与翻译由 starter 完成。
完整的可运行例子见配套服务仓的 **`mars-cloud-sample-service`**：
它是一条命令能跑起来的示例服务，演示信封、异常映射、错误码与 i18n 的接线，
并自带端到端验收脚本。

### 参与本仓开发

构建、测试与依赖约束见下文「[构建](#构建)」一节。

## 模块

| 模块 | 定位 |
| --- | --- |
| `mars-cloud-dependencies` | 依赖管理 BOM。全仓**版本唯一出口**，所有模块以它为 parent |
| `mars-cloud-common` | 纯工具与模型库。**不依赖任何 Spring / Servlet 组件** |
| `mars-cloud-core-spring-boot-starter` | 核心自动装配，目前提供分布式 ID（雪花算法） |
| `mars-cloud-mvc-spring-boot-starter` | Servlet 栈的 Web 横切能力：统一响应、全局异常、错误码区间校验、i18n、请求上下文 |
| `mars-cloud-mysql` | MyBatis-Plus 约定：基础实体、逻辑删除、审计字段填充、ID 生成器 |

依赖方向是单向的：starter → `common` / `dependencies` / 更底层的 starter
（mvc starter 依赖 core starter 以获得分布式 ID），不允许反向依赖或成环。
`common` 不反向依赖任何 starter。

> 本仓**只出 jar，不部署**。某个能力该不该放这里，判据是「第二个真实消费者出现了吗」——
> 只有第一个消费者时，代码应留在那个业务服务里。可部署的应用都在配套服务仓。

## 构建

```bash
mvn clean install            # 全量构建（父 POM 通过显式 relativePath 解析）
mvn -pl mars-cloud-common clean test                 # 单模块
mvn -pl mars-cloud-mvc-spring-boot-starter -am test   # 单模块 + 其依赖
```

本仓要求 **JDK 25**（编译目标 `release 25`），并在 BOM 里为全仓开启 `-parameters`——
Spring Framework 7（Boot 4 基线）靠参数名解析 `@RequestParam` / `@PathVariable`，
缺了它会在运行期抛 `IllegalArgumentException`，而编译期不报错。

契约测试共有 **55 个**（mvc starter 41 个、mysql 14 个），随 `mvn clean install` 一起跑：

| 测试类 | 固定下来的契约 |
| --- | --- |
| `MvcContractTest` | 成功/失败信封、HTTP 状态码、错误码 |
| `ResponseAdviceEdgeCaseTest` | 跳过包装、dev/非 dev 的调试详情差异 |
| `AutoconfigurationBoundaryTest` | 错误码归属/越界/重复/段间重叠在启动期拦截、配置自检、响应式栈不装配 Servlet advice |
| `ErrorCodeContractTest` | 错误码契约（`getMsgKey` 规范、归属判定） |
| `MessageResolutionTest` | 文案四级兜底的层次 |
| `StringReturnAndFallbackTest` | String 返回值的 Content-Type 与文案兜底 |
| `PersistenceContractTest` | 审计字段填充、雪花 ID、分页拦截器 |
| `DataSourceDialectTest` | 方言显式指定（达梦/金仓/大小写）、配置错误启动期失败、分页参数 |
| `OpenApiSmokeTest` | `/v3/api-docs` 与 Swagger UI 可访问、接口清单非空 |

> 改到统一响应、异常映射、i18n 或错误码时，这几张测试表是必须跑绿的契约面。

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

- [docs/architecture.md](docs/architecture.md) —— 模块划分、依赖方向与设计取舍
- [docs/error-code.md](docs/error-code.md) —— 统一响应信封与错误码区间约定
- [docs/ci.md](docs/ci.md) —— 流水线内容与跨仓触发
- [CONTRIBUTING.md](CONTRIBUTING.md) —— 参与本仓开发：硬性约束、模块边界判定、改动检查清单
- 各模块自己的 `README.md` —— 配置项与用法
- 配套服务仓的 `mars-cloud-sample-service` —— 可运行的完整示例

## 许可

内部项目，暂未公开发布 artifact。
