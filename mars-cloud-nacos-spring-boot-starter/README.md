# mars-cloud-nacos-spring-boot-starter

为业务服务接入 Nacos 注册发现与配置中心，并在启动期校验 mars-cloud 的命名与配置分层约定。

## 引入

版本由 `mars-cloud-dependencies` 统一管理：

```xml
<dependency>
    <groupId>com.mars.cloud</groupId>
    <artifactId>mars-cloud-nacos-spring-boot-starter</artifactId>
</dependency>
```

## 固定约定

| 概念 | 约定 |
| --- | --- |
| Namespace | 每个环境使用独立的非空 Namespace ID；Config 与 Discovery 使用同一个 ID |
| 共享配置 | `COMMON/shared-common.yaml` |
| 应用配置 | `DEFAULT_GROUP/<spring.application.name>.yaml` |
| 服务发现 Group | `DEFAULT_GROUP` |
| 应用名 | 小写 kebab-case，同时作为服务名与应用配置 Data ID 的前缀 |
| 导入顺序 | 共享配置在前，应用配置在后，应用配置覆盖共享默认值 |
| 失败语义 | 两层配置都不得写 `optional:`；Nacos 不可用时应用启动失败 |

## 配置模板

完整模板随 jar 放在
`META-INF/mars-cloud/nacos/application-example.yml`。核心配置如下：

```yaml
spring:
  application:
    name: mars-cloud-example-service
  cloud:
    nacos:
      config:
        server-addr: ${NACOS_SERVER_ADDR:127.0.0.1:8848}
        namespace: ${NACOS_NAMESPACE_ID}
        username: ${NACOS_USERNAME:}
        password: ${NACOS_PASSWORD:}
      discovery:
        server-addr: ${NACOS_SERVER_ADDR:127.0.0.1:8848}
        namespace: ${NACOS_NAMESPACE_ID}
        group: DEFAULT_GROUP
        username: ${NACOS_USERNAME:}
        password: ${NACOS_PASSWORD:}
  config:
    import:
      - nacos:shared-common.yaml?group=COMMON&refreshEnabled=true
      - nacos:${spring.application.name}.yaml?group=DEFAULT_GROUP&refreshEnabled=true
```

Spring Cloud Alibaba 2025.1.x 已不支持 `bootstrap.yml` /
`bootstrap.properties`。配置导入只能写在 `application.yml` 的
`spring.config.import` 中。

## 明确离线

测试或不接入 Nacos 的进程必须同时关闭 Config 与 Discovery。关闭不是容错方案，只表示该进程
明确不使用 Nacos：

```yaml
spring:
  cloud:
    nacos:
      config:
        enabled: false
        import-check:
          enabled: false
      discovery:
        enabled: false
```

也可以用 `mars.nacos.convention.validation-enabled=false` 关闭本 starter 的约定校验，
但这不会关闭 Spring Cloud Alibaba 自己的 Nacos 客户端或导入检查。

## 默认值

本 starter 在环境末尾追加一个最低优先级的属性源，配置文件、环境变量、命令行参数与配置中心的显式配置都覆盖它。例外有两处：

- 应用代码经 `SpringApplication#setDefaultProperties` 设置的默认属性：Spring Boot 在 starter 写入之后才把它们移到末尾，
  同名时 starter 无条件写入的值生效，例如下表的 `spring.cloud.discovery.client.composite-indicator.enabled`。
  只在未配置时才推导的 `spring.cloud.nacos.discovery.ip` 在推导时已经能看到这些默认属性，于是不推导，应用代码的值生效。
- 上下文刷新时才加入的 `@PropertySource`：它排在本 starter 的属性源之后，同名值不生效。

| 属性 | 默认值 | 说明 |
| --- | --- | --- |
| `spring.cloud.discovery.client.composite-indicator.enabled` | `false` | 根健康端点不含注册中心检查（`discoveryComposite`），见下文 |
| `spring.cloud.nacos.discovery.ip` | 取 `server.address` 的规范形式 | 只在 `server.address` 是具体的 IPv4 地址时写入，见下文「注册地址」 |

注册中心检查默认不加入根健康端点，避免把注册中心连通性混入业务依赖健康状态。
需要时可显式启用；服务注册始终检查完整 readiness 组。

## 就绪注册与停机

注册实例使用 Nacos v3 Client HTTP API；SDK 仅用于配置和发现，不持有实例注册。
业务端口已监听、所有 ApplicationRunner 完成、应用接受流量且完整 readiness 组为 UP 后，才首次注册。
必须启用存活与就绪探针，分别保留 `livenessState`、`readinessState` 成员及对应健康贡献器。
将数据库等必需依赖加入 readiness 组，依赖失败时才能阻止注册或触发注销。

每次注册或续约前重新检查完整 readiness 组，健康检查最长等待 1 秒且不重叠。
默认每 5 秒续约；健康状态不再满足时停止续约并显式删除实例，恢复后重新检查再注册。
消费者必须关闭 `spring.cloud.nacos.discovery.naming-push-empty-protection`，接受空实例列表。
注册响应未知时暂停自动重新注册；空列表本身不能证明先前请求不会迟到生效。

关闭根上下文时立即停止新的注册和续约，实例清理最多等待 10 秒，然后进入 HTTP 优雅停机。
服务发现客户端一直保留到 Bean 销毁，在途 Feign 请求仍可查询下游实例。
实例清理等待、各生命周期阶段等待分别计时，不承诺 JVM 的硬退出时限。

支持用户名/密码认证、临时实例、逗号分隔的显式地址列表以及 HTTP/HTTPS 和 context path。
无 scheme 的主机名默认使用 8848 端口；显式 URI 按其端口语义解析。HTTPS 使用 JVM 信任库。
每次 HTTP 请求总等待最多 2 秒，不自动重试或重定向。配置刷新先清理旧快照，再使用新配置。
不支持 endpoint、AK/SK、永久实例、自定义认证 SPI 或心跳时间覆盖；不兼容配置在启动或刷新时被拒绝。
同一实例身份同一时间只能由一个进程持有。

### 注册地址

Spring Cloud Alibaba 在没有配置注册地址时注册第一块非回环网卡的地址，不参考 `server.address`。
业务端口只绑定在某个地址上时，调用方按另一块网卡的地址连接会失败。所以 `server.address` 是具体的 IPv4 地址字面量时，
本 starter 让注册地址取它的规范形式（四段十进制，例如 `127.1` 写成 `127.0.0.1`）。下面几种情况不写入，
注册地址保持 Spring Cloud Alibaba 的默认：

- `server.address` 为通配地址（`0.0.0.0`、`::`）、主机名或未配置。
- `server.address` 是 IPv6 地址：Spring Cloud 把注册地址直接拼进 `http://<地址>:<端口>` 形式的实例地址，
  不给 IPv6 地址加方括号，拼出的地址无效。以 IPv6 地址注册不在本 starter 的支持范围内。
- 显式配置了 `spring.cloud.nacos.discovery.ip`，包括空值。空值时 Spring Cloud Alibaba 视为未配置，自己选取网卡。
  `server.address` 是具体 IPv4 地址时，上下文刷新时才加入的 `@PropertySource` 在推导时还看不到，并且排在本 starter 的属性源之后，
  其中的 `spring.cloud.nacos.discovery.ip` 既不阻止推导，也不生效；注册地址用配置文件、环境变量或命令行参数给出。

`spring.cloud.nacos.discovery.network-interface`、`ip-type` 与 `spring.cloud.inetutils` 的 `preferred-networks`、
`ignored-interfaces`、`use-only-site-local-interfaces` 只能从本机网卡里挑地址。
`server.address` 是具体的 IPv4 地址时业务端口只在这个地址上监听，挑出别的地址也没有进程在那里监听，
所以本 starter 照常推导，这些配置项不再影响注册地址。

注册地址由本 starter 给出后，Spring Cloud Alibaba 不再把本机 IPv6 地址写进实例元数据 `IPv6`，这一项只在它自己选取注册地址时写入。
注册地址需要与绑定地址不同时，显式配置 `spring.cloud.nacos.discovery.ip`。注册端口使用实际业务监听端口；
显式 `spring.cloud.nacos.discovery.port` 必须与它一致，管理端口不注册。

## 运行时 JVM 参数

引入本 starter 的应用在 JDK 24 及以上启动时需要带：

```
--sun-misc-unsafe-memory-access=allow
```

本 starter 引入的 nacos-client 3.1.1 内部 shade 了 Guava，后者调用 `sun.misc.Unsafe`
的内存访问方法。JDK 24 起（JEP 498）默认在首次调用时向标准错误打印一组
`WARNING: A terminally deprecated method in sun.misc.Unsafe has been called` 弃用警告，
后续 JDK 版本会先改为 `debug` 再改为 `deny`。这是 nacos 上游问题
（[nacos#14070](https://github.com/alibaba/nacos/issues/14070)），上面的参数是 JDK 给出的规避方式。

| 启动方式 | 谁负责带上参数 |
| --- | --- |
| `mvn spring-boot:run` / `spring-boot:start` | `mars-cloud-dependencies` 已在 `pluginManagement` 里统一配置，以它为 parent 的模块不需要再写 |
| `java -jar`、容器 `ENTRYPOINT` | 应用自己的启动命令 |
| IDE 直接运行主类 | 运行配置的 VM options |

明确离线的进程（含测试）不会调用 Nacos 客户端，也就不会触发这组警告，不需要这个参数。

与本 starter 无关但常一起出现的另一个参数：classpath 上带 Netty 平台原生库的应用（响应式栈即如此）
在 JDK 24 及以上还需要 `--enable-native-access=ALL-UNNAMED`（JEP 472）。`mars-cloud-dependencies`
给 `spring-boot:run` 与测试 JVM 的配置已同时带上两者；`java -jar` 与容器入口同样需要自己写上。

## 验证

```bash
mvn -pl mars-cloud-nacos-spring-boot-starter -am test
```

契约测试覆盖自动装配注册、命名生成、离线模式、Namespace 一致性、Group、Data ID、
导入顺序与禁止 `optional:`。默认值用例覆盖注册中心健康检查与注册地址的推导条件。
