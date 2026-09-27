package com.mars.cloud.job;

/**
 * 一次任务执行的上下文，由执行器传给 {@link JobHandler} 方法。
 *
 * <p>周期任务不代表任何终端用户，执行期间没有调用方身份；任务里经服务调用组件访问其他服务时不带身份头。
 */
public interface JobContext {

    /** 调度中心里的任务编号。 */
    int jobId();

    /** 本次执行在调度中心的日志编号，调度中心页面按它查看执行日志。 */
    long logId();

    /** 调度中心下发的任务参数；没有参数时为空字符串，不为 null。 */
    String param();

    /** 分片广播时本执行器的分片序号，从 0 开始；不是分片广播时为 0。 */
    int shardIndex();

    /** 分片广播时的分片总数；不是分片广播时为 1。 */
    int shardTotal();

    /**
     * 写一行执行日志：写进本次执行的日志文件（调度中心页面可见），同时以 INFO 级别写进服务日志。
     *
     * @param pattern   消息模板，占位符写法与 SLF4J 相同（{@code {}}）
     * @param arguments 占位符的取值；最后一个参数是异常时，堆栈一并写入
     */
    void log(String pattern, Object... arguments);
}
