package com.mars.cloud.common.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class CallerContextHolderTest {

    private static final CallerContext OUTER = new CallerContext("user-1", "portal", "default");
    private static final CallerContext INNER = new CallerContext("user-2", "console", "default");

    @Test
    @DisplayName("scope 关闭后清理线程上下文")
    void scopeClearsContext() {
        assertThat(CallerContextHolder.current()).isEmpty();

        try (CallerContextHolder.Scope ignored = CallerContextHolder.open(OUTER)) {
            assertThat(CallerContextHolder.current()).contains(OUTER);
        }

        assertThat(CallerContextHolder.current()).isEmpty();
    }

    @Test
    @DisplayName("嵌套 scope 关闭后恢复外层上下文")
    void nestedScopeRestoresPreviousContext() {
        try (CallerContextHolder.Scope outer = CallerContextHolder.open(OUTER)) {
            try (CallerContextHolder.Scope inner = CallerContextHolder.open(INNER)) {
                assertThat(CallerContextHolder.current()).contains(INNER);
            }
            assertThat(CallerContextHolder.current()).contains(OUTER);
        }

        assertThat(CallerContextHolder.current()).isEmpty();
    }

    @Test
    @DisplayName("重复关闭同一个 scope 不改变已恢复的上下文")
    void closeIsIdempotent() {
        CallerContextHolder.Scope scope = CallerContextHolder.open(OUTER);

        scope.close();
        scope.close();

        assertThat(CallerContextHolder.current()).isEmpty();
    }

    @Test
    @DisplayName("不按后进先出顺序关闭时立即失败且不破坏当前上下文")
    void outOfOrderCloseFailsWithoutCorruption() {
        CallerContextHolder.Scope outer = CallerContextHolder.open(OUTER);
        CallerContextHolder.Scope inner = CallerContextHolder.open(INNER);

        assertThatIllegalStateException()
                .isThrownBy(outer::close)
                .withMessage("CallerContext scope 必须按后进先出顺序关闭");
        assertThat(CallerContextHolder.current()).contains(INNER);

        inner.close();
        outer.close();
        assertThat(CallerContextHolder.current()).isEmpty();
    }

    @Test
    @DisplayName("普通 ThreadLocal 不把调用方上下文继承给子线程")
    void contextIsNotInheritedByChildThread() throws InterruptedException {
        AtomicReference<Optional<CallerContext>> childContext = new AtomicReference<>();

        try (CallerContextHolder.Scope ignored = CallerContextHolder.open(OUTER)) {
            Thread child = Thread.ofVirtual()
                    .start(() -> childContext.set(CallerContextHolder.current()));
            child.join();
            assertThat(childContext.get()).isEmpty();
            assertThat(CallerContextHolder.current()).contains(OUTER);
        }
    }

    @Test
    @DisplayName("scope 只能由创建它的线程关闭")
    void scopeMustCloseOnOwnerThread() throws InterruptedException {
        CallerContextHolder.Scope scope = CallerContextHolder.open(OUTER);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread child = Thread.ofVirtual().start(() -> {
            try {
                scope.close();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        child.join();

        assertThat(failure.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CallerContext scope 必须由创建它的线程关闭");
        assertThat(CallerContextHolder.current()).contains(OUTER);

        scope.close();
        assertThat(CallerContextHolder.current()).isEmpty();
    }
}
