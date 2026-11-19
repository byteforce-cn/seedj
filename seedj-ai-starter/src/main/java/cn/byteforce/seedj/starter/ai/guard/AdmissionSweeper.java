package cn.byteforce.seedj.starter.ai.guard;

import cn.byteforce.seedj.ai.spi.DecisionAdmissionStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 过期准入清理：删除 {@code expiresAt < now - 1h} 的行。
 *
 * <p>admission 不是业务事实，过期后不再需要；宿主必须自己写 {@code @EnableScheduling} 才会运行。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class AdmissionSweeper {

    private static final Logger LOG = LoggerFactory.getLogger(AdmissionSweeper.class);

    private final DecisionAdmissionStore admissions;

    private final Duration grace;

    /**
     * 绑定存储与保留宽限。
     *
     * @param admissions 准入存储
     * @param grace 过期后的保留时间
     */
    public AdmissionSweeper(DecisionAdmissionStore admissions, Duration grace) {
        this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.grace = Objects.requireNonNull(grace, "grace");
    }

    /** 每个配置周期清理一次。 */
    @Scheduled(fixedDelay = 60_000L)
    public void run() {
        try {
            Instant before = Instant.now().minus(grace);
            admissions.deleteExpired(before);
        } catch (RuntimeException error) {
            LOG.warn("admission 过期清理失败，下一轮重试", error);
        }
    }
}
