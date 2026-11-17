package cn.byteforce.seedj.im.spi;

import java.util.UUID;

/**
 * 已读与同步游标。
 *
 * <p>两类游标刻意放在一起：它们都是「只前进」的高频状态，没有审计价值，与消息、会话的生命周期 完全不同，因此不进 {@link ImConversationStore} 的事务边界。
 *
 * <p>两个 {@code advance*} 方法都只前进不后退（取当前值与入参的较大者），返回生效后的值—— 多端并发上报时以此收敛，不需要调用方加锁。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface ImCursorStore {

    /**
     * 某主体在某会话的最后已读序号。
     *
     * @param conversationId 会话标识
     * @param subjectId 主体
     * @return 序号；从未上报过为 {@code 0}
     */
    long readSeq(UUID conversationId, String subjectId);

    /**
     * 上报已读。
     *
     * @param conversationId 会话标识
     * @param subjectId 主体
     * @param seq 已读到的序号
     * @return 生效后的已读序号
     */
    long advanceRead(UUID conversationId, String subjectId, long seq);

    /**
     * 某设备已同步到的全局序号。
     *
     * @param tenantId 租户
     * @param subjectId 主体
     * @param deviceId 设备
     * @return 序号；从未同步过为 {@code 0}
     */
    long syncSeq(String tenantId, String subjectId, String deviceId);

    /**
     * 记录设备同步进度。
     *
     * @param tenantId 租户
     * @param subjectId 主体
     * @param deviceId 设备
     * @param seq 已同步到的序号
     * @return 生效后的同步序号
     */
    long advanceSync(String tenantId, String subjectId, String deviceId, long seq);
}
