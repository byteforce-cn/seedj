package cn.byteforce.seedj.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ABAC/SCIM 表达式模型：默认值、空集合拒绝与授权请求不可变副本。 */
class IamPolicyModelTest {

    @Test
    @DisplayName("IamPolicy：effect 默认 DENY，condition 默认 Always，版本不为负")
    void policyDefaults() {
        IamPolicy policy =
                new IamPolicy(
                        "p-1",
                        null,
                        "orders",
                        null,
                        -5,
                        Set.of("order:read"),
                        Set.of("order"),
                        null,
                        true,
                        -1);

        assertThat(policy.id()).isEqualTo("p-1");
        assertThat(policy.tenantId()).isEqualTo(IamUser.DEFAULT_TENANT);
        assertThat(policy.name()).isEqualTo("orders");
        assertThat(policy.effect()).isEqualTo(IamPolicyEffect.DENY);
        assertThat(policy.priority()).isEqualTo(-5);
        assertThat(policy.actions()).containsExactly("order:read");
        assertThat(policy.resources()).containsExactly("order");
        assertThat(policy.condition()).isEqualTo(PolicyCondition.Always.INSTANCE);
        assertThat(policy.enabled()).isTrue();
        assertThat(policy.version()).isZero();
    }

    @Test
    @DisplayName("IamPolicy：id/name 必填，空 actions 或 resources 非法")
    void policyValidation() {
        assertThatThrownBy(
                        () ->
                                new IamPolicy(
                                        null,
                                        null,
                                        "orders",
                                        null,
                                        0,
                                        Set.of("a"),
                                        Set.of("r"),
                                        null,
                                        true,
                                        0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () ->
                                new IamPolicy(
                                        " ",
                                        null,
                                        "orders",
                                        null,
                                        0,
                                        Set.of("a"),
                                        Set.of("r"),
                                        null,
                                        true,
                                        0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
        assertThatThrownBy(
                        () ->
                                new IamPolicy(
                                        "p-1",
                                        null,
                                        null,
                                        null,
                                        0,
                                        Set.of("a"),
                                        Set.of("r"),
                                        null,
                                        true,
                                        0))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(
                        () ->
                                new IamPolicy(
                                        "p-1",
                                        null,
                                        "orders",
                                        null,
                                        0,
                                        Set.of(),
                                        Set.of("r"),
                                        null,
                                        true,
                                        0))
                .isInstanceOf(IamException.class)
                .extracting(e -> ((IamException) e).getCode())
                .isEqualTo(IamErrorCode.POLICY_INVALID);
        assertThatThrownBy(
                        () ->
                                new IamPolicy(
                                        "p-1",
                                        null,
                                        "orders",
                                        null,
                                        0,
                                        Set.of("a"),
                                        null,
                                        null,
                                        true,
                                        0))
                .isInstanceOf(IamException.class);
    }

    @Test
    @DisplayName("IamPolicy：allow/deny 工厂与 withEnabled")
    void policyFactories() {
        PolicyCondition condition = new PolicyCondition.Exists(PolicyOperand.subject("department"));

        IamPolicy allow =
                IamPolicy.allow(
                        "p-1",
                        "tenant-a",
                        "read",
                        Set.of("order:read"),
                        Set.of("order"),
                        condition);
        assertThat(allow.effect()).isEqualTo(IamPolicyEffect.ALLOW);
        assertThat(allow.priority()).isZero();
        assertThat(allow.enabled()).isTrue();
        assertThat(allow.tenantId()).isEqualTo("tenant-a");
        assertThat(allow.condition()).isSameAs(condition);

        IamPolicy deny =
                IamPolicy.deny("p-2", null, "block", Set.of("order:delete"), Set.of("order"), null);
        assertThat(deny.effect()).isEqualTo(IamPolicyEffect.DENY);
        assertThat(deny.priority()).isEqualTo(1000);
        assertThat(deny.tenantId()).isEqualTo(IamUser.DEFAULT_TENANT);
        assertThat(deny.condition()).isEqualTo(PolicyCondition.Always.INSTANCE);

        IamPolicy disabled = allow.withEnabled(false);
        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.id()).isEqualTo("p-1");
        assertThat(disabled.effect()).isEqualTo(IamPolicyEffect.ALLOW);
    }

    @Test
    @DisplayName("PolicyCondition：And/Or 至少一个条件，Not/Compare/Exists 不允许 null")
    void policyConditionValidation() {
        PolicyCondition.Compare compare =
                new PolicyCondition.Compare(
                        PolicyOperand.subject("department"),
                        PolicyComparisonOperator.EQ,
                        PolicyOperand.literal("sales"));
        PolicyCondition.Exists exists =
                new PolicyCondition.Exists(PolicyOperand.resource("ownerId"));

        assertThat(new PolicyCondition.And(List.of(compare, exists)).conditions())
                .containsExactly(compare, exists);
        assertThat(new PolicyCondition.Or(List.of(compare)).conditions()).containsExactly(compare);
        assertThat(new PolicyCondition.Not(compare).condition()).isSameAs(compare);
        assertThat(compare.operator()).isEqualTo(PolicyComparisonOperator.EQ);
        assertThat(exists.operand().source()).isEqualTo(PolicyOperandSource.RESOURCE);

        assertThatThrownBy(() -> new PolicyCondition.And(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("and");
        assertThatThrownBy(() -> new PolicyCondition.Or(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("or");
        assertThatThrownBy(() -> new PolicyCondition.And(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PolicyCondition.Or(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PolicyCondition.Not(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PolicyCondition.Exists(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () ->
                                new PolicyCondition.Compare(
                                        null,
                                        PolicyComparisonOperator.EQ,
                                        PolicyOperand.literal("x")))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () ->
                                new PolicyCondition.Compare(
                                        PolicyOperand.subject("a"),
                                        null,
                                        PolicyOperand.literal("x")))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () ->
                                new PolicyCondition.Compare(
                                        PolicyOperand.subject("a"),
                                        PolicyComparisonOperator.EQ,
                                        null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("PolicyOperand：LITERAL 必须携带值，路径 null 归一化为空串")
    void policyOperand() {
        assertThat(PolicyOperand.subject("department").source())
                .isEqualTo(PolicyOperandSource.SUBJECT);
        assertThat(PolicyOperand.resource("ownerId").path()).isEqualTo("ownerId");
        assertThat(PolicyOperand.action().path()).isEqualTo("action");
        assertThat(PolicyOperand.environment("ip").source())
                .isEqualTo(PolicyOperandSource.ENVIRONMENT);
        assertThat(PolicyOperand.literal(42).literal()).isEqualTo(42);
        assertThat(new PolicyOperand(PolicyOperandSource.RESOURCE, null, null).path()).isEmpty();

        assertThatThrownBy(() -> PolicyOperand.literal(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("literal");
        assertThatThrownBy(() -> new PolicyOperand(null, "path", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("ScimFilter：And/Or 至少一个过滤器，属性必填")
    void scimFilterValidation() {
        ScimFilter.Compare compare =
                new ScimFilter.Compare("userName", ScimComparisonOperator.EQ, "alice");
        ScimFilter.Present present = new ScimFilter.Present("emails");

        assertThat(new ScimFilter.And(List.of(compare, present)).filters())
                .containsExactly(compare, present);
        assertThat(new ScimFilter.Or(List.of(compare)).filters()).containsExactly(compare);
        assertThat(new ScimFilter.Not(compare).filter()).isSameAs(compare);
        assertThat(compare.attribute()).isEqualTo("userName");
        assertThat(compare.operator()).isEqualTo(ScimComparisonOperator.EQ);
        assertThat(compare.value()).isEqualTo("alice");
        assertThat(present.attribute()).isEqualTo("emails");

        assertThatThrownBy(() -> new ScimFilter.And(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("and");
        assertThatThrownBy(() -> new ScimFilter.Or(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("or");
        assertThatThrownBy(() -> new ScimFilter.And(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ScimFilter.Or(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ScimFilter.Not(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ScimFilter.Compare(null, ScimComparisonOperator.EQ, "x"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ScimFilter.Compare("userName", null, "x"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ScimFilter.Present(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("IamAuthorizationRequest：resourceId/attributes/environment 归一化并可替换")
    void authorizationRequest() {
        IamSubject subject =
                new IamSubject(
                        "u-1",
                        "alice",
                        "default",
                        Set.of("admin"),
                        Set.of("order:read"),
                        Set.of(),
                        Set.of("pwd"),
                        "urn:seedj:acr:pwd",
                        Map.of());

        IamAuthorizationRequest request =
                new IamAuthorizationRequest(subject, "order:read", "order", null, null, null);

        assertThat(request.subject()).isSameAs(subject);
        assertThat(request.action()).isEqualTo("order:read");
        assertThat(request.resourceType()).isEqualTo("order");
        assertThat(request.resourceId()).isEmpty();
        assertThat(request.resourceAttributes()).isEmpty();
        assertThat(request.environment()).isEmpty();

        IamAuthorizationRequest replaced = request.withResourceAttributes(Map.of("ownerId", "u-1"));
        assertThat(replaced.resourceAttributes()).containsEntry("ownerId", "u-1");
        assertThat(replaced.subject()).isSameAs(subject);
        assertThat(replaced.action()).isEqualTo("order:read");
        assertThat(replaced.resourceId()).isEmpty();
        assertThat(request.resourceAttributes()).isEmpty();

        assertThatThrownBy(() -> new IamAuthorizationRequest(null, "a", "t", null, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IamAuthorizationRequest(subject, null, "t", null, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IamAuthorizationRequest(subject, "a", null, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
