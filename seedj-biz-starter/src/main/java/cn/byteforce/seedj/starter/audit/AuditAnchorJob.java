package cn.byteforce.seedj.starter.audit;

import cn.byteforce.seedj.audit.AuditChain;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 可选定时锚定：把当前链头导出到 {@link cn.byteforce.seedj.audit.AuditAnchor}。
 *
 * <p>默认关闭（{@code seedj.audit.anchor.enabled=false}）。失败时记录 ERROR 并 向上抛出，不做静默吞掉；调度线程会继续下一轮。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class AuditAnchorJob {

    private static final Logger log = LoggerFactory.getLogger(AuditAnchorJob.class);

    private final AuditChain<String> chain;

    /**
     * 构造锚定任务。
     *
     * @param chain 审计链
     */
    public AuditAnchorJob(AuditChain<String> chain) {
        this.chain = Objects.requireNonNull(chain, "chain");
    }

    /** 锚定当前链头。 */
    @Scheduled(
            fixedDelayString = "${seedj.audit.anchor.interval-millis:3600000}",
            initialDelayString = "${seedj.audit.anchor.initial-delay-millis:0}")
    public void anchor() {
        try {
            chain.anchorNow();
        } catch (RuntimeException error) {
            log.error("audit anchor job failed", error);
            throw error;
        }
    }
}
