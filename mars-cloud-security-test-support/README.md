# mars-cloud-security-test-support

仅供测试使用的 RSA 签发器与回环 JWKS HTTP 服务。宿主必须以 Maven `test` scope 引入，不加入部署包。

```java
try (var issuer = new TestIdentityProvider()) {
    String token = issuer.token("alice", "sample", "upms");
    // issuer.issuer() 与 issuer.jwksUri() 提供本次测试的信任配置。
}
```

每个实例生成独立 RSA 密钥。`rotate(true)` 发布新密钥并保留旧公钥；`rotate(false)` 只发布新公钥。`jwksStatus(503)` 可测试刷新失败，计数器可检查公钥缓存和元数据发现。`claims` / `sign` 支持过期、未来 nbf、错误 audience 和缺字段等测试。关闭实例释放监听端口。

`TestIdentityProviderProcess` 仅通过测试 classpath 启动，参数为已存在的临时目录。它输出 issuer 配置与权限 0600 的临时令牌文件，不输出令牌到标准输出，也不接受私钥命令行参数。调用脚本负责结束自己创建的进程并删除这些临时文件。

此模块不提供用户登录、OAuth 授权、刷新令牌或生产签发能力。
