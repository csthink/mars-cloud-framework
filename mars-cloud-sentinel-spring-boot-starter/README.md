# mars-cloud-sentinel-spring-boot-starter

Sentinel 限流降级 starter：规则只从 Nacos 读取，整批校验、整批生效；网关按核对后的客户端地址限流；
每个 Feign 客户端一个资源；不开放命令端口，不在本机写日志或统计文件。

## 坐标

```xml
<dependency>
    <groupId>com.mars.cloud</groupId>
    <artifactId>mars-cloud-sentinel-spring-boot-starter</artifactId>
</dependency>
```

它带来 Spring Cloud Alibaba 的 Sentinel starter（已排除 HTTP 命令端口与两个集群限流实现）、
`sentinel-logging-slf4j` 与 `mars-cloud-nacos-spring-boot-starter`。网关部署物另外声明：

```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-alibaba-sentinel-gateway</artifactId>
</dependency>
```

## 规则从哪里来

每种规则一个 Nacos 配置，Data ID 为 `<spring.application.name>-sentinel-<类型>-rules.json`，Group 为
`SENTINEL_GROUP`，命名空间与应用配置相同（starter 复用应用配置导入所用的 Nacos 客户端）。

| 部署物 | 订阅的类型 |
| --- | --- |
| 网关（响应式应用且引入了网关适配） | `gw-api-group`、`gw-flow`，分组先装入 |
| 其他部署物 | `flow`、`degrade`、`param-flow`、`system` |

加载语义：

- **启动时**：任一 Data ID 不存在、内容为空白、读取失败或没有通过校验，应用启动失败，异常信息写明 Data ID 与原因。
  某类规则不需要时写 `[]`。
- **运行中**：修改后校验通过即整批替换，不重启；没有通过校验、配置被删除或变为空白时，保留上一批规则，
  写一行 ERROR 并更新指标。要清空规则写 `[]`。
- **读取失败时 Nacos 客户端的回退**：读取出错或超时（无权限除外）时，Nacos 客户端退回本机的故障转移文件或快照。
  有快照的主机会用快照内容启动，不报错；没有快照的主机得到「配置不存在或读取失败」。
- **同一把锁**：同一个部署物的各种规则在同一把锁里校验与装入。网关的两种规则互相引用，校验时读到的另一种规则
  不会在装入前被并发的更新改掉。

不使用 `sentinel-datasource-nacos`：它在启动时读取失败、内容为空或监听登记失败都只写警告后照常运行，
规则就此静默失效；它还为每个 Data ID 各建一个 Nacos 客户端。

## 校验

任一条不通过则整批拒绝，拒绝信息写明第几条规则、哪个字段与原因。

1. **字段表**：顶层必须是数组；每种规则只接受下表字段，未知字段、重复字段、类型不符、字符串冒充数字、小数冒充整数都拒绝。
   省略的字段取 Sentinel 的默认值。
2. **Sentinel 的合法性判断**：Sentinel 自己会静默丢弃的单条规则，在这里变成整批拒绝。
3. **约束**：`limitApp` 只能是 `default`（不配置调用来源解析）；`clusterMode` 只能是 `false`（不使用集群限流）；
   `regex` 只能是 `false`（资源名按字面匹配）。网关的路由模式规则必须指向已声明的路由 ID，分组模式规则必须指向
   已生效的分组；删掉仍被引用的分组时，分组的这次更新整批拒绝。Sentinel 会静默接受却不生效的取值同样拒绝：
   热点参数规则里解析不出或次数为负的例外项，网关参数项里越界的 `parseStrategy`、没有实现的 `matchStrategy` 与写错的正则。

| 类型 | 必填 | 可选 |
| --- | --- | --- |
| `flow` | `resource`、`count` | `grade`、`strategy`、`refResource`、`controlBehavior`、`warmUpPeriodSec`、`maxQueueingTimeMs`、`limitApp`、`clusterMode`、`regex` |
| `degrade` | `resource`、`count`、`timeWindow` | `grade`、`minRequestAmount`、`slowRatioThreshold`、`statIntervalMs`、`limitApp`、`regex` |
| `param-flow` | `resource`、`paramIdx`、`count` | `grade`、`controlBehavior`、`maxQueueingTimeMs`、`burstCount`、`durationInSec`、`paramFlowItemList`（每项 `object`、`count`、`classType`：`count` 不为负；`classType` 是 `int`、`long`、`double`、`float`、`boolean`、`byte`、`short`、`char`、对应包装类的全名或 `java.lang.String`；`object` 能按它解析，`boolean` 只接受 `true` 与 `false`，`char` 只接受一个字符；同一批里解析后的值不重复）、`limitApp`、`clusterMode`、`regex` |
| `system` | 至少一个阈值不为 -1 | `highestSystemLoad`、`highestCpuUsage`（0 到 1）、`qps`、`avgRt`、`maxThread` |
| `gw-api-group` | `apiName`、`predicateItems`（每项 `pattern`，可选 `matchStrategy`） | 前缀匹配的 `pattern` 以 `/**` 结尾，正则必须能编译 |
| `gw-flow` | `resource`、`count` | `resourceMode`、`grade`、`intervalSec`、`controlBehavior`、`burst`、`maxQueueingTimeoutMs`、`paramItem`（`parseStrategy` 必填，0 客户端地址、1 Host、2 请求头、3 URL 参数、4 Cookie，后三种 `fieldName` 必填；`pattern` 可选，给出时 `matchStrategy` 只接受 0 精确、2 正则、3 包含，正则必须能编译；没有 `pattern` 就不能写 `matchStrategy`） |

## 资源名

| 来源 | 资源名 |
| --- | --- |
| Servlet 接口 | `HTTP 方法:路径模板`，例如 `POST:/order/v1/callbacks/{channel}` |
| 网关 | 路由 ID，或 `gw-api-group` 里的分组名 |
| Feign 客户端 | `feign:<客户端名>`，例如 `feign:mars-cloud-upms-service`；一次调用含负载均衡的换实例重试只进入一次 |

Sentinel 1.8.9 的 Spring MVC 6 适配不读取 `http-method-specify` 开关，同一路径的不同方法会落到同一个资源上；
starter 提供的拦截器补上方法前缀，开关 `spring.cloud.sentinel.http-method-specify` 默认打开。

## 被拦截时

starter 不写响应，把拦截异常交给应用自己的统一错误处理：

- **网关**：`BlockException` 交回 WebFlux 的异常处理链，由应用的 `WebExceptionHandler` 写响应。
- **Servlet**：`BlockException` 抛回 Spring MVC 的异常处理链，由应用的 `@ExceptionHandler` 写响应。
- **Feign**：请求不发出，抛出 `SentinelBlockedCallException`；feign starter 把它映射为 `DownstreamFailureKind.UNAVAILABLE`
  交给调用方的 `DownstreamFailureMapper`，需要区分时看失败原因。传输异常与下游 5xx 计入资源异常，4xx 与信封里的
  业务失败不计入。

应用没有映射拦截异常时，请求得到应用的兜底错误响应，不会出现 Sentinel 自带的文本。

## 网关的客户端地址

按客户端地址限流（`paramItem.parseStrategy` 为 0）时，地址取自交换属性，不取 TCP 对端地址：网关前面有负载均衡或
CDN 时，对端地址是代理的地址。

```yaml
mars:
  sentinel:
    gateway:
      client-ip-attribute: <入站过滤器写入客户端地址的交换属性名>
```

网关部署物必须配置它，为空白时启动失败。写入这个属性的过滤器必须先于 Sentinel 网关过滤器执行
（后者是全局过滤器，所有 `WebFilter` 都在它之前）；请求到达时属性缺失，解析器抛出异常，不回退到对端地址。

## 不开放的能力

| 能力 | 处理 |
| --- | --- |
| HTTP 命令端口（默认 8719，没有认证，能在运行期改规则） | 从依赖里排除；starter 提供不监听端口的命令中心与不发送的心跳，classpath 上出现 HTTP 命令中心时启动失败 |
| Sentinel Dashboard | 不支持；配置 `spring.cloud.sentinel.transport.*` 即启动失败 |
| Spring Cloud Alibaba 的属性数据源 `spring.cloud.sentinel.datasource.*` | 配置即启动失败，规则只从上面的 Data ID 进入 |
| 网关兜底响应 `spring.cloud.sentinel.scg.fallback.*`、Servlet 拦截页 `spring.cloud.sentinel.block-page` 与 `spring.cloud.sentinel.servlet.block-page` | 配置即启动失败 |
| Spring Cloud Alibaba 的 Feign 集成 `feign.sentinel.enabled=true` | 配置即启动失败，资源由 starter 按客户端登记 |
| 日志文件 | Sentinel 的记录日志与命令中心日志经 slf4j 输出。日志目录（默认用户目录下的 `logs/csp/`，可用系统属性 `csp.sentinel.log.dir` 指定）只在 Sentinel 的 `LogBase` 类初始化时创建：本组件的用例触发拦截后核对过它不存在，已知的触发点是读取 Spring Cloud Alibaba 的 `sentinel` 管理端点；即使创建了，目录里也不会出现文件 |
| 统计文件 | 关闭每秒写一次的指标文件：starter 自带的 `sentinel.properties` 写了 `csp.sentinel.metric.flush.interval=0`，不论谁先触发 Sentinel 初始化都生效，启动时再把同名系统属性设为 0（已显式设置时不覆盖）；处理链去掉 `LogSlot`，不写 `sentinel-block.log`，首次拦截也不会在用户目录建 EagleEye 日志 |

显式设置 `spring.cloud.sentinel.enabled=false` 时，starter 不装配规则来源，也关闭网关过滤器。

## 指标

有 Micrometer 的 `MeterRegistry` 时：

| 指标 | 标签 | 含义 |
| --- | --- | --- |
| `mars.sentinel.requests.blocked` | `resource`、`exception` | 被拦截的请求数 |
| `mars.sentinel.rule.updates` | `rule_type`、`outcome`（`accepted` / `rejected`） | 运行中的规则更新结果 |
| `mars.sentinel.rule.source.valid` | `rule_type` | 最近一次更新被接受为 1；为 0 表示更新被拒绝、上一批规则仍在生效，告警按它触发 |
| `mars.sentinel.rules.active` | `rule_type` | 当前生效的规则条数 |

## 配置

| 属性 | 默认 | 说明 |
| --- | --- | --- |
| `mars.sentinel.rules.read-timeout` | `3s` | 启动时读取每个规则 Data ID 的超时 |
| `mars.sentinel.gateway.client-ip-attribute` | 无 | 网关必填，见上文 |
| `spring.cloud.sentinel.http-method-specify` | `true` | Servlet 资源名是否带 HTTP 方法 |

## 测试

单元测试不连 Nacos：提供一个 `RuleConfigSource` bean 即可替换规则来源。本模块的网关与 Servlet 用例在真端口上运行；
Feign 用例用经 Spring Cloud OpenFeign 装配的客户端，下游是按路径返回状态码的 `Client` 替身。用例在触发拦截后核对
日志目录里没有任何文件。
