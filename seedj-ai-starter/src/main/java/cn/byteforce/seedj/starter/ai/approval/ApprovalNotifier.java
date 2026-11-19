package cn.byteforce.seedj.starter.ai.approval;

/**
 * 审批通知端口：默认只记日志；有 IM 时由卡片适配器覆盖。
 *
 * <p>通知失败绝不能让审批失败：审批事实已经落账，通知只是触达。实现必须自己吞掉传输异常或由调用方兜底。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface ApprovalNotifier {

    /**
     * 审批已发起，等待处理。
     *
     * @param notification 通知
     */
    void pending(ApprovalNotification notification);

    /**
     * 审批已终结（批准/驳回/过期）。
     *
     * @param notification 通知
     */
    void resolved(ApprovalNotification notification);
}
