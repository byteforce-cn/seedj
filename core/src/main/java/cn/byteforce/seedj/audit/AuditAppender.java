package cn.byteforce.seedj.audit;

/**
 * 审计追加 SPI：命令受理成功时在同一事务内写入审计链。
 *
 * <p>语义权威为 {@code 05-audit-contract/hash-chain.spec.md}：链式哈希、序列连续、
 * 系统时间轴 append-only。运行时保证：仅 accepted 命令调用；在命令事务内调用
 * （审计与命令/事件同生共死）；锚行串行分配序列。</p>
 *
 * @param <ID> 审计标识类型
 * @author ByteForce
 * @since 1.0.0
 */
public interface AuditAppender<ID> {

    /**
     * 追加一条审计记录（当前事务内）。
     *
     * @param request 追加请求
     */
    void append(AuditAppendRequest<ID> request);
}
