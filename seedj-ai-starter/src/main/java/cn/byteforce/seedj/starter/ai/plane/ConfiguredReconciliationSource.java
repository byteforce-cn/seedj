package cn.byteforce.seedj.starter.ai.plane;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * T0 的默认对账来源：一个也不返回。
 *
 * <p>存在意义是把「T0 没有跨租户扫描入口」变成显式事实而不是静默空转：构造时 WARN 一次，点名对账要由 Task 13 的 夹具、运维脚本或宿主自定义 {@link
 * PlaneReconciliationSource} bean 按已知 {@code decision_id} 驱动。等账本具备 「按租户/时间扫描」的只读入口后再实现真正的发现——那是 U9
 * 之后的事，现在不要假装已经做了。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class ConfiguredReconciliationSource implements PlaneReconciliationSource {

    private static final Logger log = LoggerFactory.getLogger(ConfiguredReconciliationSource.class);

    /** 构造即 WARN 一次，避免每个对账周期刷日志。 */
    public ConfiguredReconciliationSource() {
        log.warn(
                "seedj.ai.plane.reconcile: T0 没有跨租户扫描入口，ConfiguredReconciliationSource 返回空列表；"
                        + "对账请由 Task 13 夹具或运维脚本按已知 decision_id 驱动"
                        + " ReconciliationJob#reconcile，或提供自定义 PlaneReconciliationSource bean");
    }

    /**
     * 永远返回空列表。
     *
     * @param before 截止时间（不含）
     * @return 空列表
     * @throws NullPointerException {@code before} 为 {@code null}
     */
    @Override
    public List<UUID> pending(Instant before) {
        Objects.requireNonNull(before, "before");
        return List.of();
    }
}
