package cn.byteforce.seedj.starter.ai.approval.im;

import cn.byteforce.seedj.ai.ApprovalStatus;
import cn.byteforce.seedj.starter.ai.approval.ApprovalNotification;
import cn.byteforce.seedj.starter.ai.approval.ApprovalNotifier;
import cn.byteforce.seedj.starter.im.CardMessage;
import cn.byteforce.seedj.starter.im.ImSystemNotifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 审批卡片通知：每个审批人一张 {@code agent.approval.card.v1} 卡片，动作 approve/reject。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class ApprovalCardNotifier implements ApprovalNotifier {

    /** 卡片 schema。 */
    public static final String SCHEMA = "agent.approval.card.v1";

    private static final Logger LOG = LoggerFactory.getLogger(ApprovalCardNotifier.class);

    private final ImSystemNotifier notifier;

    public ApprovalCardNotifier(ImSystemNotifier notifier) {
        this.notifier = Objects.requireNonNull(notifier, "notifier");
    }

    @Override
    public void pending(ApprovalNotification notification) {
        for (UUID approver : notification.approvers()) {
            try {
                notifier.sendCard(
                        notification.tenantId().toString(),
                        approver.toString(),
                        card(notification, approver));
            } catch (RuntimeException error) {
                LOG.warn(
                        "审批卡片发送失败（HTTP 审批仍可用）: {} -> {}",
                        notification.decisionId(),
                        approver,
                        error);
            }
        }
    }

    @Override
    public void resolved(ApprovalNotification notification) {
        for (UUID approver : notification.approvers()) {
            try {
                notifier.sendText(
                        notification.tenantId().toString(),
                        approver.toString(),
                        "决策 " + notification.decisionId() + " 已" + status(notification.status()));
            } catch (RuntimeException error) {
                LOG.warn("审批结果通知失败: {}", notification.decisionId(), error);
            }
        }
    }

    private static CardMessage card(ApprovalNotification notification, UUID approver) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("decision_id", notification.decisionId().toString());
        data.put("args_hash", notification.argsHash());
        data.put("command_type", notification.commandType());
        return new CardMessage(
                SCHEMA,
                "待审批：" + notification.commandType(),
                notification.rationale(),
                "待审批 " + notification.commandType() + "，decision " + notification.decisionId(),
                notification.decisionId() + ":" + approver,
                data,
                List.of(
                        new CardMessage.CardAction("approve", "批准"),
                        new CardMessage.CardAction("reject", "驳回")));
    }

    private static String status(ApprovalStatus status) {
        return switch (status) {
            case APPROVED -> "批准";
            case REJECTED -> "驳回";
            case EXPIRED -> "过期";
            default -> status.wire();
        };
    }
}
