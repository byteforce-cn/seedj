package cn.byteforce.seedj.starter.ai.plane;

import cn.byteforce.seedj.ai.DecisionEffect;
import cn.byteforce.seedj.ai.DecisionRecord;
import cn.byteforce.seedj.ai.DecisionRecordKind;
import cn.byteforce.seedj.ai.EffectStatus;
import cn.byteforce.seedj.ai.spi.DecisionLedger;
import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.starter.ai.AiProperties;
import cn.byteforce.seedj.starter.ai.ledger.RecordedEffect;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 事后核对：把「已上报的 ok」与「链上真实条目」对上，补一条 {@code reconciled}（spec §6.4 第 5 步）。
 *
 * <p>为什么要这一步：上报阶段不写 {@code audit_entry_id}（那会假装上报就是链校验），所以一条 outcome 是不是真的
 * 在链上留下了副作用，只能另外查。核对规则只有两条，都按账本里已写的 outcome 判断：
 *
 * <ul>
 *   <li>outcome 是 {@code ok} 且 {@code audit_entry_id} 为空 → 按 {@code command_id} 查链条目：查到就追加 {@code
 *       reconciled(ok(audit_entry_id, command_id), "chain_verified")}；查不到就追加 {@code
 *       reconciled(unknown(), "audit_entry_missing")}——自称成功却没有链条目是数据不一致，降为未知，「不得把 unknown 当成成功」。
 *   <li>其它情况（已链校验过、非 ok、没有 outcome）不动。
 *   <li><b>按 reason 去重</b>：账本的 {@code reconciled} <b>不幂等</b>（每次核对一条观察），而 outcome 落账后永远还是 {@code ok
 *       + audit_entry_id=null}；不去重的话一个 60 秒一次的调度会为同一个 decision 无限追加同样的记录。 规则是<b>同一 reason 只记一次</b>：
 *       <code>audit_entry_missing</code> 已经记过且本次仍查不到链条目 → 不追加； 已经有一条带 {@code audit_entry_id} 的
 *       {@code chain_verified} → 不追加。 但「缺失 → 核对成功」是一次<b>状态跃迁</b>，允许再记一条 {@code
 *       chain_verified}（即使之前已记过 {@code audit_entry_missing}）——链条目补写后必须能升级。因此每个 decision 最多留下 {@code
 *       1 × audit_entry_missing + 1 × chain_verified}，既有上界又能升级。旧 outcome、旧记录都不改写（账本没有 更新入口）。
 * </ul>
 *
 * <p><b>T0 的范围收窄（有意为之）</b>：{@link DecisionLedger} 只有「租户 + decision_id」作用域的读接口，没有跨租户 按时间扫描的入口，所以 T0
 * <b>不实现</b>「有 intent、无 outcome、且超过 TTL」的全量发现——这类决策本类也只会 no-op（没有 outcome 就没有可核对的链事实），所以它不在 {@link
 * PlaneReconciliationSource} 的契约里。待核对清单来自 {@link PlaneReconciliationSource}，它列的是「已有 outcome、{@code
 * status=ok}、但 {@code audit_entry_id} 仍为 null」的决策；生产默认实现 {@link ConfiguredReconciliationSource}
 * 返回空列表并在启动时 WARN，真正的驱动来自 Task 13 的夹具、运维脚本或宿主自定义 bean。定时包装即使打开也扫不到东西——这不是全量对账，别把它当全量对账。
 *
 * <p><b>定时</b>：只有 {@code seedj.ai.plane.reconcile.enabled=true} 时才装配 {@link Scheduler}，且宿主必须自己写
 * {@code @EnableScheduling}（starter 不改宿主的调度配置）。测试与运维直接调 {@link #reconcileOnce(Instant)}，不依赖 调度。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class ReconciliationJob {

    /** 核对依据：链上找到了该命令的审计条目。 */
    public static final String REASON_CHAIN_VERIFIED = "chain_verified";

    /** 核对依据：outcome 自称 ok，但链上找不到该命令的条目——结局降为 unknown。 */
    public static final String REASON_AUDIT_ENTRY_MISSING = "audit_entry_missing";

    /** 定时对账的固定延迟：T0 只有一把调度，间隔不做配置项。 */
    private static final long SCHEDULE_DELAY_MS = 60_000L;

    private final ReconcileQueries queries;

    private final DecisionLedger ledger;

    private final PlaneReconciliationSource source;

    private final UUID tenantId;

    private final Duration ttl;

    /**
     * 绑定业务库、账本与待核对清单来源。
     *
     * @param dataSource 业务库数据源（只读）
     * @param ledger 决策账本
     * @param source 待核对决策的来源
     * @param properties AI 配置：{@code seedj.ai.plane.tenant} 与 {@code seedj.ai.plane.reconcile-ttl}
     * @throws NullPointerException 任一参数为 {@code null}
     * @throws IllegalStateException {@code seedj.ai.plane.tenant} 缺失
     */
    public ReconciliationJob(
            DataSource dataSource,
            DecisionLedger ledger,
            PlaneReconciliationSource source,
            AiProperties properties) {
        this.queries = new ReconcileQueries(dataSource);
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.source = Objects.requireNonNull(source, "source");
        AiProperties config = Objects.requireNonNull(properties, "properties");
        this.tenantId = PlaneTenant.of(config);
        this.ttl = Objects.requireNonNull(config.plane().reconcileTtl(), "reconcileTtl");
    }

    /**
     * 按已知决策核对一次：需要时追加一条 {@code reconciled}。
     *
     * <p>去重规则（详见类注释）：同一 reason 只记一次；{@code audit_entry_missing} → {@code chain_verified} 是状态
     * 跃迁，允许再记一条。
     *
     * @param decisionId 决策标识
     * @return 追加的 {@code reconciled} 记录；不需要核对（没有 outcome、非 ok、已链校验过、同一 reason 已记过） 时为空
     * @throws NullPointerException {@code decisionId} 为 {@code null}
     */
    public Optional<DecisionRecord> reconcile(UUID decisionId) {
        Objects.requireNonNull(decisionId, "decisionId");
        List<DecisionRecord> records = ledger.byDecision(tenantId, decisionId);
        Optional<DecisionRecord> outcome =
                records.stream()
                        .filter(record -> record.kind() == DecisionRecordKind.OUTCOME)
                        .findFirst();
        if (outcome.isEmpty()) {
            return Optional.empty();
        }
        RecordedEffect effect = RecordedEffect.of(outcome.get());
        if (effect.status() != EffectStatus.OK || effect.auditEntryId() != null) {
            return Optional.empty();
        }
        Optional<String> auditEntryId =
                effect.commandId() == null
                        ? Optional.empty()
                        : queries.auditEntryIdOf(effect.commandId());
        if (auditEntryId.isPresent()) {
            if (chainVerified(records)) {
                return Optional.empty();
            }
            return Optional.of(
                    ledger.appendReconciled(
                            decisionId,
                            DecisionEffect.ok(auditEntryId.get(), effect.commandId()),
                            REASON_CHAIN_VERIFIED));
        }
        if (reconciledWithReason(records, REASON_AUDIT_ENTRY_MISSING)) {
            return Optional.empty();
        }
        return Optional.of(
                ledger.appendReconciled(
                        decisionId, DecisionEffect.unknown(), REASON_AUDIT_ENTRY_MISSING));
    }

    /**
     * 已经补过链校验记录：存在一条带 {@code audit_entry_id} 的 {@code reconciled}。
     *
     * <p>用 {@code audit_entry_id} 而不是 reason 字符串判定：这是「链上确实有那条条目」的硬事实，没有它就不算 校验过。
     */
    private static boolean chainVerified(List<DecisionRecord> records) {
        return records.stream()
                .filter(record -> record.kind() == DecisionRecordKind.RECONCILED)
                .anyMatch(record -> RecordedEffect.of(record).auditEntryId() != null);
    }

    /** 账本里已记过同一个 reason 的核对结论（{@code audit_entry_missing} 的幂等依据）。 */
    private static boolean reconciledWithReason(List<DecisionRecord> records, String reason) {
        return records.stream()
                .filter(record -> record.kind() == DecisionRecordKind.RECONCILED)
                .anyMatch(record -> reason.equals(record.body().get("reason")));
    }

    /**
     * 对 {@code now - reconcile-ttl} 之前仍在等待的决策逐个核对一次。
     *
     * @param now 本次运行的时点
     * @return 本次处理（源列出并尝试核对）的决策条数；TTL 窗口内的决策不在其内
     * @throws NullPointerException {@code now} 为 {@code null}
     */
    public int reconcileOnce(Instant now) {
        Objects.requireNonNull(now, "now");
        List<UUID> pending = source.pending(now.minus(ttl));
        for (UUID decisionId : pending) {
            reconcile(decisionId);
        }
        return pending.size();
    }

    /**
     * 定时入口：只做「按固定延迟驱动一次对账」，时间取自宿主的 {@link Clock}。
     *
     * <p>只有 {@code seedj.ai.plane.reconcile.enabled=true} 时才会被装配成 bean；宿主需要自己写
     * {@code @EnableScheduling}，否则这个 bean 只是一直躺着。
     *
     * @author ByteForce
     * @since 1.0.0
     */
    public static final class Scheduler {

        private final ReconciliationJob job;

        private final Clock clock;

        /**
         * 绑定对账作业与取时入口。
         *
         * @param job 对账作业
         * @param clock 取时入口
         * @throws NullPointerException 任一参数为 {@code null}
         */
        public Scheduler(ReconciliationJob job, Clock clock) {
            this.job = Objects.requireNonNull(job, "job");
            this.clock = Objects.requireNonNull(clock, "clock");
        }

        /** 按固定延迟（60 秒）驱动一次对账。 */
        @Scheduled(fixedDelay = SCHEDULE_DELAY_MS)
        public void run() {
            job.reconcileOnce(clock.now());
        }
    }
}
