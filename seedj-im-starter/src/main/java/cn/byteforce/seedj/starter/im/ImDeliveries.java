package cn.byteforce.seedj.starter.im;

import cn.byteforce.seedj.im.ImMessage;
import cn.byteforce.seedj.starter.im.protocol.body.ChatDeliver;
import java.util.Map;

/** 消息到协议视图的投影：撤回后清空正文，编辑/撤回时间只在读路径出现。 */
final class ImDeliveries {

    private ImDeliveries() {}

    static ChatDeliver of(ImMessage message) {
        Map<String, Object> visible = message.recalledAt() == null ? message.body() : Map.of();
        return new ChatDeliver(
                message.id().toString(),
                message.conversationId().toString(),
                message.seq(),
                message.senderId(),
                message.kind().name(),
                visible,
                message.createdAt().toEpochMilli(),
                message.editedAt() == null ? null : message.editedAt().toEpochMilli(),
                message.recalledAt() == null ? null : message.recalledAt().toEpochMilli());
    }
}
