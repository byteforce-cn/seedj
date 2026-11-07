package cn.byteforce.seedj.iam.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 授权值类型：检查结果、解析结果、属性拉取、条件项与 SoD 规则。 */
class IamAuthzValueTypesTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("IamCheckResult：默认 NOT_REQUIRED，reason/reasonCode 必填")
    void checkResult() {
        IamCheckResult result = new IamCheckResult(true, "ok", "ALLOWED", null);

        assertThat(result.allowed()).isTrue();
        assertThat(result.reason()).isEqualTo("ok");
        assertThat(result.reasonCode()).isEqualTo("ALLOWED");
        assertThat(result.attributeState()).isEqualTo(AttributeState.NOT_REQUIRED);

        assertThat(
                        new IamCheckResult(false, "stale", "ATTRIBUTE_STALE", AttributeState.STALE)
                                .attributeState())
                .isEqualTo(AttributeState.STALE);

        assertThatThrownBy(() -> new IamCheckResult(true, null, "ALLOWED", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("reason");
        assertThatThrownBy(() -> new IamCheckResult(true, "ok", null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("reasonCode");
    }

    @Test
    @DisplayName("IamResolution：三个列表归一化为空")
    void resolution() {
        IamResolution empty = new IamResolution(null, null, null);

        assertThat(empty.roles()).isEmpty();
        assertThat(empty.permissions()).isEmpty();
        assertThat(empty.sodWarnings()).isEmpty();

        IamResolution populated =
                new IamResolution(List.of("admin"), List.of("order:read"), List.of("SOD-003"));
        assertThat(populated.roles()).containsExactly("admin");
        assertThat(populated.permissions()).containsExactly("order:read");
        assertThat(populated.sodWarnings()).containsExactly("SOD-003");
    }

    @Test
    @DisplayName("AttributeLookup：missing/unavailable 不带快照")
    void attributeLookup() {
        AttributeLookup missing = AttributeLookup.missing();
        assertThat(missing.state()).isEqualTo(AttributeState.MISSING);
        assertThat(missing.snapshot()).isNull();

        AttributeLookup unavailable = AttributeLookup.unavailable();
        assertThat(unavailable.state()).isEqualTo(AttributeState.SOURCE_UNAVAILABLE);
        assertThat(unavailable.snapshot()).isNull();

        AttributeSnapshot snapshot =
                new AttributeSnapshot("alice", NOW, NOW, null, "hr", "v1", Map.of());
        AttributeLookup fresh = new AttributeLookup(AttributeState.FRESH, snapshot);
        assertThat(fresh.state()).isEqualTo(AttributeState.FRESH);
        assertThat(fresh.snapshot()).isSameAs(snapshot);

        assertThatThrownBy(() -> new AttributeLookup(null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("state");
    }

    @Test
    @DisplayName("IamAttributeSource.none：始终 missing")
    void attributeSourceNone() {
        AttributeLookup lookup = IamAttributeSource.none().lookup("alice", NOW, NOW);

        assertThat(lookup.state()).isEqualTo(AttributeState.MISSING);
        assertThat(lookup.snapshot()).isNull();
    }

    @Test
    @DisplayName("ConditionOutcome：success/mismatch/missing 工厂")
    void conditionOutcome() {
        assertThat(ConditionOutcome.success().matched()).isTrue();
        assertThat(ConditionOutcome.success().reasonCode()).isNull();

        assertThat(ConditionOutcome.mismatch("positions[*].grade").matched()).isFalse();
        assertThat(ConditionOutcome.mismatch("positions[*].grade").reasonCode())
                .isEqualTo("ATTRIBUTE_MISMATCH");

        assertThat(ConditionOutcome.missing("positions[*].grade").matched()).isFalse();
        assertThat(ConditionOutcome.missing("positions[*].grade").reasonCode())
                .isEqualTo("ATTRIBUTE_MISSING");
        assertThat(ConditionOutcome.missing("positions[*].grade").attribute())
                .isEqualTo("positions[*].grade");
    }

    @Test
    @DisplayName("IamCondition：logic 小写归一化，只接受 all/any，terms 非空")
    void condition() {
        IamConditionTerm term =
                new IamConditionTerm("department", IamConditionOperator.EQ, List.of("D001"));

        IamCondition condition = new IamCondition("ALL", List.of(term));
        assertThat(condition.logic()).isEqualTo("all");
        assertThat(condition.terms()).containsExactly(term);

        assertThatThrownBy(() -> new IamCondition("all", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("terms");
        assertThatThrownBy(() -> new IamCondition("all", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("terms");
        assertThatThrownBy(() -> new IamCondition("xor", List.of(term)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("logic");
        assertThatThrownBy(() -> new IamCondition(null, List.of(term)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("logic");
    }

    @Test
    @DisplayName("IamConditionTerm：属性与运算必填，候选值非空")
    void conditionTerm() {
        IamConditionTerm term =
                new IamConditionTerm(
                        "department", IamConditionOperator.IN, List.of("D001", "D002"));

        assertThat(term.attribute()).isEqualTo("department");
        assertThat(term.op()).isEqualTo(IamConditionOperator.IN);
        assertThat(term.values()).containsExactly("D001", "D002");

        assertThatThrownBy(() -> new IamConditionTerm(" ", IamConditionOperator.EQ, List.of("x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attribute");
        assertThatThrownBy(() -> new IamConditionTerm("a", null, List.of("x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("op");
        assertThatThrownBy(() -> new IamConditionTerm("a", IamConditionOperator.EQ, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("values");
        assertThatThrownBy(() -> new IamConditionTerm("a", IamConditionOperator.EQ, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("values");
    }

    @Test
    @DisplayName("IamPermissionCondition：权限非空白且条件必填")
    void permissionCondition() {
        IamCondition condition =
                new IamCondition(
                        "all",
                        List.of(
                                new IamConditionTerm(
                                        "department", IamConditionOperator.EQ, List.of("D001"))));

        IamPermissionCondition binding = new IamPermissionCondition("order:read", condition);
        assertThat(binding.permission()).isEqualTo("order:read");
        assertThat(binding.condition()).isSameAs(condition);

        assertThatThrownBy(() -> new IamPermissionCondition(null, condition))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permission");
        assertThatThrownBy(() -> new IamPermissionCondition(" ", condition))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IamPermissionCondition("order:read", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("condition");
    }

    @Test
    @DisplayName("SodRule：至少两条冲突权限，scope/enforcement 必填")
    void sodRule() {
        SodRule rule =
                new SodRule(
                        "SOD-001",
                        "采购申请与审批分离",
                        List.of("purchase.request.create", "purchase.request.approve"),
                        SodScope.SAME_DEPARTMENT,
                        SodEnforcement.PREVENT);

        assertThat(rule.id()).isEqualTo("SOD-001");
        assertThat(rule.name()).isEqualTo("采购申请与审批分离");
        assertThat(rule.conflictingPermissions())
                .containsExactly("purchase.request.create", "purchase.request.approve");
        assertThat(rule.scope()).isEqualTo(SodScope.SAME_DEPARTMENT);
        assertThat(rule.enforcement()).isEqualTo(SodEnforcement.PREVENT);
        assertThat(rule.scope().scopeKey()).isEqualTo("department");
        assertThat(SodScope.SAME_ORGANIZATION.scopeKey()).isEqualTo("organization");
        assertThat(SodScope.SAME_WAREHOUSE.scopeKey()).isEqualTo("warehouse");

        assertThatThrownBy(
                        () ->
                                new SodRule(
                                        null,
                                        "n",
                                        List.of("a", "b"),
                                        SodScope.SAME_DEPARTMENT,
                                        SodEnforcement.PREVENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
        assertThatThrownBy(
                        () ->
                                new SodRule(
                                        "SOD-001",
                                        " ",
                                        List.of("a", "b"),
                                        SodScope.SAME_DEPARTMENT,
                                        SodEnforcement.PREVENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
        assertThatThrownBy(
                        () ->
                                new SodRule(
                                        "SOD-001",
                                        "n",
                                        null,
                                        SodScope.SAME_DEPARTMENT,
                                        SodEnforcement.PREVENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("two conflicting");
        assertThatThrownBy(
                        () ->
                                new SodRule(
                                        "SOD-001",
                                        "n",
                                        List.of("a"),
                                        SodScope.SAME_DEPARTMENT,
                                        SodEnforcement.PREVENT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new SodRule(
                                        "SOD-001",
                                        "n",
                                        List.of("a", "b"),
                                        null,
                                        SodEnforcement.PREVENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope");
        assertThatThrownBy(
                        () ->
                                new SodRule(
                                        "SOD-001",
                                        "n",
                                        List.of("a", "b"),
                                        SodScope.SAME_DEPARTMENT,
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("SodRuleSet：null 归一化，defaults 内置三条规则")
    void sodRuleSet() {
        assertThat(new SodRuleSet(null).rules()).isEmpty();
        assertThat(new SodRuleSet(List.of()).rules()).isEmpty();

        SodRuleSet defaults = SodRuleSet.defaults();
        assertThat(defaults.rules()).hasSize(3);
        assertThat(defaults.rules())
                .extracting(SodRule::id)
                .containsExactly("SOD-001", "SOD-002", "SOD-003");
        assertThat(defaults.rules().get(2).enforcement()).isEqualTo(SodEnforcement.WARN);
    }

    @Test
    @DisplayName("SodFinding：permissions 归一化为空")
    void sodFinding() {
        SodFinding finding = new SodFinding("SOD-001", SodEnforcement.WARN, null);

        assertThat(finding.ruleId()).isEqualTo("SOD-001");
        assertThat(finding.enforcement()).isEqualTo(SodEnforcement.WARN);
        assertThat(finding.permissions()).isEmpty();
        assertThat(
                        new SodFinding("SOD-002", SodEnforcement.PREVENT, List.of("a", "b"))
                                .permissions())
                .containsExactly("a", "b");
    }
}
