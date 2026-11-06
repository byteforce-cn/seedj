package cn.byteforce.seedj.infra.local.id;

import cn.byteforce.seedj.id.IdErrorCode;
import cn.byteforce.seedj.id.IdException;
import cn.byteforce.seedj.id.IdGenerator;

import java.util.function.LongSupplier;

/**
 * 雪花。
 *
 * <p>雪花布局（64 位正数）：
 * <pre>
 * 1 位符号(0) | 41 位毫秒时间戳（相对 epoch） | 10 位节点 | 12 位序列
 * </pre>
 * 单节点每毫秒 4096 个，可用约 69 年。
 *
 * <p>UUIDv7 布局见 RFC 9562 §5.7：48 位毫秒 + 版本(7) + 12 位序列 + 变体(10) + 62 位随机。
 *
 * <p>时钟回拨处理：回拨 ≤ {@value #MAX_BACKWARD_MS}ms 时冻结在上一毫秒继续发号
 * （宁可"借用"未来几毫秒，也不产生重复 ID）；超过阈值抛
 * {@link IdErrorCode#CLOCK_BACKWARDS}，由运维介入。
 */
public class SnowflakeIdGenerator implements IdGenerator<Long> {

    /** 默认 epoch：2024-01-01T00:00:00Z。 */
    public static final long DEFAULT_EPOCH_MILLIS = 1_704_067_200_000L;

    private static final int NODE_BITS = 10;
    private static final int SEQUENCE_BITS = 12;
    private static final long MAX_NODE_ID = (1L << NODE_BITS) - 1;
    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1;
    private static final long MAX_BACKWARD_MS = 5_000L;

    private final long epochMillis;
    private final long nodeId;
    private final LongSupplier clock;

    private long lastMillis = -1L;
    private long sequence = 0L;
    private volatile boolean closed;

    public SnowflakeIdGenerator(long nodeId) {
        this(nodeId, DEFAULT_EPOCH_MILLIS, System::currentTimeMillis);
    }

    public SnowflakeIdGenerator(long nodeId, long epochMillis) {
        this(nodeId, epochMillis, System::currentTimeMillis);
    }

    SnowflakeIdGenerator(long nodeId, long epochMillis, LongSupplier clock) {
        if (nodeId < 0 || nodeId > MAX_NODE_ID) {
            throw new IdException(IdErrorCode.INVALID_NODE_ID,
                    "nodeId must be in [0, " + MAX_NODE_ID + "]: " + nodeId);
        }
        this.nodeId = nodeId;
        this.epochMillis = epochMillis;
        this.clock = clock;
    }

    @Override
    public synchronized Long id() {
        ensureOpen();
        long now = clock.getAsLong();
        if (now < lastMillis) {
            long backward = lastMillis - now;
            if (backward > MAX_BACKWARD_MS) {
                throw new IdException(IdErrorCode.CLOCK_BACKWARDS,
                        "clock moved backwards by " + backward + "ms");
            }
            now = lastMillis;
        }

        if (now == lastMillis) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                // 本毫秒用尽：借用下一毫秒，保证单调且不阻塞
                now = lastMillis + 1;
            }
        } else {
            sequence = 0;
        }
        lastMillis = now;

        return ((now - epochMillis) << (NODE_BITS + SEQUENCE_BITS))
                | (nodeId << SEQUENCE_BITS)
                | sequence;
    }

    @Override
    public void close() {
        closed = true;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IdException(IdErrorCode.PROVIDER_CLOSED, "IdGenerator is closed");
        }
    }
}
