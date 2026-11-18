package cn.byteforce.seedj.starter.ai.plane;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 待对账决策的来源：对账作业唯一可插拔的入口。
 *
 * <p><b>列的是什么</b>：已经上报过 {@code outcome}、且那条 outcome 是 {@code status=ok} 但 {@code audit_entry_id}
 * 仍为 {@code null} 的决策——也就是「自称成功、还没链校验」的那些。列的集合必须与 {@link ReconciliationJob#reconcile}
 * 真正会处理的集合一致，否则宿主按文档实现出来的东西会被整套丢弃。
 *
 * <p><b>不在本接口范围</b>：「有 intent、无 outcome」的扫描 T0 <b>不做</b>（见 {@link ReconciliationJob} 类注释）：
 * {@link cn.byteforce.seedj.ai.spi.DecisionLedger} 只有「租户 + decision_id」作用域的读接口，没有跨租户按时间扫描的
 * 入口；而且没有 outcome 就没有可核对的链事实，对账对它只会 no-op。这类「有决策无效果」要靠账本具备按租户/时间扫描
 * 的只读入口之后再做（U9 之后）。
 *
 * <p><b>T0 的范围收窄（有意为之）</b>：生产默认实现 {@link ConfiguredReconciliationSource} 返回空列表并在启动时 WARN；
 * 需要驱动对账时由 Task 13 的夹具、运维脚本或宿主自定义 bean 提供 {@code pending}。
 * {@link ReconciliationJob.Scheduler} 只负责按 TTL 窗口驱动这个来源，它扫不到东西不等于对账做完了。
 *
 * <p>实现方语义：返回 {@code before} 之前记录、符合上述「{@code ok} 但未链校验」条件、且还没有带
 * {@code audit_entry_id} 的 {@code reconciled} 记录的决策。{@code before} 由作业按
 * {@code now - seedj.ai.plane.reconcile-ttl} 算好，实现方只需按 {@code outcome.recorded_at < before} 过滤。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@FunctionalInterface
public interface PlaneReconciliationSource {

    /**
     * 列出待补链校验的决策。
     *
     * <p>只列「已有 outcome 且 {@code status=ok}、但 {@code audit_entry_id} 仍为 null」的决策；「有 intent 无
     * outcome」的扫描在 T0 <b>不在本接口范围</b>（见 {@link ReconciliationJob} 类注释），列进来也只会被丢弃。
     *
     * @param before 截止时间（不含）：只有早于它的 outcome 才算「过了 TTL」
     * @return 决策标识列表；没有时返回空列表，不得返回 {@code null}
     */
    List<UUID> pending(Instant before);
}
