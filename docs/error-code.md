# 统一响应与错误码约定

## 统一响应信封

所有 Servlet 栈接口的成功与失败响应都由框架包成同一个信封：

```json
{
  "success": true,
  "result": { "id": 123 }
}
```

```json
{
  "success": false,
  "code": "61001",
  "message": "参数不合法"
}
```

字段说明：

| 字段 | 说明 |
| --- | --- |
| `success` | 业务成功 / 失败 |
| `code` | 错误码（字符串形式的数字），成功时不返回 |
| `message` | 提示信息（已本地化），成功时不返回 |
| `result` | 业务数据；失败时可承载调试详情，非开发环境一律为 `null` |

信封为 `null` 字段不参与序列化（`@JsonInclude(NON_NULL)`），所以成功响应里看不到
`code` / `message`。

### 与 HTTP 状态码的关系

HTTP 状态码表达**协议语义**，`code` 表达**业务语义**，两者并存：

| 场景 | HTTP | `success` | `code` |
| --- | --- | --- | --- |
| 成功 | 200 | `true` | 无 |
| 参数校验失败 | 400 | `false` | 业务错误码 |
| 未认证 | 401 | `false` | 业务错误码 |
| 无权限 | 403 | `false` | 业务错误码 |
| 业务规则拒绝（`BusinessException`） | 200 | `false` | 业务错误码 |
| 未预期异常 | 500 | `false` | 业务错误码 |

注意最后两行：业务规则拒绝走 HTTP 200，由 `success=false` + `code` 表达。

### 跳过信封包装

个别接口（如文件下载、回调）需要原样返回时，在类或方法上标注
`@IgnoreResponseAnnotation` 即可跳过包装。

## 错误码区间分配

| 区段 | 归属 |
| --- | --- |
| `60000–60999` | 框架级（`mars-cloud-common`） |
| `61000–61999` | `mars-cloud-mvc-spring-boot-starter` |
| `62000–62999` | `mars-cloud-security-spring-boot-starter` |
| `63000–63999` | 网关 |
| `64000–64999` | 认证服务 |
| `65000–65999` | 权限服务（UPMS） |
| `66000+` | 其他业务服务，每服务 1000 或 2000 一段 |

**框架各层也必须声明自己的区间**——框架自己抛出的错误同样需要错误码。

## 在服务里声明区间

```yaml
mars:
  error-code:
    validate: true          # 默认 true，启动时校验
    range:
      service: order-service
      start: 66000
      end: 66999
```

启动时框架会校验：

1. 所有注册的错误码 **落在声明区间内**，越界直接启动失败
2. 本服务内**错误码不重复**

这样错误码冲突会在启动瞬间暴露，而不是等到某个分支被触发。

## 定义错误码

实现 `ErrorCode` 接口即可（枚举是最常见的形式）：

```java
@Getter
@AllArgsConstructor
public enum OrderErrorCode implements ErrorCode {

    ORDER_NOT_FOUND(66001),
    ORDER_ALREADY_PAID(66002),
    ;

    private final int code;
}
```

把它们注册给框架：

```java
@Component
public class OrderErrorCodeRegistrar implements ErrorCodeRegistrar {

    @Override
    public Collection<? extends ErrorCode> codes() {
        return List.of(OrderErrorCode.values());
    }
}
```

抛出业务异常：

```java
throw new BusinessException(OrderErrorCode.ORDER_NOT_FOUND);
```

## 国际化文案

错误码只存数字，文案按 key 约定从 `MessageSource` 取：

| 项 | 约定 |
| --- | --- |
| key 格式 | `error.code.<数字>` |
| 资源文件 | `i18n/error-code.properties`（默认）、`_zh_CN`、`_en_US` 三份同步维护 |
| 必需配置 | `spring.messages.basename: i18n/error-code`，**缺它会退化成裸错误码** |

```properties
error.code.66001=订单不存在
```

文案查找有三级兜底：规范 key → 纯数字 key → 服务本地的
`config/exception-code.properties`（`mars.codes[66001]=...`）→ 最终回退为数字本身。
