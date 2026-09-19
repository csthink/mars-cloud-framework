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

## 验证

```bash
mvn -pl mars-cloud-nacos-spring-boot-starter -am test
```

契约测试覆盖自动装配注册、命名生成、离线模式、Namespace 一致性、Group、Data ID、
导入顺序与禁止 `optional:`。
