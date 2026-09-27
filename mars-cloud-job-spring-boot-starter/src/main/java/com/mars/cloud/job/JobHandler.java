package com.mars.cloud.job;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 把 Spring Bean 的一个方法登记为周期任务。名称对应调度中心任务配置里的 JobHandler 字段。
 *
 * <p>方法无参，或只接受一个 {@link JobContext}；返回值被忽略。正常返回表示执行成功，抛出异常表示执行失败，
 * 异常摘要作为执行结果回报调度中心，堆栈写进本次执行的日志。
 *
 * <p>任务方法只做「调用服务里已有的业务逻辑」这一件事，并且必须幂等：调度中心的失败重试、手动补跑
 * 与执行器重启后的重新触发都可能让同一时刻的任务执行不止一次。
 *
 * <p>名称在一个执行器内唯一，重名、方法签名不符或方法是静态方法时应用启动失败。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface JobHandler {

    /** 任务名，与调度中心任务配置里的 JobHandler 一致；不能为空白，不能含空白字符。 */
    String value();
}
