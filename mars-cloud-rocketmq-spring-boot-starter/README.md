# mars-cloud-rocketmq-spring-boot-starter

RocketMQ 消息 starter：Spring Cloud Stream 函数式模型加 Spring Cloud Alibaba 的 RocketMQ binder，
固化事务发送、消费约定、上下文透传、主题与消费组核验，以及运行环境前缀。

## 坐标

```xml
<dependency>
    <groupId>com.mars.cloud</groupId>
    <artifactId>mars-cloud-rocketmq-spring-boot-starter</artifactId>
</dependency>
```

它带来 `spring-cloud-starter-stream-rocketmq`（已排除只有服务端用到的 `reflections` 与不需要的 OTLP 导出器）、
`rocketmq-tools`（管理接口）、`spring-boot-starter-jackson` 与 `mars-cloud-common`。
有 Micrometer Tracing 的 `Tracer` 与 `Propagator` bean 时自动传播 trace，没有时只传身份头。

## 运行环境变量

| 变量 | 作用 |
| --- | --- |
| `ROCKETMQ_NAME_SERVER` | 名字服务器地址 `host:port`。必填：不显式配置会启动失败，不回退到 binder 内置默认地址 |
| `MARS_MQ_PREFIX` | 运行环境前缀，形如 `s1-`；starter 把它加到全部主题名、消费组名与生产者组名前面。空表示不加 |
| `MARS_ROCKETMQ_TOPOLOGY` | `verify`（默认）：启动期核验主题与消费组存在；`provision`：缺失的在每个 master broker 创建（读写队列数取 `mars.rocketmq.topic-queues`，默认 4）；`off`：不检查 |

同名属性 `spring.cloud.stream.rocketmq.binder.name-server`、`mars.rocketmq.prefix`、`mars.rocketmq.topology`
也可以直接写，环境变量优先。

## 配置约定

主题名形如 `<domain>-event`，消费组名必须是 `<应用名>-<主题>`；每个生产 binding 必须显式配置 `producer.group`，
以 `<应用名>-` 开头且进程内唯一（binder 默认的 anonymous 组会让同主题的生产者共用客户端实例，broker 也按生产者组路由事务回查）。
三者都只写不带前缀的名字，starter 加运行环境前缀；tag 是大写事件名。启动期校验不满足即失败，错误消息说明违反的规则。

```yaml
spring:
  application:
    name: mars-cloud-product-service
  cloud:
    function:
      definition: orderPaid
    stream:
      output-bindings: entitlementGranted;orderTimeout
      bindings:
        orderPaid-in-0:
          destination: order-event
          group: mars-cloud-product-service-order-event
        entitlementGranted-out-0:
          destination: entitlement-event
        orderTimeout-out-0:
          destination: order-event
      rocketmq:
        bindings:
          entitlementGranted-out-0:
            producer:
              group: mars-cloud-product-service-entitlement-granted
              producer-type: Trans
              transaction-listener: marsTransactionListener
          orderTimeout-out-0:
            producer:
              group: mars-cloud-product-service-order-timeout
```

starter 给出的默认值：进程内重试关闭（`consumer.max-attempts=1`，校验器拒绝其他值）、RocketMQ 重投上限
`push.max-reconsume-times=16`、消息轨迹关闭。消费失败抛异常即可，RocketMQ 按退避重投，超过上限进入
`%DLQ%<消费组>`；业务代码不写重试循环。

## 发送

事务消息把「写库」与「发消息」绑定成本地事务提交后消息才可见：

```java
transactionalEventPublisher.publish("entitlementGranted-out-0",
        EventEnvelope.of("GRANTED", applicationName, orderId, new EntitlementGranted(orderId, resource)),
        () -> entitlementService.grant(orderId, resource));   // 在这里做本地事务；抛异常即回滚消息
```

不能在外层 Spring 事务里调用它，本地事务里也不能再发事务消息；本地事务抛出任何 Throwable 都回滚消息。
进程在本地事务提交后、答复 broker 前崩溃时，broker 会回查：每个事务主题必须有且只有一个
`TransactionStateChecker` bean，按业务表状态答复 `COMMIT_MESSAGE`、`ROLLBACK_MESSAGE` 或 `UNKNOW`。

事务消息忽略延迟档。延迟消息与不需要事务的消息走普通生产者：

```java
plainEventPublisher.publish("orderTimeout-out-0", envelope, DelayLevel.LEVEL_16);          // 立即发送，30 分钟后投递
plainEventPublisher.publishAfterCommit("orderTimeout-out-0", envelope, DelayLevel.LEVEL_16); // 当前事务提交后发送
```

`publishAfterCommit` 在登记前就校验 binding；提交后的发送失败以异常抛给提交方，此时数据库改动已经提交。
提交后、发送前进程崩溃会丢这条消息，兜底由对账任务承担。

发布器写入的消息头：tag（事件名）、key（业务键）、`X-Mars-Event-Id`、当前调用方身份的三个 `X-Mars-*` 头，
以及有 Tracer 时的 `traceparent` 与 `tracestate`。信封的 `trace_id` 为空时按当前 span 补上。

## 消费

消费者是 `Consumer<Message<EventEnvelope<T>>>` bean，一个事件一个函数。消费函数执行期间，上游的 trace 与调用方身份
已还原：`CallerContextHolder.current()` 有值（三个身份头齐全时），Micrometer 的当前 span 属于生产侧的 trace。
是否接续上游 trace 取决于 Propagator 的实现：Brave 的 W3C 实现只接续带 `tracestate` b3 条目的头。

同一事件可能被重复投递。`IdempotentEventHandler` 在一个事务里先登记 `event_id` 再执行业务，登记已存在就跳过：

```java
idempotentEventHandler.handle("mars-cloud-product-service-order-event", message, envelope -> grant(envelope));
```

应用必须先提供 `ProcessedEventStore` bean，`IdempotentEventHandler` 才会装配；实现必须与业务写入同一个数据库事务，接口注释给出建表语句。
事务模板取自唯一的 `PlatformTransactionManager`；有多个事务管理器时必须提供 `TransactionOperations` bean，否则启动失败。
登记用的消费组名按配置里不带前缀的 `group` 写。`InMemoryProcessedEventStore` 只供测试与本机演示。

## 主题与消费组

broker 关闭自动创建时，缺主题只在发送时报错，缺消费组只让拉取持续失败。starter 在启动期按 `MARS_ROCKETMQ_TOPOLOGY`
核验或创建 binding 用到的主题与消费组；核验失败的异常消息列出缺失项与对应的 `mqadmin updateTopic` /
`updateSubGroup` 命令。重试与死信主题由 broker 派生，不在清单里。

## 日志

RocketMQ 客户端自带的日志实现从 classpath 读取 `rmq.logback.xml`；starter 自带一份只写 stdout 的配置，
级别由系统属性 `rocketmq.log.level` 控制（默认 `info`），客户端不再往用户目录写日志文件。

## 测试

单元测试用 Spring Cloud Stream 的 test binder，不起 RocketMQ；对真实 RocketMQ 的契约测试放在
`src/contract/java`，只由 Maven profile `rocketmq-contract` 加入，本机执行：

```bash
tools/rocketmq-contract.sh 127.0.0.1:9876 s1- /path/outside/the/repo/report
```

测试自己以 `provision` 模式创建带前缀的临时主题与消费组，结束时删除；回查场景等待 broker 的回查间隔，用例上限 90 秒。
