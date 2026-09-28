# mars-cloud-core-spring-boot-starter

核心能力自动装配，提供分布式 ID（雪花算法）、配置刷新校验与优雅停机约束。

## 坐标

```xml
<dependency>
    <groupId>com.mars.cloud</groupId>
    <artifactId>mars-cloud-core-spring-boot-starter</artifactId>
</dependency>
```

## 分布式 ID

引入依赖后自动生效，无需额外配置。

### 配置项

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `mars.id.snowflake.enabled` | `true` | 是否启用雪花算法（便于切换其它实现） |
| `mars.id.snowflake.worker-id` | `1` | 工作节点 ID，取值 `0–31` |
| `mars.id.snowflake.data-center-id` | `1` | 数据中心 ID，取值 `0–31` |

```yaml
mars:
  id:
    snowflake:
      worker-id: 3
      data-center-id: 1
```

**多实例部署时每个实例的 `worker-id` 必须不同**，否则会生成重复 ID。

### 使用

注入统一接口即可：

```java
@Service
@RequiredArgsConstructor
public class OrderService {

    private final IdGenerator idGenerator;

    public String nextOrderNo() {
        return String.valueOf(idGenerator.nextId());
    }
}
```

### 装配行为

| Bean | 条件 |
| --- | --- |
| `Snowflake` | `mars.id.snowflake.enabled=true` 且没有自定义同类型 Bean |
| `IdGenerator` | 存在 `Snowflake` Bean 且没有自定义 `IdGenerator` 实现 |

业务侧可以通过声明同类型的 Bean 覆盖默认实现。

## 优雅停机

默认 `server.shutdown=graceful`，`spring.lifecycle.timeout-per-shutdown-phase=30s`。
等待时间可以收紧为正值，不能超过 30 秒；立即停机配置与不兼容的自定义生命周期处理器会在启动时被拒绝。
校验同时检查实际 WebServer 工厂和生命周期处理器，配置刷新也不能放宽限制。

30 秒是每个生命周期阶段的等待上限，不是整个 JVM 的退出时限。使用 Nacos 时先进行实例清理，
然后 HTTP 服务器停止接收新请求并等待在途请求；服务发现客户端保留到 Bean 销毁阶段。
