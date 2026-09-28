package com.mars.cloud.nacos.registration;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** A timed out operation occupies its sole worker until the underlying call actually ends. */
final class BoundedOperation implements AutoCloseable {
    private final AtomicBoolean active = new AtomicBoolean();
    private final ExecutorService executor;
    BoundedOperation(String name) {
        executor = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, name); t.setDaemon(true); return t; });
    }
    boolean busy() { return active.get(); }
    <T> T call(Callable<T> action, long deadline, Runnable cancel) throws Exception {
        if (!active.compareAndSet(false, true)) throw new RejectedExecutionException("Operation still running");
        Future<T> future;
        try {
            future = executor.submit(() -> { try { return action.call(); } finally { active.set(false); } });
        } catch (RuntimeException ex) { active.set(false); throw ex; }
        try { return future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
        catch (TimeoutException | InterruptedException ex) {
            cancel.run();
            // Do not cancel a queued Future: its finally block must release the active flag.
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            throw ex;
        }
        catch (ExecutionException ex) {
            if (ex.getCause() instanceof Exception cause) throw cause;
            throw new IllegalStateException("Operation failed");
        }
    }
    @Override public void close() { executor.shutdownNow(); }
}
