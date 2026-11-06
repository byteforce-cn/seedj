package cn.byteforce.seedj.iam.authz;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 声明式授权条件：all/any、[*]、全称/存在语义与 fail-closed。
 */
class IamConditionEvaluatorTest {

    private static final Map<String, Object> SNAPSHOT = Map.of(
            "employment_type", "full_time",
            "primary_position", Map.of("grade", "M2"),
            "positions", List.of(
                    Map.of("grade", "M2"),
                    Map.of("grade", "P6")));

    private final IamConditionEvaluator evaluator = new IamConditionEvaluator();

    @Test
    @DisplayName("all + primary_position.grade in P5：M2 不满足")
    void allInRejectsMismatchedPrimaryGrade() {
        IamCondition condition = new IamCondition("all", List.of(
                new IamConditionTerm("primary_position.grade", IamConditionOperator.IN,
                        List.of("P5"))));

        ConditionOutcome outcome = evaluator.evaluate(condition, SNAPSHOT);

        assertThat(outcome.matched()).isFalse();
        assertThat(outcome.reasonCode()).isEqualTo("ATTRIBUTE_MISMATCH");
        assertThat(outcome.attribute()).isEqualTo("primary_position.grade");
    }

    @Test
    @DisplayName("any + positions[*].grade in P6：任一岗位命中")
    void anyPositionsStarMatchesAnyGrade() {
        IamCondition condition = new IamCondition("any", List.of(
                new IamConditionTerm("positions[*].grade", IamConditionOperator.IN,
                        List.of("P6")),
                new IamConditionTerm("employment_type", IamConditionOperator.EQ,
                        List.of("contractor"))));

        assertThat(evaluator.evaluate(condition, SNAPSHOT).matched()).isTrue();
    }

    @Test
    @DisplayName("not_in 全称语义：存在 P6 候选则不满足")
    void notInIsUniversal() {
        IamCondition condition = new IamCondition("all", List.of(
                new IamConditionTerm("positions[*].grade", IamConditionOperator.NOT_IN,
                        List.of("P6"))));

        ConditionOutcome outcome = evaluator.evaluate(condition, SNAPSHOT);

        assertThat(outcome.matched()).isFalse();
        assertThat(outcome.reasonCode()).isEqualTo("ATTRIBUTE_MISMATCH");
    }

    @Test
    @DisplayName("neq 全称语义：全部候选不等于目标才满足")
    void neqIsUniversal() {
        IamCondition condition = new IamCondition("all", List.of(
                new IamConditionTerm("positions[*].grade", IamConditionOperator.NEQ,
                        List.of("P5"))));

        assertThat(evaluator.evaluate(condition, SNAPSHOT).matched()).isTrue();
    }

    @Test
    @DisplayName("路径缺失 fail-closed 为 ATTRIBUTE_MISSING")
    void missingPathFailsClosedAsMissing() {
        IamCondition condition = new IamCondition("all", List.of(
                new IamConditionTerm("primary_position.department",
                        IamConditionOperator.EQ, List.of("D001"))));

        ConditionOutcome outcome = evaluator.evaluate(condition, SNAPSHOT);

        assertThat(outcome.matched()).isFalse();
        assertThat(outcome.reasonCode()).isEqualTo("ATTRIBUTE_MISSING");
        assertThat(outcome.attribute()).isEqualTo("primary_position.department");
    }

    @Test
    @DisplayName("exists 尊重 [true]/[false]")
    void existsRespectsBooleanValue() {
        IamCondition exists = new IamCondition("all", List.of(
                new IamConditionTerm("primary_position.grade",
                        IamConditionOperator.EXISTS, List.of("true"))));
        IamCondition absent = new IamCondition("all", List.of(
                new IamConditionTerm("primary_position.grade",
                        IamConditionOperator.EXISTS, List.of("false"))));

        assertThat(evaluator.evaluate(exists, SNAPSHOT).matched()).isTrue();
        assertThat(evaluator.evaluate(absent, SNAPSHOT).matched()).isFalse();
    }

    @Test
    @DisplayName("空候选集对 in 不满足、对 not_in 也不放行")
    void emptyPositionsFailClosedForInAndNotIn() {
        Map<String, Object> empty = Map.of("positions", List.of());
        IamCondition in = new IamCondition("all", List.of(
                new IamConditionTerm("positions[*].grade", IamConditionOperator.IN,
                        List.of("P6"))));
        IamCondition notIn = new IamCondition("all", List.of(
                new IamConditionTerm("positions[*].grade", IamConditionOperator.NOT_IN,
                        List.of("P6"))));

        assertThat(evaluator.evaluate(in, empty).matched()).isFalse();
        assertThat(evaluator.evaluate(notIn, empty).matched()).isFalse();
    }
}
