package cn.byteforce.seedj.im.spi;

/**
 * 全局单调序号分配器。
 *
 * <p>IM 的消息序号、成员任期序号与同步游标共用同一个序列，因此「会话变更」与「消息」可以直接比较先后。
 *
 * <p>语义要求：单调递增；<b>不要求连续</b>——序列回滚、重启、缓存都会产生空洞，客户端必须容忍。 实现必须是跨节点全局的（PostgreSQL 用序列，内存实现用进程内计数器）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface ImSequenceAllocator {

    /**
     * 取下一个序号。
     *
     * @return 严格递增的正数
     */
    long next();
}
