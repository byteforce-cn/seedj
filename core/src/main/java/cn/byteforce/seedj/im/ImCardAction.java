package cn.byteforce.seedj.im;

import java.util.Objects;
import java.util.UUID;

/**
 * 一次卡片动作回传：{@code messageId + actionId} 是幂等键。
 *
 * @param messageId 卡片消息
 * @param actionId 卡片上的动作标识
 * @param tenantId 租户
 * @param conversationId 会话
 * @param actorId 操作人
 * @author ByteForce
 * @since 1.0.0
 */
public record ImCardAction(
        UUID messageId, String actionId, String tenantId, UUID conversationId, String actorId) {

    public ImCardAction {
        Objects.requireNonNull(messageId, "messageId");
        actionId = text(actionId, "actionId");
        tenantId = text(tenantId, "tenantId");
        Objects.requireNonNull(conversationId, "conversationId");
        actorId = text(actorId, "actorId");
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
