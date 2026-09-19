package com.mars.cloud.common.exception;

/**
 * 分布式锁获取失败。
 *
 * <p>框架**不绑定任何具体锁实现**：谁实现锁（Redis / Redisson / 数据库 / ZooKeeper），
 * 谁在获取失败时抛这个异常，Servlet 侧的全局异常处理会把它映射成 HTTP 409 +
 * 统一响应信封。
 *
 * <p>为什么要在 common 里自带一个而不是复用第三方类型：第三方锁组件往往把自己的
 * 自动装配也一起带进 classpath，最轻量的 core 包同样是无条件装配的——框架为了一个
 * 异常类型就把使用方的启动过程交给第三方的自动配置，代价过大。
 *
 * @since 2026-09-19
 */
public class LockFailureException extends RuntimeException {

    public LockFailureException(String message) {
        super(message);
    }

    public LockFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
