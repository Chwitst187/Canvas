package ca.spottedleaf.moonrise.common.util;

import ca.spottedleaf.concurrentutil.executor.thread.BalancedPrioritisedThreadPool;
import ca.spottedleaf.concurrentutil.numa.OSNuma;
import ca.spottedleaf.moonrise.common.PlatformHooks;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class MoonriseCommon {

    private static final Logger LOGGER = LogUtils.getClassLogger();

    // Canvas start - replace moonrise executor
    public static final long WORKER_QUEUE_HOLD_TIME = (long)(1.0e6); // 1ms
    public static final long IO_WORKER_QUEUE_HOLD_TIME = (long)(1.0e6); // 1ms
    public static io.canvasmc.canvas.world.chunk.BalancedChunkSystem WORKER_POOL;
    public static io.canvasmc.canvas.world.chunk.BalancedChunkSystem IO_POOL;

    public static io.canvasmc.canvas.world.chunk.BalancedChunkSystem.OrderedStreamGroup SERVER_GROUP;
    public static io.canvasmc.canvas.world.chunk.BalancedChunkSystem.OrderedStreamGroup SERVER_IO_GROUP;
    // Canvas end - replace moonrise executor

    public static void adjustWorkerThreads(final int configWorkerThreads, final int configIoThreads) {
        int defaultWorkerThreads = OSNuma.getNativeInstance().getTotalCores()  / 2;
        if (defaultWorkerThreads <= 4) {
            defaultWorkerThreads = defaultWorkerThreads <= 3 ? 1 : 2;
        } else {
            defaultWorkerThreads = defaultWorkerThreads / 2;
        }
        defaultWorkerThreads = Integer.getInteger(PlatformHooks.get().getBrand() + ".WorkerThreadCount", Integer.valueOf(defaultWorkerThreads));

        int workerThreads = configWorkerThreads;

        if (workerThreads <= 0) {
            workerThreads = defaultWorkerThreads;
        }

        final int ioThreads = Math.max(1, configIoThreads);

    // Canvas start - replace moonrise executor
        if (WORKER_POOL != null && IO_POOL != null) {
            // we just need to adjust the thread count, cannot recreate instances
            WORKER_POOL.adjustThreadCount(workerThreads);
            IO_POOL.adjustThreadCount(ioThreads);
        } else {
            // setup chunk system
            LOGGER.info("Setting up ls_wg chunk system");

            // build instances
            WORKER_POOL = new io.canvasmc.canvas.world.chunk.BalancedChunkSystem(
                WORKER_QUEUE_HOLD_TIME, workerThreads,
                new io.canvasmc.canvas.world.chunk.BalancedChunkSystem.ThreadBuilder() {
                    @Override
                    public void accept(final Thread thread) {
                        thread.setPriority(io.canvasmc.canvas.Config.INSTANCE.chunks.threadPoolPriority);
                        thread.setDaemon(true);
                        thread.setUncaughtExceptionHandler((thread1, throwable) -> LOGGER.error("Uncaught exception in thread {}", thread1.getName(), throwable));
                        thread.setName("ls_wg worker #" + getAndIncrementId());
                    }
                }, "ls_wg"
            );
            IO_POOL = new io.canvasmc.canvas.world.chunk.BalancedChunkSystem(
                IO_WORKER_QUEUE_HOLD_TIME, ioThreads,
                new io.canvasmc.canvas.world.chunk.BalancedChunkSystem.ThreadBuilder() {
                    @Override
                    public void accept(final Thread thread) {
                        thread.setPriority(io.canvasmc.canvas.Config.INSTANCE.chunks.threadPoolPriority);
                        thread.setDaemon(true);
                        thread.setUncaughtExceptionHandler((thread1, throwable) -> LOGGER.error("Uncaught exception in thread {}", thread1.getName(), throwable));
                        thread.setName("ls_wg/io worker #" + getAndIncrementId());
                    }
                }, "ls_wg/io"
            );

            // setup server groups
            SERVER_GROUP = WORKER_POOL.createChunkOrderedStreamGroup();
            SERVER_IO_GROUP = IO_POOL.createChunkOrderedStreamGroup();
        }

        LOGGER.info("Running LS ChunkSystem with {} worker threads and {} io threads", workerThreads, ioThreads);
    }

    // Canvas end - replace moonrise executor

    public static void haltExecutors() {
        MoonriseCommon.WORKER_POOL.shutdown(false);
        LOGGER.info("Awaiting termination of worker pool for up to 60s...");
        if (!MoonriseCommon.WORKER_POOL.join(TimeUnit.SECONDS.toMillis(60L))) {
            LOGGER.error("Worker pool did not shut down in time!");
            MoonriseCommon.WORKER_POOL.halt(false);
        }

        MoonriseCommon.IO_POOL.shutdown(false);
        LOGGER.info("Awaiting termination of I/O pool for up to 60s...");
        if (!MoonriseCommon.IO_POOL.join(TimeUnit.SECONDS.toMillis(60L))) {
            LOGGER.error("I/O pool did not shut down in time!");
            MoonriseCommon.IO_POOL.halt(false);
        }
    }

    private MoonriseCommon() {}
}
