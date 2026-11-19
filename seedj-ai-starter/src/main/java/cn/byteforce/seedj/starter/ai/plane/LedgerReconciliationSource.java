package cn.byteforce.seedj.starter.ai.plane;

import cn.byteforce.seedj.ai.spi.DecisionLedger;
import cn.byteforce.seedj.starter.ai.AiProperties;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 默认对账来源：从账本扫描「outcome=ok 但 audit_entry_id 为空」的决策。
 *
 * <p>这是 T0 缺失的全量发现入口。账本只读、按租户与时间过滤，不碰业务库；业务事实仍由 {@link ReconcileQueries} 在 {@link
 * ReconciliationJob#reconcile} 里按 {@code decision_id} / {@code command_id} 读取。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class LedgerReconciliationSource implements PlaneReconciliationSource {

    private static final int LIMIT = 1000;

    private final DecisionLedger ledger;

    private final UUID tenantId;

    /**
     * 绑定账本与租户。
     *
     * @param ledger 决策账本
     * @param properties AI 配置：{@code seedj.ai.plane.tenant}
     */
    public LedgerReconciliationSource(DecisionLedger ledger, AiProperties properties) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.tenantId = PlaneTenant.of(Objects.requireNonNull(properties, "properties"));
    }

    @Override
    public List<UUID> pending(Instant before) {
        Objects.requireNonNull(before, "before");
        return ledger.pendingAudit(tenantId, before, LIMIT);
    }
}
