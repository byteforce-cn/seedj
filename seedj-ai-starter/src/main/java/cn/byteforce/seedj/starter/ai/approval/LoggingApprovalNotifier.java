package cn.byteforce.seedj.starter.ai.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 无 IM 时的默认通知：只记一条结构化日志，审批仍走 HTTP 端点。
 *
 * <p>日志不含 token、不含载荷原文，只放 decision_id / command_type / status。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class LoggingApprovalNotifier implements ApprovalNotifier {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingApprovalNotifier.class);

    @Override
    public void pending(ApprovalNotification notification) {
        LOG.info(
                "approval pending: decision={} command={} approvers={}",
                notification.decisionId(),
                notification.commandType(),
                notification.approvers());
    }

    @Override
    public void resolved(ApprovalNotification notification) {
        LOG.info(
                "approval resolved: decision={} command={} status={}",
                notification.decisionId(),
                notification.commandType(),
                notification.status().wire());
    }
}
