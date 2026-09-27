package com.mars.cloud.job.internal;

/**
 * 每次任务执行开一个没有父级的根 span。调度中心的协议不带 traceparent，所以不从调度中心续接。
 *
 * <p>接口里不出现追踪库的类型：classpath 上没有 Micrometer Tracing 时使用 {@link #NONE}，不建 span。
 */
public interface JobTracing {

    JobTracing NONE = (handler, jobId, logId) -> Scope.NONE;

    Scope start(String handler, int jobId, long logId);

    interface Scope extends AutoCloseable {

        Scope NONE = new Scope() {
            @Override
            public String traceId() {
                return null;
            }

            @Override
            public void error(Throwable failure) {
            }

            @Override
            public void close() {
            }
        };

        /** 本次执行的 traceId；没有追踪实现时为 null。 */
        String traceId();

        void error(Throwable failure);

        @Override
        void close();
    }
}
