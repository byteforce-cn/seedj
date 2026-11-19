package cn.byteforce.seedj.starter.ai.approval.im;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import cn.byteforce.seedj.ai.ApprovalStatus;
import cn.byteforce.seedj.starter.ai.approval.ApprovalNotification;
import cn.byteforce.seedj.starter.im.CardMessage;
import cn.byteforce.seedj.starter.im.ImSystemNotifier;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 审批卡片通知：每个审批人一张，带 decision_id 与两个动作。 */
class ApprovalCardNotifierTest {

    private static final UUID DECISION = UUID.fromString("018f2e3a-1234-7000-8000-00000000a001");
    private static final UUID TENANT = UUID.fromString("018f2e3a-0000-7000-8000-0000000b0007");
    private static final UUID APPROVER = UUID.fromString("018f2e3a-0000-7000-8000-000000000119");

    @Test
    @DisplayName("resolved：给每个审批人发结果文本")
    void resolvedSendsText() {
        ImSystemNotifier notifier = mock(ImSystemNotifier.class);
        ApprovalCardNotifier cardNotifier = new ApprovalCardNotifier(notifier);
        ApprovalNotification notification =
                new ApprovalNotification(
                        DECISION,
                        TENANT,
                        "ticket.refund",
                        "sha256:a",
                        "退款",
                        List.of(APPROVER),
                        Instant.parse("2026-11-19T00:00:00Z"),
                        Instant.parse("2026-11-19T04:00:00Z"),
                        ApprovalStatus.REJECTED);

        cardNotifier.resolved(notification);

        verify(notifier)
                .sendText(
                        eq(TENANT.toString()),
                        eq(APPROVER.toString()),
                        org.mockito.ArgumentMatchers.contains("驳回"));
    }

    @Test
    @DisplayName("发送失败只 WARN，不抛出")
    void sendFailureIsSwallowed() {
        ImSystemNotifier notifier = mock(ImSystemNotifier.class);
        org.mockito.Mockito.when(
                        notifier.sendCard(
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("down"));
        ApprovalCardNotifier cardNotifier = new ApprovalCardNotifier(notifier);
        ApprovalNotification notification =
                new ApprovalNotification(
                        DECISION,
                        TENANT,
                        "ticket.refund",
                        "sha256:a",
                        "退款",
                        List.of(APPROVER),
                        Instant.parse("2026-11-19T00:00:00Z"),
                        Instant.parse("2026-11-19T04:00:00Z"),
                        ApprovalStatus.PENDING);

        org.assertj.core.api.Assertions.assertThatCode(() -> cardNotifier.pending(notification))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("pending：给每个审批人发卡片，data 带 decision_id")
    void pendingSendsOneCardPerApprover() {
        ImSystemNotifier notifier = mock(ImSystemNotifier.class);
        ApprovalCardNotifier cardNotifier = new ApprovalCardNotifier(notifier);
        ApprovalNotification notification =
                new ApprovalNotification(
                        DECISION,
                        TENANT,
                        "ticket.refund",
                        "sha256:a",
                        "退款",
                        List.of(APPROVER),
                        Instant.parse("2026-11-19T00:00:00Z"),
                        Instant.parse("2026-11-19T04:00:00Z"),
                        ApprovalStatus.PENDING);

        cardNotifier.pending(notification);

        ArgumentCaptor<CardMessage> captor = ArgumentCaptor.forClass(CardMessage.class);
        verify(notifier).sendCard(eq(TENANT.toString()), eq(APPROVER.toString()), captor.capture());
        CardMessage card = captor.getValue();
        assertThat(card.data()).containsEntry("decision_id", DECISION.toString());
        assertThat(card.actions())
                .extracting(CardMessage.CardAction::id)
                .containsExactly("approve", "reject");
        assertThat(card.fallbackText()).contains(DECISION.toString());
    }
}
