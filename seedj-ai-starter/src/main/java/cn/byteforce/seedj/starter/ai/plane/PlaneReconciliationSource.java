package cn.byteforce.seedj.starter.ai.plane;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 待对账决策的来源：对账作业唯一可插拔的入口。
 *
 * <p><b>T0 的范围收窄</b>：{@link cn.byteforce.seedj.ai.spi.DecisionLedger} 的读取一律是「租户 + decision_id」
 * 作用域，没有「跨租户、按时间扫描」的只读入口，所以 T0 不实现「有 intent、无 outcome、且超过 TTL」的全量发现。
 * 生产默认实现 {@link ConfiguredReconciliationSource} 返回空列表并在启动时 WARN；需要驱动对账时由 Task 13 的夹具、
 * 运维脚本或宿主自定义 bean 提供 {@code pending}。{@link ReconciliationJob.Scheduler} 只负责按 TTL 窗口驱动这个来源，
 * 它扫不到东西不等于对账做完了。
 *
 * <p>实现方语义：返回 {@code before} 之前记录、仍有 intent 却没有 outcome 的决策。{@code before} 由作业按
 * {@code now - seedj.ai.plane.reconcile-ttl} 算好，实现方只需按 {@code intent.recorded_at < before} 过滤。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@FunctionalInterface
public interface PlaneReconciliationSource {

    /**
     * 列出待对账的决策。
     *
     * @param before 截止时间（不含）：只有早于它的意图才算「过了 TTL」
     * @return 决策标识列表；没有时返回空列表，不得返回 {@code null}
     */
    List<UUID> pending(Instant before);
}
