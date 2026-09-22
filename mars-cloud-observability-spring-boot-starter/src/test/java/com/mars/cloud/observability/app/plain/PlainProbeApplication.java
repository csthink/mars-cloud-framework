package com.mars.cloud.observability.app.plain;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 没有 Web 层的被测应用，供只关心日志与追踪装配的测试使用。 */
@SpringBootApplication
public class PlainProbeApplication {
}
