package cn.byteforce.seedj.core.clock;

import cn.byteforce.seedj.core.clock.spi.GlobalSequencer;
import cn.byteforce.seedj.core.clock.spi.HybridStateStore;

import java.time.Instant;
import java.util.Objects;

/**
 * 混合逻辑时钟实现。
 *
 * <p>在标准 HLC 本地事件算法基础上，强制 {@link #now()} 返回的物理部分严格递增：
 * 若本次 HLC 物理值不大于上次已返回值，则推进到上次值 +1 微秒并重置逻辑计数器。
 * 这样既满足 {@code superseded_at > recorded_at}，又保留跨节点合并能力。</p>
 */
final class HybridClockImpl implements HybridClock {

    private static final long PERSIST_THRESHOLD_MICROS = 1_000L;

    private final String nodeId;
    private final Clock physical;
    private final HybridStateStore stateStore;

    private long physicalMicros;
    private long logical;
    private long lastEmittedMicros;
    private long lastPersistedMicros;

    HybridClockImpl(String nodeId, Clock physical,
                    HybridStateStore stateStore, GlobalSequencer sequencer) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.physical = Objects.requireNonNull(physical, "physical");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        Objects.requireNonNull(sequencer == null ? "" : "ok"); // sequencer 可选，保留参数
        HybridTimestamp restored = stateStore.load(nodeId);
        if (restored != null) {
            this.physicalMicros = restored.physicalMicros();
            this.logical = restored.logical();
            this.lastEmittedMicros = restored.physicalMicros();
            this.lastPersistedMicros = restored.physicalMicros();
        } else {
            this.physicalMicros = Long.MIN_VALUE;
            this.logical = 0L;
            this.lastEmittedMicros = Long.MIN_VALUE;
            this.lastPersistedMicros = Long.MIN_VALUE;
        }
    }

    @Override
    public Instant now() {
        return nowTimestamp().physicalInstant();
    }

    @Override
    public synchronized HybridTimestamp nowTimestamp() {
        long pt = HybridTimestamp.toMicros(physical.now());
        long prev = physicalMicros;

        physicalMicros = Math.max(prev, pt);
        logical = (physicalMicros == prev) ? logical + 1 : 0L;

        if (physicalMicros <= lastEmittedMicros) {
            physicalMicros = lastEmittedMicros + 1;
            logical = 0L;
        }
        lastEmittedMicros = physicalMicros;

        persistIfNeeded();
        return new HybridTimestamp(physicalMicros, logical);
    }

    @Override
    public synchronized void merge(HybridTimestamp remote) {
        if (remote == null) {
            return;
        }
        long pt = HybridTimestamp.toMicros(physical.now());
        long lp = physicalMicros;
        long lc = logical;
        long rm = remote.physicalMicros();
        long rc = remote.logical();

        long max = Math.max(Math.max(lp, rm), pt);
        long newLogical;
        if (max == lp && max == rm) {
            newLogical = Math.max(lc, rc) + 1;
        } else if (max == lp) {
            newLogical = lc + 1;
        } else if (max == rm) {
            newLogical = rc + 1;
        } else {
            newLogical = 0L;
        }
        physicalMicros = max;
        logical = newLogical;
        persistIfNeeded();
    }

    private void persistIfNeeded() {
        if (lastPersistedMicros != Long.MIN_VALUE
                && physicalMicros - lastPersistedMicros < PERSIST_THRESHOLD_MICROS) {
            return;
        }
        try {
            stateStore.save(nodeId, new HybridTimestamp(physicalMicros, logical));
            lastPersistedMicros = physicalMicros;
        } catch (RuntimeException ex) {
            // 持久化失败不阻塞取时；生产应接入日志与告警
        }
    }
}
