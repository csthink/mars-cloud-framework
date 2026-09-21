# mars-cloud-security-feign

连接 security starter 与 Feign starter 的 Servlet 权限适配模块。宿主引入后按 security starter 配置 issuer、audience 和 Web 运行时；权限服务通过 Nacos 等 DiscoveryClient 提供的服务发现定位。

模块只为 `mars-cloud-upms-service` 的权限客户端传递当前已验证令牌。客户端固定使用 POST、禁止重定向，并禁用 Feign 请求正文和凭据日志；普通 Feign 日志级别及 follow-redirects 属性不能放宽这些约束。其他 Feign 客户端不会自动获得令牌。

复用 Feign starter 的 LoadBalancer、超时限制、内部身份头与失败分类。PDP 不重试。该服务名只注册一个 `DownstreamFailureMapper`，宿主应使用本模块的 `PdpClient`，不再注册同名客户端或 mapper。

`PdpClient.decide(caller, action, resource)` 校验 caller 与当前认证一致，返回 `PdpDecision`。有效 deny 返回 `allowed=false`；使用方法权限表达式时，业务方法会被拒绝并返回 403。调用失败抛出只含稳定错误码的 `SecurityFailure`。应用如有已有错误码，可在应用适配层转换这些异常。

本模块用于 Servlet，不应加入 WebFlux gateway。`PdpFeignConfiguration` 只由专用 Feign 客户端加载，不要把它加入宿主组件扫描。
