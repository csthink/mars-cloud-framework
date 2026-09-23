package com.mars.cloud.observability;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.util.ContextInitializer;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LoggingSystemProperty;

/**
 * 打开结构化日志的用例类结束后，把日志输出恢复成测试 JVM 启动时的文本格式。
 *
 * <p>构建的日志检查按文本形态识别告警与错误，JSON 行它看不见。不恢复的话，同一个测试 JVM 里排在后面的
 * 用例日志都会是 JSON，原因有两处：Spring Boot 把 {@code logging.structured.format.console} 写成系统属性，
 * 之后启动的应用即使没有配置它也按这个属性输出；不经过 {@code SpringApplication} 的用例
 * （例如 {@code ApplicationContextRunner}）不会重新初始化日志框架，沿用上一个应用留下的 JSON 配置。
 */
final class StructuredLoggingFormatCleanup implements AfterAllCallback {

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        System.clearProperty(LoggingSystemProperty.CONSOLE_STRUCTURED_FORMAT.getEnvironmentVariableName());
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        loggerContext.reset();
        new ContextInitializer(loggerContext).autoConfig();
    }
}
