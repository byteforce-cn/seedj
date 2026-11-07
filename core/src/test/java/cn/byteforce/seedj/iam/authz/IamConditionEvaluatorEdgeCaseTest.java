package cn.byteforce.seedj.iam.authz;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 条件求值边界：any 的失败原因选择、空候选与标量 [*] 展开。
 */
class IamConditionEvaluatorEdgeCaseTest {

    private final IamConditionEvaluator evaluator = new IamConditionEvaluator();

    @Test
    @DisplayName("any：全部不满足时优先返回 ATTRIBUTE_MISSING")
    void anyPrefersMissingReason() {
        IamCondition condition = new IamCondition("any", List.of(
                new IamConditionTerm("department", IamConditionOperator.EQ, List.of("D001")),
                new IamConditionTerm("position", IamConditionOperator.EQ, List.of("P5"))));

        ConditionOutcome outcome = evaluator.evaluate(condition, Map.of("department", "D002"));

        assertThat(outcome.matched()).isFalse();
        assertThat(outcome.reasonCode()).isEqualTo("ATTRIBUTE_MISSING");
        assertThat(outcome.attribute()).isEqualTo("position");
    }

    @Test
    @DisplayName("any：全部不满足且都不是 missing 时返回第一个不满足项")
    void anyFallsBackToFirstOutcome() {
        IamCondition condition = new IamCondition("any", List.of(
                new IamConditionTerm("department", IamConditionOperator.EQ, List.of("D001")),
                new IamConditionTerm("position", IamConditionOperator.EQ, List.of("P5"))));

        ConditionOutcome outcome = evaluator.evaluate(condition,
                Map.of("department", "D002", "position", "M2"));

        assertThat(outcome.matched()).isFalse();
        assertThat(outcome.reasonCode()).isEqualTo("ATTRIBUTE_MISMATCH");
        assertThat(outcome.attribute()).isEqualTo("department");
    }

    @Test
    @DisplayName("路径存在但候选为空：按 mismatch 处理而非 missing")
    void presentPathWithoutCandidatesIsMismatch() {
        IamCondition condition = new IamCondition("all", List.of(
                new IamConditionTerm("positions", IamConditionOperator.IN, List.of("P6"))));

        ConditionOutcome outcome = evaluator.evaluate(condition, Map.of("positions", List.of()));

        assertThat(outcome.matched()).isFalse();
        assertThat(outcome.reasonCode()).isEqualTo("ATTRIBUTE_MISMATCH");
    }

    @Test
    @DisplayName("null 值被过滤：存在但为 null 的属性视为无候选")
    void nullValuesAreFiltered() {
        IamCondition condition = new IamCondition("all", List.of(
                new IamConditionTerm("department", IamConditionOperator.EQ, List.of("D001"))));

        Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("department", null);

        ConditionOutcome outcome = evaluator.evaluate(condition, snapshot);

        assertThat(outcome.matched()).isFalse();
        assertThat(outcome.reasonCode()).isEqualTo("ATTRIBUTE_MISMATCH");
    }

    @Test
    @DisplayName("[*] 作用于非集合值时按单值展开")
    void starUnwrapsScalarValues() {
        IamCondition condition = new IamCondition("all", List.of(
                new IamConditionTerm("positions[*].grade", IamConditionOperator.IN,
                        List.of("P6"))));

        assertThat(evaluator.evaluate(condition, Map.of("positions", Map.of("grade", "P6")))
                .matched()).isTrue();

        IamCondition scalarOnly = new IamCondition("all", List.of(
                new IamConditionTerm("positions[*]", IamConditionOperator.IN, List.of("P6"))));
        assertThat(evaluator.evaluate(scalarOnly, Map.of("positions", "P6")).matched()).isTrue();
    }

    @Test
    @DisplayName("EXISTS=false 对缺失属性仍报 missing")
    void existsFalseOnMissingPathIsMissing() {
        IamCondition condition = new IamCondition("all", List.of(
                new IamConditionTerm("department", IamConditionOperator.EXISTS,
                        List.of("false"))));

        ConditionOutcome outcome = evaluator.evaluate(condition, Map.of());

        assertThat(outcome.matched()).isFalse();
        assertThat(outcome.reasonCode()).isEqualTo("ATTRIBUTE_MISSING");
    }

    @Test
    @DisplayName("EXISTS=true 对存在但为 null 的属性不满足")
    void existsTrueOnNullValueMismatches() {
        IamCondition condition = new IamCondition("all", List.of(
                new IamConditionTerm("department", IamConditionOperator.EXISTS,
                        List.of("true"))));

        Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("department", null);

        ConditionOutcome outcome = evaluator.evaluate(condition, snapshot);

        assertThat(outcome.matched()).isFalse();
        assertThat(outcome.reasonCode()).isEqualTo("ATTRIBUTE_MISMATCH");
    }

    @Test
    @DisplayName("null 快照按空 map 求值")
    void nullSnapshotIsTreatedAsEmpty() {
        IamCondition condition = new IamCondition("all", List.of(
                new IamConditionTerm("department", IamConditionOperator.EQ, List.of("D001"))));

        ConditionOutcome outcome = evaluator.evaluate(condition, null);

        assertThat(outcome.matched()).isFalse();
        assertThat(outcome.reasonCode()).isEqualTo("ATTRIBUTE_MISSING");
    }
}
