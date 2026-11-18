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
 * 决策面的 HTTP 面：求值一条决策、读一个决策的账本记录。
 *
 * <p>控制器只做绑定与状态码，判定与落账全在 {@link DecisionService}：请求体与响应体的线格式由服务侧的值类型定义
 * （两侧都是 snake_case，不存在第二套映射）。
 *
 * <p><b>状态码</b>：求值成功是 200（账本写入失败、签发失败会让请求 500——此时 intent 已落账，这是刻意的不对称：
 * 没有意图的执行绝不允许，但有意图无效果可以对账）；坏请求是 400，由 {@code ContractException} 交给宿主的
 * {@code ContractErrorHandler}；读不到记录是 404（租户不匹配同样 404，不泄露存在性）。
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

    private final DecisionService decisions;

    /**
     * 构造控制器。
     *
     * @param decisions 决策服务
     */
    public DecisionController(DecisionService decisions) {
        this.decisions = Objects.requireNonNull(decisions, "decisions");
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
}
