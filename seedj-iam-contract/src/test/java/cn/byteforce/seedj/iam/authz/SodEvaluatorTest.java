package cn.byteforce.seedj.iam.authz;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 职责分离：prevent/warn、作用域比较与缺省规则。 */
class SodEvaluatorTest {

    @Test
    @DisplayName("同部门同时持有 create + approve → SOD-001 prevent")
    void sameDepartmentConflictIsPrevent() {
        List<SodFinding> findings =
                SodEvaluator.findings(
                        SodRuleSet.defaults().rules(),
                        Map.of(
                                "purchase.request.create", List.of(Map.of("department", "D001")),
                                "purchase.request.approve", List.of(Map.of("department", "D001"))));

        assertThat(findings).extracting(SodFinding::ruleId).contains("SOD-001");
        assertThat(findings.get(0).enforcement()).isEqualTo(SodEnforcement.PREVENT);
    }

    @Test
    @DisplayName("不同部门不冲突")
    void differentDepartmentsDoNotConflict() {
        List<SodFinding> findings =
                SodEvaluator.findings(
                        SodRuleSet.defaults().rules(),
                        Map.of(
                                "purchase.request.create", List.of(Map.of("department", "D001")),
                                "purchase.request.approve", List.of(Map.of("department", "D002"))));

        assertThat(findings).isEmpty();
    }

    @Test
    @DisplayName("缺少作用域键不冲突")
    void missingScopeDoesNotConflict() {
        List<SodFinding> findings =
                SodEvaluator.findings(
                        SodRuleSet.defaults().rules(),
                        Map.of(
                                "purchase.request.create", List.of(Map.of()),
                                "purchase.request.approve", List.of(Map.of())));

        assertThat(findings).isEmpty();
    }

    @Test
    @DisplayName("warn 级规则仍产出 finding")
    void warnRuleStillProducesFinding() {
        List<SodFinding> findings =
                SodEvaluator.findings(
                        SodRuleSet.defaults().rules(),
                        Map.of(
                                "inventory.count", List.of(Map.of("warehouse", "W001")),
                                "inventory.adjust", List.of(Map.of("warehouse", "W001"))));

        assertThat(findings).extracting(SodFinding::ruleId).contains("SOD-003");
        assertThat(findings.get(0).enforcement()).isEqualTo(SodEnforcement.WARN);
    }

    @Test
    @DisplayName("同一规则多组 scope 只报一次")
    void sameRuleReportsOnce() {
        List<SodFinding> findings =
                SodEvaluator.findings(
                        SodRuleSet.defaults().rules(),
                        Map.of(
                                "purchase.request.create",
                                List.of(Map.of("department", "D001"), Map.of("department", "D002")),
                                "purchase.request.approve",
                                List.of(
                                        Map.of("department", "D001"),
                                        Map.of("department", "D002"))));

        assertThat(findings).hasSize(1);
    }
}
