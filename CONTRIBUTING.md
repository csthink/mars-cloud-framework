# 贡献指南

本仓是 mars-cloud 的**公共库仓**：只产出 jar，不部署任何东西。可部署的应用在配套的
服务仓 `mars-cloud-service`。

这份文档说明改本仓时必须守住的约束、以及改动怎么验证。

## 硬性约束

下面几条破坏后会造成**结构性返工**，改代码前先确认：

| 约束 | 理由 |
| --- | --- |
| `mars-cloud-common` **不得**依赖任何 Spring / Servlet / Swagger 组件 | 它是唯一能被 Servlet 与响应式两种栈复用的模块。引 `spring-boot-starter-jackson` 同样破坏这条——它经 `spring-boot-starter` 带入 `spring-core` / `spring-context` 与日志实现 |
| 统一响应信封（`UnifyResponse`）与错误码契约（`ErrorCode`）**留在 common** | 网关与业务服务必须返回同一种响应格式 |
| 框架**不引入任何具体锁实现**（lock4j / Redisson 都不引） | 第三方锁组件连最轻的 core 包都会无条件自动装配，会让使用方启动失败。锁异常类型用 `com.mars.cloud.common.exception.LockFailureException` |
| starter **不得**依赖任何具体认证产品（含第三方 IdP 的 artifact） | 框架只依赖标准协议，换 IdP 不改代码 |
| 各模块 `<parent>` 是 `mars-cloud-dependencies`，**根聚合 POM 不做 parent** | 「版本唯一出口」契约 |
| 依赖版本只在 `mars-cloud-dependencies` 声明一次，模块内**不写 `<version>`** | 同上。唯一例外是 `common` 的 Jackson——它拿不到 Boot 的依赖管理，版本在 BOM 里用属性钉住 |
| 不为「以后可能用」提前引入组件 | 出现真实场景再建模块 |

## 模块边界

新增能力前，按下面的顺序判定它该放哪里：

1. **这个模块自己启动吗？** 会启动 → 属于服务仓，不属于本仓
2. **现在有几个消费者？** 只有一个 → 先写在业务服务里；出现**第二个真实消费者**才下沉到本仓
3. **会不会形成环？** starter 只**向下**依赖 `common`、`dependencies` 与更底层的 starter，
   不允许反向依赖或成环

第 2 条是刻意从严的：过早下沉会让框架承担单点需求，之后每次改动都牵动所有使用方。

## 改动检查清单

| 改动类型 | 必须说明 / 验证 |
| --- | --- |
| 统一响应信封 | envelope 字段、HTTP 状态码、body 结构是否变化；下游兼容性 |
| 异常 / advice | 异常映射、错误码、默认兜底文案 |
| i18n | message lookup、fallback locale、缺 key 时的行为 |
| 错误码 | 是否落在声明区间内、是否与既有码冲突 |
| 自动装配 | `AutoConfiguration.imports` 是否同步、条件注解是否覆盖两种 Web 栈 |
| Nacos 约定 | Config 与 Discovery 的 Namespace 是否一致；Group / Data ID / 导入顺序 / fail-fast 是否仍受测试保护 |
| 持久化契约 | `BaseEntity` / 审计填充 / 逻辑删除的改动必须验证下游 |
| 依赖版本 | 只改 `mars-cloud-dependencies`，并跑全量构建 |

## 构建与验证

需要 **JDK 25**。仓内 `.mvn/jvm.config` 会给 Maven 进程带上
`--sun-misc-unsafe-memory-access=allow`（Lombok 在 JDK 24 及以上编译期需要），无需手动设置。

```bash
mvn clean install                                     # 全量 + 契约测试
mvn -pl mars-cloud-common clean test                  # 单模块
mvn -pl mars-cloud-mvc-spring-boot-starter -am test   # 单模块 + 其依赖
mvn -pl mars-cloud-nacos-spring-boot-starter -am test # Nacos 约定 + 其依赖
```

本仓的契约测试是**回归网**，改到统一响应、异常映射、i18n 或错误码时必须全绿。
测试类与它们固定的契约见 [README](README.md) 的「构建」一节。

## 提交前

本仓是**公开仓**，提交前请确认改动里没有夹带不该公开的内容——
内部主机名、内网地址、本机绝对路径、真实凭据、内部计划或未公开的评审结论。

克隆后装一次 pre-commit 钩子（幂等），它会在每次提交时检查暂存内容：

```bash
./tools/install-hooks.sh
```

钩子只是第一道，且可以被 `git commit --no-verify` 绕过。**内容一旦 push 出去，
即使后续删除也仍留在 Git 历史里**，所以提交前请自己再过一遍。

提交信息只描述改动本身。
