package cn.byteforce.seedj.starter.im;

import cn.byteforce.seedj.domain.event.DomainEvent;
import cn.byteforce.seedj.domain.event.EventType;
import java.util.List;
import java.util.Map;

/**
 * 跨节点投递信号：告诉其它节点「这些 subject 应该收到一条通知」。
 *
 * <p>消息正文<b>不</b>进事件总线：{@code messageId != null} 时各节点从共享消息存储回读消息再投递，
 * 这样事件总线拿不到正文，字段策略与保留期仍然只有存储一处口径。其余通知（已读、撤回、编辑、
 * 输入中、在线状态）本身只有元数据，直接带 body。
 *
 * @param origin 发出节点；收到自己发出的事件直接跳过
 * @param cmd 命令字（无符号）
 * @param recipients 目标 subject
 * @param messageId 需要回读的消息标识；{@code null} 表示 {@code body} 就是完整通知体
 * @param body 通知体（消息正文不在其中）
 * @author ByteForce
 * @since 1.0.0
 */
@EventType("im.delivery")
record ImDeliveryEvent(
        String origin, int cmd, List<String> recipients, String messageId, Map<String, Object> body)
        implements DomainEvent {

    /** 事件 topic。 */
    public static final String TOPIC = "im.delivery";

    public ImDeliveryEvent {
        recipients = recipients == null ? List.of() : List.copyOf(recipients);
        body = body == null ? Map.of() : Map.copyOf(body);
    }
}
