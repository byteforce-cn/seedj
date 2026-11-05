package cn.byteforce.seedj.audit.spi;

import cn.byteforce.seedj.audit.AuditEntry;

import java.util.List;

/**
 * 审计存储端口（SPI）。
 *
 * <p>只描述审计链对存储的<b>语义要求</b>，不绑定任何数据库方言。具体实现
 * （PostgreSQL、MySQL、内存等）位于独立适配器模块，由自动装配按条件注入。</p>
 *
 * <p><b>事务契约</b>：{@link #nextSequenceAndPrevHash()}、{@link #insert(AuditEntry)}、
 * {@link #updateHead(String)} 由调用方在同一事务内依次调用；实现<b>不得</b>自行开启、
 * 提交或回滚事务。命令回滚时序号分配与条目写入一并回滚，且不留序号缺口。</p>
 *
 * <p><b>连续性契约</b>：序号从 1 开始连续递增、无缺口、无重复；空链时前序哈希为创世哈希
 * （由实现初始化，链头 {@code last_hash} 与之相等）。</p>
 *
 * @param <ID> 审计标识类型
 * @author ByteForce
 * @since 1.0.0
 */
public interface AuditStore<ID> {

    /**
     * 原子地分配下一序号，并返回当前链头哈希作为新条目的前序哈希。
     *
     * <p>实现须在调用方事务内获取锚行排他锁并持有至事务提交，使并发 {@code append}
     * 在审计处串行；回滚后该序号不得被消耗。空链时返回创世哈希。</p>
     *
     * @return 分配结果；{@link Head#getHash()} 为<b>前序哈希</b>
     */
    Head nextSequenceAndPrevHash();

    /**
     * 插入一条审计条目（含已计算好的 hash 与 prevHash）。
     *
     * <p>实现负责将 {@link AuditEntry#getPayload()} 以规范 JSON 落库，并保证
     * {@link #loadAll()} 回读后经 {@link cn.byteforce.seedj.audit.CanonicalHasher} 重算
     * 得到相同哈希（时间微秒截断、规范序列化由实现与写入侧共同遵守）。</p>
     *
     * @param entry 待插入条目
     */
    void insert(AuditEntry<ID> entry);

    /**
     * 将链头哈希更新为最新条目的哈希。
     *
     * @param hash 最新条目哈希
     */
    void updateHead(String hash);

    /**
     * 读取全链条目，按 sequence 升序。
     *
     * @return 条目列表；空表返回空列表，不返回 {@code null}
     */
    List<AuditEntry<ID>> loadAll();

    /**
     * 读取最近一次已提交事务的完整链头快照。
     *
     * <p>返回值中的 sequence 与 hash 必须来自同一提交版本，不得出现“序号已增、
     * 哈希仍旧”的中间态。</p>
     *
     * @return 链头；{@link Head#getHash()} 为<b>当前链头哈希</b>
     */
    Head head();

    /**
     * 链头/分配结果视图。
     */
    final class Head {

        private final long sequence;

        private final String hash;

        public Head(long sequence, String hash) {
            this.sequence = sequence;
            this.hash = hash;
        }

        public long getSequence() {
            return sequence;
        }

        public String getHash() {
            return hash;
        }
    }
}
