package cn.byteforce.seedj.starter.ai.plane;

import cn.byteforce.seedj.ai.DecisionEffect;
import cn.byteforce.seedj.ai.DecisionRecord;
import cn.byteforce.seedj.ai.DecisionRecordKind;
import cn.byteforce.seedj.ai.EffectStatus;
import cn.byteforce.seedj.ai.OutcomeSource;
import cn.byteforce.seedj.ai.spi.DecisionLedger;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.starter.ai.AiProperties;
import cn.byteforce.seedj.starter.ai.ledger.RecordedEffect;
import cn.byteforce.seedj.starter.ai.ledger.RecordedIntent;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * 把一次 runtime 上报变成一条 {@code outcome} 记录（spec §6.4 的两段式效果：intent 先行、outcome 后补）。
 *
 * <p><b>上报不是结论</b>：请求只带 {@code command_id}，没有 status / audit_entry_id / error_code——runtime 说
 * 什么都不算数。结局全部由链上事实推导：按 {@code (issuer = intent.identity.actor, idempotency_key = decision_id)} 查
 * {@code command_record}（「幂等键 = decision_id」是 guard 保证的），命令记录才是唯一事实来源。 上报的那个 {@code command_id}
 * 只表达「runtime 认为这是哪条命令」，不参与判定：库里是 ACCEPTED 就按<b>库里</b>那条 记录的 id 落账——说谎的上报因此被链上事实覆盖，而不是被采信。
 *
 * <p><b>上报阶段永远不写 {@code audit_entry_id}</b>：链条目由命令事务写出，上报本身不是链校验。所以这里写出的 outcome 是 {@code source =
 * runtime_reported} 的「已上报、未链校验」，补齐链条目标是 {@link ReconciliationJob} 的事。一条自称 OK 却没有链条目的 outcome
 * 会在对账里被降为 {@code unknown}，不是成功。
 *
 * <p><b>判定顺序</b>（每一步只看库里的那一行）：
 *
 * <ol>
 *   <li>按幂等键查不到命令记录 → {@code failed(command_not_found)}——决策发出去过，但命令从未落库；
 *   <li>{@code payload_fingerprint} 与 intent 的幂等指纹不一致 → {@code failed(fingerprint_mismatch)}：那条命令
 *       不是这次决策，它的结果不能算到这条意图上；
 *   <li>{@code accepted} → {@code ok(null, 库里的 command_id)}；{@code rejected} → {@code
 *       failed(rejection_code)}；{@code pending}（含租约未过期）→ {@code unknown()}。
 * </ol>
 *
 * <p><b>幂等</b>：同一 decision 上报两次只留一条 outcome（账本对 outcome 幂等），第二次返回首条记录的 id 与状态， 不会把已落账的结局改写一遍。
 *
 * <p><b>数据源</b>：只读业务库，用来查 {@code command_record} 与（对账时）{@code audit_entry}；本类不写业务库。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class OutcomeDeriver {

    /** 链上按幂等键找不到命令记录：命令从未落库。 */
    public static final String ERROR_COMMAND_NOT_FOUND = "command_not_found";

    /** 命令记录的幂等指纹与本条 intent 不一致：那条命令不是这次决策。 */
    public static final String ERROR_FINGERPRINT_MISMATCH = "fingerprint_mismatch";

    private final ReconcileQueries queries;

    private final DecisionLedger ledger;

    private final UUID tenantId;

    /**
     * 绑定对账数据源与账本。
     *
     * @param dataSource 业务库数据源（只读）
     * @param ledger 决策账本
     * @param properties AI 配置：{@code seedj.ai.plane.tenant}
     * @throws NullPointerException 任一参数为 {@code null}
     * @throws IllegalStateException {@code seedj.ai.plane.tenant} 缺失
     */
    public OutcomeDeriver(DataSource dataSource, DecisionLedger ledger, AiProperties properties) {
        this.queries = new ReconcileQueries(dataSource);
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.tenantId = PlaneTenant.of(Objects.requireNonNull(properties, "properties"));
    }

    /**
     * 上报一次执行结局：推导并追加 {@code outcome}，已存在时返回首条。
     *
     * @param decisionId 决策标识
     * @param reportedCommandId runtime 上报的命令标识；只表达它认为的命令，<b>不参与判定</b>
     * @return 上报结果（记录 id 与账本里的结局状态）
     * @throws NullPointerException 任一参数为 {@code null}
     * @throws ContractException {@code object_not_found}：本租户下没有该决策的 intent
     */
    public Reported report(UUID decisionId, UUID reportedCommandId) {
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(reportedCommandId, "reportedCommandId");
        DecisionEffect effect = derive(decisionId, RecordedIntent.of(intentOf(decisionId)));
        DecisionRecord outcome =
                ledger.appendOutcome(decisionId, effect, OutcomeSource.RUNTIME_REPORTED);
        return new Reported(decisionId, outcome.recordId(), RecordedEffect.of(outcome).status());
    }

    private DecisionRecord intentOf(UUID decisionId) {
        return ledger.byDecision(tenantId, decisionId).stream()
                .filter(record -> record.kind() == DecisionRecordKind.INTENT)
                .findFirst()
                .orElseThrow(
                        () ->
                                new ContractException(
                                        ContractErrorCode.OBJECT_NOT_FOUND,
                                        "决策不存在: " + decisionId));
    }

    private DecisionEffect derive(UUID decisionId, RecordedIntent intent) {
        Optional<ReconcileQueries.CommandRow> found =
                queries.commandByIdempotency(intent.actor(), decisionId.toString());
        if (found.isEmpty()) {
            return DecisionEffect.failed(ERROR_COMMAND_NOT_FOUND);
        }
        ReconcileQueries.CommandRow command = found.get();
        if (!command.payloadFingerprint().equals(intent.idemFingerprint())) {
            return DecisionEffect.failed(ERROR_FINGERPRINT_MISMATCH);
        }
        return switch (command.status()) {
                // 上报不是链校验：audit_entry_id 恒为 null，命令标识取库里那条
            case "accepted" -> DecisionEffect.ok(null, command.id());
            case "rejected" -> DecisionEffect.failed(command.rejectionCode());
                // pending（含租约未过期）：执行还没定论，不得当成成功
            default -> DecisionEffect.unknown();
        };
    }

    /**
     * 上报结果。
     *
     * @param decisionId 决策标识
     * @param outcomeRecordId 本次（或首次）outcome 的记录标识
     * @param status 账本里的结局状态（不是请求里的）
     * @author ByteForce
     * @since 1.0.0
     */
    public record Reported(UUID decisionId, UUID outcomeRecordId, EffectStatus status) {

        /**
         * 校验三项必填。
         *
         * @throws NullPointerException 任一组件为 {@code null}
         */
        public Reported {
            Objects.requireNonNull(decisionId, "decisionId");
            Objects.requireNonNull(outcomeRecordId, "outcomeRecordId");
            Objects.requireNonNull(status, "status");
        }
    }
}
