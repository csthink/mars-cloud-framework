package com.mars.cloud.common.context;

import java.util.Objects;
import java.util.Optional;

/**
 * 阻塞线程模型的调用方上下文持有者。
 *
 * <p>适用于 Servlet、Feign 与消息消费等一个调用固定使用一个线程的路径。Reactive 代码应把
 * {@link CallerContext} 放入 Reactor Context，不得使用本类。
 *
 * <p>{@link #open(CallerContext)} 返回的 scope 支持嵌套，并在关闭时恢复上一层上下文。scope
 * 必须由创建它的线程按后进先出顺序关闭，错误顺序会立即失败，避免静默污染线程池。
 *
 * @since 2026-09-20
 */
public final class CallerContextHolder {

    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();

    private CallerContextHolder() {
    }

    /**
     * 返回当前线程的调用方上下文。
     */
    public static Optional<CallerContext> current() {
        Frame frame = CURRENT.get();
        return frame == null ? Optional.empty() : Optional.of(frame.context());
    }

    /**
     * 在当前线程打开一层调用方上下文。
     *
     * @param context 要安装的上下文
     * @return 必须关闭的 scope
     */
    public static Scope open(CallerContext context) {
        Objects.requireNonNull(context, "context 不能为空");
        Frame frame = new Frame(context, CURRENT.get());
        CURRENT.set(frame);
        return new ThreadLocalScope(Thread.currentThread(), frame);
    }

    /**
     * 可用于 try-with-resources 的上下文作用域。
     */
    public interface Scope extends AutoCloseable {

        @Override
        void close();
    }

    private record Frame(CallerContext context, Frame previous) {
    }

    private static final class ThreadLocalScope implements Scope {

        private final Thread owner;
        private final Frame frame;
        private boolean closed;

        private ThreadLocalScope(Thread owner, Frame frame) {
            this.owner = owner;
            this.frame = frame;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("CallerContext scope 必须由创建它的线程关闭");
            }
            if (CURRENT.get() != frame) {
                throw new IllegalStateException("CallerContext scope 必须按后进先出顺序关闭");
            }

            if (frame.previous() == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(frame.previous());
            }
            closed = true;
        }
    }
}
