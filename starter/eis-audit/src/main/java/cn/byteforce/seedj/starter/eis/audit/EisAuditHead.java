package cn.byteforce.seedj.starter.eis.audit;

import java.time.Instant;

/**
 * 审计链头快照（锚定 SPI 的入参）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class EisAuditHead {

    private final long sequence;

    private final String hash;

    private final Instant anchoredAt;

    /**
     * 构造链头快照。
     *
     * @param sequence   链头序号（0 = 空链）
     * @param hash       链头哈希（空链为创世前序哈希）
     * @param anchoredAt 锚定时刻
     */
    public EisAuditHead(long sequence, String hash, Instant anchoredAt) {
        this.sequence = sequence;
        this.hash = hash;
        this.anchoredAt = anchoredAt;
    }

    /**
     * 获取链头序号。
     *
     * @return 序号
     */
    public long getSequence() {
        return sequence;
    }

    /**
     * 获取链头哈希。
     *
     * @return 小写 hex
     */
    public String getHash() {
        return hash;
    }

    /**
     * 获取锚定时刻。
     *
     * @return 锚定时刻
     */
    public Instant getAnchoredAt() {
        return anchoredAt;
    }
}
