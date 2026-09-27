package com.mars.cloud.job.internal;

import com.mars.cloud.job.JobContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.MessageFormatter;
import org.springframework.util.ClassUtils;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 一次执行的上下文。执行日志写进本次执行的日志文件，并以任务方法所在类的名字写进服务日志。
 */
final class RunContext implements JobContext {

    private final Protocol.TriggerRequest trigger;
    private final JobLogFiles logs;
    private final Logger logger;

    RunContext(Protocol.TriggerRequest trigger, JobMethod method, JobLogFiles logs) {
        this.trigger = trigger;
        this.logs = logs;
        this.logger = LoggerFactory.getLogger(ClassUtils.getUserClass(method.bean()));
    }

    @Override
    public int jobId() {
        return trigger.jobId();
    }

    @Override
    public long logId() {
        return trigger.logId();
    }

    @Override
    public String param() {
        return trigger.executorParams() == null ? "" : trigger.executorParams();
    }

    @Override
    public int shardIndex() {
        return trigger.broadcastIndex();
    }

    @Override
    public int shardTotal() {
        return trigger.broadcastTotal() > 0 ? trigger.broadcastTotal() : 1;
    }

    @Override
    public void log(String pattern, Object... arguments) {
        FormattingTuple formatted = MessageFormatter.arrayFormat(pattern, arguments);
        Throwable failure = formatted.getThrowable();
        logs.append(trigger.logDateTime(), trigger.logId(),
                failure == null ? formatted.getMessage() : formatted.getMessage() + System.lineSeparator() + stackTrace(failure));
        if (failure == null) {
            logger.info(formatted.getMessage());
        } else {
            logger.info(formatted.getMessage(), failure);
        }
    }

    /** 只写执行日志文件，不写服务日志；用于执行器自己的开始、结束与失败记录。 */
    void record(String message) {
        logs.append(trigger.logDateTime(), trigger.logId(), message);
    }

    static String stackTrace(Throwable failure) {
        StringWriter out = new StringWriter();
        failure.printStackTrace(new PrintWriter(out));
        return out.toString().stripTrailing();
    }
}
