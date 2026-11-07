package cn.byteforce.seedj.read;

/**
 * 读审计出口。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@FunctionalInterface
public interface ReadAuditSink {

    /**
     * 记录一次读访问。
     *
     * @param event 审计事件
     */
    void record(ReadAccessEvent event);

    /**
     * 空实现。
     *
     * @return 不记录任何事件的 sink
     */
    static ReadAuditSink noop() {
        return event -> {};
    }
}
