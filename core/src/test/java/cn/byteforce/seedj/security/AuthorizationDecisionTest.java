package cn.byteforce.seedj.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.core.security.Caller;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AuthorizationDecisionTest {

    @Test
    @DisplayName("subject 从 Caller 完整转换")
    void subjectRoundTripsCaller() {
        Caller caller =
                new Caller(
                        "u-1",
                        "alice",
                        "default",
                        Set.of("ADMIN"),
                        Set.of("hr.employee.read"),
                        Set.of("finance"),
                        Set.of("pwd"),
                        "acr",
                        Map.of("grade", "M2"));
        AuthorizationSubject subject = AuthorizationSubject.from(caller);

        assertThat(subject.id()).isEqualTo("u-1");
        assertThat(subject.username()).isEqualTo("alice");
        assertThat(subject.permissions()).containsExactly("hr.employee.read");
        assertThat(subject.roles()).containsExactly("ADMIN");
        assertThat(subject.groups()).containsExactly("finance");
        assertThat(subject.attributes()).containsEntry("grade", "M2");
    }

    @Test
    @DisplayName("subject 的 null 字段归一为安全默认值")
    void subjectDefaultsForNulls() {
        AuthorizationSubject subject =
                new AuthorizationSubject(null, null, " ", null, null, null, null, null, null);

        assertThat(subject.id()).isEmpty();
        assertThat(subject.username()).isEmpty();
        assertThat(subject.tenantId()).isEqualTo("default");
        assertThat(subject.roles()).isEmpty();
        assertThat(subject.permissions()).isEmpty();
        assertThat(subject.groups()).isEmpty();
        assertThat(subject.amr()).isEmpty();
        assertThat(subject.acr()).isEmpty();
        assertThat(subject.attributes()).isEmpty();
    }

    @Test
    @DisplayName("subject 的 amr 为空集时保留空集")
    void subjectKeepsExplicitEmptyAmr() {
        AuthorizationSubject subject =
                new AuthorizationSubject(
                        "u", "u", "t", Set.of(), Set.of(), Set.of(), Set.of(), "", Map.of());

        assertThat(subject.amr()).isEmpty();
    }

    @Test
    @DisplayName("deny 携带 reasonCode")
    void denyCarriesReasonCode() {
        AuthorizationDecision decision =
                AuthorizationDecision.deny("SOD-001", "separation of duties", "sod");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo("SOD-001");
        assertThat(decision.policyId()).isEqualTo("sod");
    }

    @Test
    @DisplayName("deny 缺少 reasonCode 在构造期失败")
    void denyRequiresReasonCode() {
        assertThatThrownBy(() -> AuthorizationDecision.deny(null, "no", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reasonCode");
        assertThatThrownBy(() -> AuthorizationDecision.deny(" ", "no", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("allow 不带 reasonCode")
    void allowHasNoReasonCode() {
        AuthorizationDecision decision = AuthorizationDecision.allow("rbac");
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.reasonCode()).isNull();
        assertThat(decision.policyId()).isEqualTo("rbac");
    }

    @Test
    @DisplayName("request 校验必填动作/类型/时点")
    void requestValidatesRequiredFields() {
        AuthorizationSubject subject =
                AuthorizationSubject.from(
                        new Caller(
                                "u",
                                "u",
                                "t",
                                Set.of(),
                                Set.of(),
                                Set.of(),
                                Set.of("pwd"),
                                "acr",
                                Map.of()));

        assertThatThrownBy(
                        () ->
                                new AuthorizationRequest(
                                        subject,
                                        "",
                                        "employee",
                                        null,
                                        Map.of(),
                                        Instant.EPOCH,
                                        Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("action");
        assertThatThrownBy(
                        () ->
                                new AuthorizationRequest(
                                        subject,
                                        "read",
                                        null,
                                        null,
                                        Map.of(),
                                        Instant.EPOCH,
                                        Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resourceType");
        assertThatThrownBy(
                        () ->
                                new AuthorizationRequest(
                                        subject,
                                        "read",
                                        "employee",
                                        null,
                                        Map.of(),
                                        null,
                                        Map.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("asOf");
    }
}
