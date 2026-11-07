package cn.byteforce.seedj.security;

import cn.byteforce.seedj.core.security.Caller;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorizationDecisionTest {

    @Test
    @DisplayName("subject 从 Caller 完整转换")
    void subjectRoundTripsCaller() {
        Caller caller = new Caller("u-1", "alice", "default", Set.of("ADMIN"),
                Set.of("hr.employee.read"), Set.of("finance"), Set.of("pwd"), "acr",
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
    @DisplayName("deny 携带 reasonCode")
    void denyCarriesReasonCode() {
        AuthorizationDecision decision = AuthorizationDecision.deny(
                "SOD-001", "separation of duties", "sod");

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
        AuthorizationSubject subject = AuthorizationSubject.from(new Caller("u", "u", "t",
                Set.of(), Set.of(), Set.of(), Set.of("pwd"), "acr", Map.of()));

        assertThatThrownBy(() -> new AuthorizationRequest(subject, "", "employee", null,
                Map.of(), Instant.EPOCH, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("action");
        assertThatThrownBy(() -> new AuthorizationRequest(subject, "read", null, null,
                Map.of(), Instant.EPOCH, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resourceType");
        assertThatThrownBy(() -> new AuthorizationRequest(subject, "read", "employee", null,
                Map.of(), null, Map.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("asOf");
    }
}
