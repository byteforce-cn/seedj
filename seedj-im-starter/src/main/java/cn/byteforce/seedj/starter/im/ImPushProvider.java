package cn.byteforce.seedj.starter.im;

import java.util.Collection;

/**
 * 投递端口：把一帧推给某个主体（或其全部设备）。
 *
 * <p>只有一个实现 {@link LocalPushProvider}（本节点 {@link ChannelRegistry}）在 P1–P3 生效；跨节点 广播（coord）在 P4
 * 落地，届时新增实现而不改调用方。
 *
 * <p>投递是尽力而为的：连接不可写就丢弃，客户端下次增量拉取会补上。实现不得阻塞、不得重试。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface ImPushProvider {

    /**
     * 推给某个主体的全部在线连接。
     *
     * @param subjectId 主体
     * @param cmd 命令字
     * @param body 帧体
     */
    void deliver(String subjectId, byte cmd, Object body);

    /**
     * 推给一组主体。
     *
     * @param subjectIds 主体集合
     * @param cmd 命令字
     * @param body 帧体
     */
    void deliverMany(Collection<String> subjectIds, byte cmd, Object body);
}
