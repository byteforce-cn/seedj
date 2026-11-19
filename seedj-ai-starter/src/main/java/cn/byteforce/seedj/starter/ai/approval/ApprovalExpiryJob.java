package cn.byteforce.seedj.starter.ai.approval;

import cn.byteforce.seedj.core.clock.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 审批过期扫描：固定延迟调 {@link ApprovalService#expirePending(java.time.Instant)}。
 *
 * <p>宿主必须自己写 {@code @EnableScheduling}；没有它时本 bean 只是一直躺着，不影响按读/按 grant 的惰性过期。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class ApprovalExpiryJob {

    private static final Logger LOG = LoggerFactory.getLogger(ApprovalExpiryJob.class);

    private final ApprovalService approvals;

    private final Clock clock;

    /**
     * 绑定审批服务与时钟。
     *
     * @param approvals 审批服务
     * @param clock 取时入口
     */
    public ApprovalExpiryJob(ApprovalService approvals, Clock clock) {
        this.approvals = approvals;
        this.clock = clock;
    }

    /** 每 60 秒扫描一次。 */
    @Scheduled(fixedDelay = 60_000L)
    public void run() {
        try {
            approvals.expirePending(clock.now());
        } catch (RuntimeException error) {
            LOG.warn("审批过期扫描失败，下一轮重试", error);
        }
    }
}
