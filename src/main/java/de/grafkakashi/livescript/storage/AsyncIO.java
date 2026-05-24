package de.grafkakashi.livescript.storage;

import de.grafkakashi.livescript.LiveScriptMod;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dedicated executor for filesystem IO. Keeps disk latency off the server
 * thread — critical on the 300+ mod server where every tick matters.
 *
 * Pool size: 2 threads. One handles the common case (saving the script you
 * just edited), one handles the parallel work (history snapshot, list refresh).
 * Larger pools don't help — disk is the bottleneck, not CPU.
 *
 * Daemon threads: yes, so they don't block JVM shutdown if the executor wasn't
 * cleanly closed. We DO close it on server stop via {@link #shutdown()} as the
 * happy path; daemon is the safety net.
 */
public final class AsyncIO {
    /**
     * Volatile because re-initialisation happens off the server thread and we
     * want a happens-before guarantee for readers on other threads.
     *
     * <p>Why mutable at all: in single-player, "leave to title" fires
     * ServerStoppingEvent which calls {@link #shutdown()}. If the player then
     * opens a different world, the mod classes are already loaded — a static
     * final pool wouldn't be re-created, and any submit() would throw
     * RejectedExecutionException because the pool is terminated.
     *
     * <p>The {@link #ensureRunning()} helper restores the pool lazily on
     * first use after shutdown. Cheap (double-checked) — the common path
     * just sees a running pool and returns immediately.
     */
    private static volatile ExecutorService EXEC = newPool();

    private static ExecutorService newPool() {
        return Executors.newFixedThreadPool(2, new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger(1);
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "livescript-io-" + n.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        });
    }

    /**
     * Lazily resurrect the pool if a previous server-stop killed it. Single-
     * player rejoin path lands here. Multiple threads racing get serialised
     * by the synchronized block; whichever runs second sees the new pool and
     * returns immediately without allocating a second one.
     */
    private static ExecutorService ensureRunning() {
        ExecutorService current = EXEC;
        if (!current.isShutdown()) return current;
        synchronized (AsyncIO.class) {
            if (EXEC.isShutdown()) {
                LiveScriptMod.LOGGER.debug("[asyncio] pool was shut down; creating new one");
                EXEC = newPool();
            }
            return EXEC;
        }
    }

    private AsyncIO() {}

    /** Run a blocking IO operation on the IO thread, returning a future. */
    public static CompletableFuture<Void> run(IORunnable action) {
        return CompletableFuture.runAsync(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                LiveScriptMod.LOGGER.warn("async IO failed", t);
                throw new RuntimeException(t);
            }
        }, ensureRunning());
    }

    public static <T> CompletableFuture<T> supply(IOSupplier<T> action) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return action.get();
            } catch (Throwable t) {
                LiveScriptMod.LOGGER.warn("async IO failed", t);
                throw new RuntimeException(t);
            }
        }, ensureRunning());
    }

    public static void shutdown() {
        // Leave the field pointing at the now-shutdown executor so
        // isShutdown()-checks in ensureRunning() work. Next submit will
        // resurrect it lazily.
        EXEC.shutdown();
    }

    @FunctionalInterface public interface IORunnable { void run() throws Exception; }
    @FunctionalInterface public interface IOSupplier<T> { T get() throws Exception; }
}
