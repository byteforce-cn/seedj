package cn.byteforce.seedj.ai.spi;

import cn.byteforce.seedj.ai.ApprovalSnapshot;
import cn.byteforce.seedj.ai.DecisionEffect;
import cn.byteforce.seedj.ai.DecisionIntent;
import cn.byteforce.seedj.ai.DecisionRecord;
import cn.byteforce.seedj.ai.LedgerVerification;
import cn.byteforce.seedj.ai.OutcomeSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 决策账本的存储端口：只追加、按分区串成哈希链。
 *
 * <p>账本是 AI 命令网关的事实来源：先写意图才可能签发 token，执行后再写结局。因此本端口只提供追加与读取， 没有修改或删除入口——记录一旦写错，只能再追加一条核对记录。
 *
 * <p>实现侧约定（内存与 PostgreSQL 两套实现共用，由 {@code DecisionLedgerContractTest} 校验）：
 *
 * <ul>
 *   <li>{@link #appendApproval} / {@link #appendOutcome} / {@link #appendReconciled} 的租户由账本按
 *       {@code decisionId} 反查既有意图得出；意图不存在时抛 {@link IllegalArgumentException}。
 *   <li>同一 {@code decisionId} 的意图重复追加是幂等的：返回首次记录，内容不同也不抛（内容比对由调用方负责）。
 *   <li>同一 {@code decisionId} 的结局重复追加是幂等的：返回首次记录。
 *   <li>{@code approval} 与 {@code reconciled} <b>不幂等</b>：每个 decision 可多条 approval（每次观察一条审批事实），
 *       reconciled 每次核对一条——它们是观察记录，不是效果副本。
 *   <li>{@link #byDecision} 按 {@code sequence} 升序返回；租户不匹配时返回空列表，不泄露记录是否存在。
 *   <li>{@link #find} 同样是租户作用域。
 * </ul>
 *
 * <p>本接口不负责签名或时间戳：{@code recordHash} 由实现按分区链计算，{@code recordedAt} 由实现写入。
 *
 * <p><b>记录体格式</b>：由 {@link cn.byteforce.seedj.ai.DecisionRecordBodies} 唯一定义（两套实现都调它）， null
 * 字段一律省略；{@code record_hash} 覆盖整条记录，两套实现写出的记录体不同就得不到同一个哈希。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface DecisionLedger {

    /**
     * 追加决策意图；同一 {@code decisionId} 重复追加返回首次记录。
     *
     * @param intent 决策意图
     * @return 落账后的记录
     * @throws NullPointerException {@code intent} 为 {@code null}
     */
    DecisionRecord appendIntent(DecisionIntent intent);

    /**
     * 追加人工审批快照；同一 decision 可追加多条（每次观察一条审批事实）。
     *
     * @param decisionId 决策标识
     * @param snapshot 审批快照
     * @return 落账后的记录
     * @throws IllegalArgumentException 该决策没有意图记录
     */
    DecisionRecord appendApproval(UUID decisionId, ApprovalSnapshot snapshot);

    /**
     * 追加上报的执行结局；同一 {@code decisionId} 重复追加返回首次记录。
     *
     * @param decisionId 决策标识
     * @param effect 执行结局
     * @param source 结局来源
     * @return 落账后的记录
     * @throws IllegalArgumentException 该决策没有意图记录
     */
    DecisionRecord appendOutcome(UUID decisionId, DecisionEffect effect, OutcomeSource source);

    /**
     * 追加事后核对得出的结局；每次核对一条记录。
     *
     * @param decisionId 决策标识
     * @param effect 核对后的结局
     * @param reason 核对依据说明
     * @return 落账后的记录
     * @throws IllegalArgumentException 该决策没有意图记录
     */
    DecisionRecord appendReconciled(UUID decisionId, DecisionEffect effect, String reason);

    /**
     * 按记录标识读取一条记录（租户作用域）。
     *
     * @param tenantId 租户
     * @param recordId 记录标识
     * @return 记录；不存在或不属于该租户时为空
     */
    Optional<DecisionRecord> find(UUID tenantId, UUID recordId);

    /**
     * 读取一个决策下的全部记录，按 {@code sequence} 升序。
     *
     * @param tenantId 租户
     * @param decisionId 决策标识
     * @return 记录列表；不存在或不属于该租户时为空列表
     */
    List<DecisionRecord> byDecision(UUID tenantId, UUID decisionId);

    /**
     * 校对分区内的哈希链：逐条重算 {@code recordHash}、校验 {@code prevHash} 接上前一条，最后与链头
     * （{@code last_sequence} / {@code last_hash}）比对。
     *
     * <p>链头比对是必需的：行级触发器挡不住 {@code TRUNCATE}，只走存活行无法发现「整条尾部被截掉」。
     *
     * @param partition 分区名（{@link DecisionRecord#partitionOf(UUID)}）
     * @return 校对结果
     */
    LedgerVerification verify(String partition);
}
