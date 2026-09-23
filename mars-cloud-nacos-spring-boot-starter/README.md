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

本 starter 在环境末尾追加一个最低优先级的属性源，任何显式配置（环境变量、配置中心、`application.yml`）都覆盖它。

| 属性 | 默认值 | 说明 |
| --- | --- | --- |
| `spring.cloud.discovery.client.composite-indicator.enabled` | `false` | 根健康端点不含注册中心检查（`discoveryComposite`），见下文 |
| `spring.cloud.nacos.discovery.ip` | 取 `server.address` 的规范形式 | 只在 `server.address` 是具体的 IPv4 地址时写入，见下文「注册地址」 |

停机时，Spring Cloud Alibaba 的优雅停机先注销实例并关闭 Nacos 客户端，再按
`spring.cloud.nacos.discovery.graceful-shutdown-wait-time`（默认 10 秒）等待，之后应用才真正停止。
等待期间如果有人查询健康端点（例如实例监控的轮询），注册中心检查会查询 Nacos，Nacos 客户端就被重新创建，
停机中途重新连接 Nacos。关闭这项检查后不再发生。它只在根健康端点里，存活与就绪探针（`liveness`、`readiness`）
不受影响。需要这项检查的部署物可以显式设为 `true`。

### 注册地址

Spring Cloud Alibaba 在没有配置注册地址时注册第一块非回环网卡的地址，不参考 `server.address`。
业务端口只绑定在某个地址上时，调用方按另一块网卡的地址连接会失败。所以 `server.address` 是具体的 IPv4 地址字面量时，
本 starter 让注册地址取它的规范形式（四段十进制，例如 `127.1` 写成 `127.0.0.1`）。下面几种情况不写入，
注册地址保持 Spring Cloud Alibaba 的默认：

- `server.address` 为通配地址（`0.0.0.0`、`::`）、主机名或未配置。
- `server.address` 是 IPv6 地址：注册地址会被拼进 `http://<地址>:<端口>` 形式的实例地址，不带方括号时无效，
  IPv6 部署显式配置 `spring.cloud.nacos.discovery.ip`。
- 已经显式决定了注册地址怎么选取：配置了 `spring.cloud.nacos.discovery.ip`、`network-interface`、`ip-type`，
  或 `spring.cloud.inetutils.preferred-networks`、`ignored-interfaces`、`use-only-site-local-interfaces` 中的任何一项。
  注册地址一旦给出，Spring Cloud Alibaba 就不再看这些配置项，所以推导会让位于它们。

注册地址由本 starter 给出后，Spring Cloud Alibaba 不再把本机 IPv6 地址写进实例元数据 `IPv6`，这一项只在它自己选取注册地址时写入。
注册地址需要与绑定地址不同时，显式配置 `spring.cloud.nacos.discovery.ip` 与 `spring.cloud.nacos.discovery.port`。

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
