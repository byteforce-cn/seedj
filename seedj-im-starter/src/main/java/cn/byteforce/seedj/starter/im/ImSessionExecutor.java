package cn.byteforce.seedj.starter.im;

import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 会话执行器：把命令处理从 Netty IO 线程挪到有界线程池，并按 owner 分流。
 *
 * <p>Event loop 里只做解码与入队；任何 JDBC 都必须在这里执行。每条泳道是单线程队列，因此同一个 owner 的命令串行执行，跨 owner 并行。队列满时抛 {@link
 * RejectedExecutionException}，由分发层回 {@code 429}——不丢消息、不阻塞 IO 线程。
 *
 * @author ByteForce
 * @since 1.0.0
 */
final class ImSessionExecutor implements AutoCloseable {

    public static final int DEFAULT_THREADS = 4;

    public static final int DEFAULT_QUEUE_CAPACITY = 4096;

    private final ExecutorService[] lanes;

    ImSessionExecutor(int threads, int queueCapacity) {
        int size = threads <= 0 ? DEFAULT_THREADS : threads;
        int capacity = queueCapacity <= 0 ? DEFAULT_QUEUE_CAPACITY : queueCapacity;
        this.lanes = new ExecutorService[size];
        for (int i = 0; i < size; i++) {
            AtomicInteger counter = new AtomicInteger();
            lanes[i] =
                    new ThreadPoolExecutor(
                            1,
                            1,
                            0L,
                            TimeUnit.MILLISECONDS,
                            new ArrayBlockingQueue<>(capacity),
                            runnable -> {
                                Thread thread =
                                        new Thread(
                                                runnable,
                                                "seedj-im-session-" + counter.incrementAndGet());
                                thread.setDaemon(true);
                                return thread;
                            });
        }
    }

    /**
     * 提交一次命令处理。
     *
     * @param owner 分流键（调用方 subject）
     * @param task 任务
     * @throws RejectedExecutionException 队列已满
     */
    void execute(String owner, Runnable task) {
        int index = Math.floorMod(owner == null ? 0 : owner.hashCode(), lanes.length);
        lanes[index].execute(task);
    }

    /** 泳道数，供指标与测试断言。 */
    int lanes() {
        return lanes.length;
    }

    @Override
    public void close() {
        Arrays.stream(lanes).forEach(ExecutorService::shutdownNow);
    }
}
