# mars-cloud-framework —— 开发约定

本仓是 mars-cloud 的**公共库仓**：只出 jar，不部署。可部署应用在配套的服务仓。

## 硬性约束

这几条破坏后会造成结构性返工，改代码前先确认：

| 约束 | 理由 |
| --- | --- |
| `mars-cloud-common` **不得**依赖任何 Spring / Servlet / Swagger 组件 | 它是唯一能被 Servlet 与响应式两种栈复用的模块 |
| 统一响应信封（`UnifyResponse`）与错误码契约（`ErrorCode`）**留在 common** | 网关与业务服务必须返回同一种响应格式 |
| `service` 之间**不得**有 Maven 依赖，只能走 HTTP | 保证将来拆库拆服务是零成本 |
| 各模块 `<parent>` 是 `mars-cloud-dependencies`，**根聚合 POM 不做 parent** | 「版本唯一出口」契约 |
| 依赖版本只在 `mars-cloud-dependencies` 声明一次，模块内**不写 `<version>`** | 同上 |
| starter **不得**依赖任何具体认证产品（含第三方 IdP 的 artifact） | 框架只依赖标准协议，换 IdP 不改代码 |
| 不为「以后可能用」提前引入组件 | 出现真实场景再建模块 |

## 模块边界

新增能力的判定顺序：

1. **这个模块自己启动吗？** 会启动 → 属于服务仓，不属于本仓
2. **现在有几个消费者？** 只有一个 → 先写在业务服务里；出现**第二个真实消费者**才下沉到本仓
3. **会不会形成环？** starter 只向下依赖 `common` 与 `dependencies`，starter 之间不互相依赖

## 改动检查清单

| 改动类型 | 必须说明 / 验证 |
| --- | --- |
| 统一响应信封 | envelope 字段、HTTP 状态码、body 结构是否变化；下游兼容性 |
| 异常 / advice | 异常映射、错误码、默认兜底文案 |
| i18n | message lookup、fallback locale、缺 key 时的行为 |
| 错误码 | 是否落在声明区间内、是否与既有码冲突 |
| 自动装配 | `AutoConfiguration.imports` 是否同步、条件注解是否覆盖两种 Web 栈 |
| 持久化契约 | `BaseEntity` / 审计填充 / 逻辑删除的改动必须验证下游 |
| 依赖版本 | 只改 `mars-cloud-dependencies`，并跑全量构建 |

文档改动不要写成「已经跑过构建」。

## 构建与验证

```bash
mvn clean install                                  # 全量
mvn -pl mars-cloud-common clean test               # 单模块（验证 relativePath）
mvn -pl mars-cloud-mvc-spring-boot-starter -am test  # 单模块 + 依赖
```

## 提交前

本仓是**公开仓**，提交前必须确认没有夹带内部信息：

```bash
# 在仓库根目录执行完整版扫描器（脚本位于私有设计库的工具目录）
<path-to-private-repo>/tools/check-public-safety.sh .
```

`pre-commit` 钩子只查凭据与本机路径，且可被 `--no-verify` 绕过——扫描器才是最后一道。
