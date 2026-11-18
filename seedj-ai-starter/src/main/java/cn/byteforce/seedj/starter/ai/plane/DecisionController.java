package cn.byteforce.seedj.starter.ai.plane;

import cn.byteforce.seedj.ai.DecisionRecord;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 决策面的 HTTP 面：求值一条决策、读一个决策的账本记录、上报一次执行结局。
 *
 * <p>控制器只做绑定与状态码，判定、落账与派生全在 {@link DecisionService} 与 {@link OutcomeDeriver}：请求体与响应体的
 * 线格式由服务侧的值类型定义（两侧都是 snake_case，不存在第二套映射）。三者合成一个控制器，是因为它们属于同一个
 * {@code /v1/decisions/{id}} 资源族：决策、它的记录、它的结局。
 *
 * <p><b>状态码</b>：求值成功是 200（账本写入失败、签发失败会让请求 500——此时 intent 已落账，这是刻意的不对称：
 * 没有意图的执行绝不允许，但有意图无效果可以对账）；坏请求是 400，由 {@code ContractException} 交给宿主的
 * {@code ContractErrorHandler}；读不到记录是 404（租户不匹配同样 404，不泄露存在性）；没有装配对账数据源时上报是 503，
 * 决策本身仍然可用。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@RestController
public final class DecisionController {

    /** 求值入口。 */
    static final String DECISIONS_PATH = "/v1/decisions";

    /** 按决策标识读账本记录。 */
    static final String DECISION_PATH = "/v1/decisions/{id}";

    /** 按决策标识上报执行结局。 */
    static final String DECISION_OUTCOME_PATH = "/v1/decisions/{id}/outcome";

    private final DecisionService decisions;

    private final OutcomeDeriver outcomes;

    /**
     * 构造控制器。
     *
     * @param decisions 决策服务
     * @param outcomes outcome 派生器；{@code null} 表示没有对账数据源，上报端点返回 503
     */
    public DecisionController(DecisionService decisions, OutcomeDeriver outcomes) {
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.outcomes = outcomes;
    }

    /**
     * 求值一条决策。
     *
     * @param request 请求体
     * @return 决策结果：deny 只带原因码，allow 的执行工具步骤才带 token
     */
    @PostMapping(DECISIONS_PATH)
    public DecisionService.DecisionResult decide(
            @RequestBody DecisionService.DecisionRequest request) {
        return decisions.decide(request);
    }

    /**
     * 读取一个决策的账本记录。
     *
     * @param id 决策标识
     * @return {@code {decision_id, records:[{record_id, kind, sequence, recorded_at}]}}
     * @throws ContractException {@code object_not_found}：本租户下没有该决策
     */
    @GetMapping(DECISION_PATH)
    public DecisionRecords records(@PathVariable UUID id) {
        List<DecisionRecord> records = decisions.records(id);
        if (records.isEmpty()) {
            throw new ContractException(
                    ContractErrorCode.OBJECT_NOT_FOUND, "决策不存在: " + id);
        }
        return new DecisionRecords(
                id,
                records.stream()
                        .map(
                                record ->
                                        new RecordView(
                                                record.recordId(),
                                                record.kind().wire(),
                                                record.sequence(),
                                                record.recordedAt()))
                        .toList());
    }

    /**
     * 上报一次执行结局。
     *
     * <p>请求体只有 {@code command_id}：status / audit_entry_id / error_code 一律不接受，它们只能来自链上事实
     * （见 {@link OutcomeDeriver}）。
     *
     * @param id 决策标识
     * @param request 请求体
     * @return {@code {decision_id, outcome_record_id, status}}，{@code status} 是账本里的结局
     * @throws ContractException {@code invalid_payload}：缺 {@code command_id}；
     *     {@code object_not_found}：决策不存在；{@code unavailable}：没有对账数据源
     */
    @PostMapping(DECISION_OUTCOME_PATH)
    public OutcomeReport report(@PathVariable UUID id, @RequestBody OutcomeRequest request) {
        UUID commandId = request == null ? null : request.commandId();
        if (commandId == null) {
            throw new ContractException(
                    ContractErrorCode.INVALID_PAYLOAD, "缺少必填字段 command_id");
        }
        if (outcomes == null) {
            throw new ContractException(
                    ContractErrorCode.UNAVAILABLE,
                    "未装配对账数据源（seedj.ai.plane.reconcile.datasource）：无法上报 outcome");
        }
        OutcomeDeriver.Reported reported = outcomes.report(id, commandId);
        return new OutcomeReport(
                reported.decisionId(), reported.outcomeRecordId(), reported.status().wire());
    }

    /**
     * 账本记录响应体（线格式 snake_case）。
     *
     * @param decisionId 决策标识
     * @param records 记录列表，按 {@code sequence} 升序
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DecisionRecords(UUID decisionId, List<RecordView> records) {

        public DecisionRecords {
            records = records == null ? List.of() : List.copyOf(records);
        }
    }

    /**
     * 一条账本记录的摘要：只给排障与举证需要的四项，不回传记录体。
     *
     * @param recordId 记录标识
     * @param kind 记录类型线格式名
     * @param sequence 分区内序号
     * @param recordedAt 落账时间
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RecordView(UUID recordId, String kind, long sequence, Instant recordedAt) {}

    /**
     * 结局上报请求体（线格式 snake_case）。
     *
     * <p>只收 {@code command_id}：结局状态、链条目标识、错误码都只能来自链上事实，出现在请求里会被忽略。
     *
     * @param commandId runtime 认为的命令标识（只用于表达，不参与判定）
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record OutcomeRequest(UUID commandId) {}

    /**
     * 结局上报响应体（线格式 snake_case）。
     *
     * @param decisionId 决策标识
     * @param outcomeRecordId 本次（或首次）outcome 的记录标识
     * @param status 结局状态的线格式名（来自账本，不是请求）
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record OutcomeReport(UUID decisionId, UUID outcomeRecordId, String status) {}
}
