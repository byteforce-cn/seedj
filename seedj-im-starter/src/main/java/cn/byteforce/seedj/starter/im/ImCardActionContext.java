package cn.byteforce.seedj.starter.im;

import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.im.ImMessage;
import java.util.Map;
import java.util.Objects;

/**
 * 卡片动作上下文：消息、卡片、被点的按钮与调用方。
 *
 * @param message 卡片消息
 * @param card 卡片 body
 * @param action 被点的按钮
 * @param caller 操作人
 * @author ByteForce
 * @since 1.0.0
 */
public record ImCardActionContext(
        ImMessage message, Map<String, Object> card, Map<String, Object> action, Caller caller) {

    public ImCardActionContext {
        Objects.requireNonNull(message, "message");
        card = card == null ? Map.of() : Map.copyOf(card);
        action = action == null ? Map.of() : Map.copyOf(action);
        Objects.requireNonNull(caller, "caller");
    }
}
