package cn.byteforce.seedj.starter.im;

import cn.byteforce.seedj.im.spi.ImMessageStore;
import java.util.Collection;
import java.util.Objects;

/**
 * {@code seedj.im.push.provider=coord}：先投本节点，再把投递意向交给 {@link ImCoordPush} 广播。
 *
 * <p>本地投递不依赖事件总线，所以单节点部署即使 coord 不可用也照常工作；跨节点的部分失败只 影响其它节点上的实时性，不丢消息。
 *
 * @author ByteForce
 * @since 1.0.0
 */
final class CoordPushProvider implements ImPushProvider {

    private final ImPushProvider local;

    private final ImCoordPush coord;

    CoordPushProvider(ImPushProvider local, ImCoordPush coord) {
        this.local = Objects.requireNonNull(local, "local");
        this.coord = Objects.requireNonNull(coord, "coord");
    }

    @Override
    public void deliver(String subjectId, byte cmd, Object body) {
        local.deliver(subjectId, cmd, body);
        coord.relay(java.util.List.of(subjectId), cmd, body);
    }

    @Override
    public void deliverMany(Collection<String> subjectIds, byte cmd, Object body) {
        local.deliverMany(subjectIds, cmd, body);
        coord.relay(subjectIds, cmd, body);
    }

    ImDeliverySubscriber subscriber(ImMessagingService messaging, ImMessageStore messages) {
        return coord.subscriber(messaging, messages);
    }
}
