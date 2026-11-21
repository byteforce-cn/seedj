package cn.byteforce.seedj.starter.iam.read;

import static cn.byteforce.seedj.read.FieldAccess.Action.FULL;
import static cn.byteforce.seedj.read.FieldAccess.Action.HIDDEN;
import static cn.byteforce.seedj.read.FieldAccess.Action.MASKED;
import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.ScopeContext;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.Maskers;
import cn.byteforce.seedj.domain.Privacy;
import cn.byteforce.seedj.iam.spi.IamPolicyDecisionPoint;
import cn.byteforce.seedj.module.ObjectModuleRegistry;
import cn.byteforce.seedj.module.ObjectModules;
import cn.byteforce.seedj.read.FieldPolicy;
import cn.byteforce.seedj.security.AuthorizationDecision;
import cn.byteforce.seedj.security.AuthorizationRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IamFieldPolicyResolverTest {

    private static ObjectModuleRegistry registry() {
        ObjectModules modules = ObjectModules.create();
        modules.object("employee", "hr_employee")
                .readPermission("hr.employee.read")
                .field("name", FieldType.TEXT)
                .field("phone", FieldType.TEXT)
                .privacy(Privacy.SENSITIVE)
                .mask(Maskers.last4())
                .field("id_number_hash", FieldType.TEXT)
                .writeOnly();
        return modules.freeze();
    }

    private static Caller caller() {
        return new Caller(
                "u-1",
                "alice",
                "default",
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of("pwd"),
                "urn:seedj:acr:pwd",
                Map.of());
    }

    @Test
    void readAllowMeansFull() {
        IamFieldPolicyResolver resolver =
                new IamFieldPolicyResolver(
                        new StubPdp(
                                request ->
                                        request.action().equals("read")
                                                ? AuthorizationDecision.allow("allow-read")
                                                : AuthorizationDecision.deny(
                                                        "no matching allow policy",
                                                        "no matching allow policy",
                                                        null)),
                        registry());

        FieldPolicy policy = resolver.resolve("employee", caller(), ScopeContext.empty());

        assertThat(policy.fields().get("phone").action()).isEqualTo(FULL);
        assertThat(policy.fields().get("phone").policyId()).isEqualTo("allow-read");
    }

    @Test
    void maskedAllowMeansMaskedWhenReadIsAbsent() {
        IamFieldPolicyResolver resolver =
                new IamFieldPolicyResolver(
                        new StubPdp(
                                request ->
                                        request.action().equals("read.masked")
                                                        && request.resourceId().equals("phone")
                                                ? AuthorizationDecision.allow("allow-masked")
                                                : AuthorizationDecision.deny(
                                                        "no matching allow policy",
                                                        "no matching allow policy",
                                                        null)),
                        registry());

        FieldPolicy policy = resolver.resolve("employee", caller(), ScopeContext.empty());

        assertThat(policy.fields().get("phone").action()).isEqualTo(MASKED);
        assertThat(policy.fields().get("phone").policyId()).isEqualTo("allow-masked");
    }

    @Test
    void explicitDenyWins() {
        IamFieldPolicyResolver resolver =
                new IamFieldPolicyResolver(
                        new StubPdp(
                                request ->
                                        request.action().equals("read")
                                                ? AuthorizationDecision.deny(
                                                        "denied", "denied", "deny-read")
                                                : AuthorizationDecision.allow("allow-masked")),
                        registry());

        FieldPolicy policy = resolver.resolve("employee", caller(), ScopeContext.empty());

        assertThat(policy.fields().get("phone").action()).isEqualTo(HIDDEN);
        assertThat(policy.fields().get("phone").policyId()).isEqualTo("deny-read");
    }

    @Test
    void fieldWithoutMatchingPolicyIsSkipped() {
        IamFieldPolicyResolver resolver =
                new IamFieldPolicyResolver(
                        new StubPdp(
                                request ->
                                        AuthorizationDecision.deny(
                                                "no matching allow policy",
                                                "no matching allow policy",
                                                null)),
                        registry());

        FieldPolicy policy = resolver.resolve("employee", caller(), ScopeContext.empty());

        assertThat(policy.fields()).isEmpty();
    }

    @Test
    void writeOnlyFieldIsNeverQueried() {
        List<AuthorizationRequest> requests = new ArrayList<>();
        IamFieldPolicyResolver resolver =
                new IamFieldPolicyResolver(
                        new StubPdp(
                                request -> {
                                    requests.add(request);
                                    return AuthorizationDecision.deny(
                                            "no matching allow policy",
                                            "no matching allow policy",
                                            null);
                                }),
                        registry());

        resolver.resolve("employee", caller(), ScopeContext.empty());

        assertThat(requests)
                .extracting(AuthorizationRequest::resourceId)
                .doesNotContain("id_number_hash");
    }

    @Test
    void classificationIsPassedToPdp() {
        List<AuthorizationRequest> requests = new ArrayList<>();
        IamFieldPolicyResolver resolver =
                new IamFieldPolicyResolver(
                        new StubPdp(
                                request -> {
                                    requests.add(request);
                                    return AuthorizationDecision.deny(
                                            "no matching allow policy",
                                            "no matching allow policy",
                                            null);
                                }),
                        registry());

        resolver.resolve("employee", caller(), ScopeContext.empty());

        assertThat(requests)
                .filteredOn(request -> request.resourceId().equals("phone"))
                .allSatisfy(
                        request ->
                                assertThat(request.resourceAttributes())
                                        .containsEntry("classification", "SENSITIVE"));
    }

    @Test
    void maskedExplicitDenyIsHidden() {
        IamFieldPolicyResolver resolver =
                new IamFieldPolicyResolver(
                        new StubPdp(
                                request ->
                                        request.action().equals("read.masked")
                                                ? AuthorizationDecision.deny(
                                                        "denied", "denied", "deny-masked")
                                                : AuthorizationDecision.deny(
                                                        "no matching allow policy",
                                                        "no matching allow policy",
                                                        null)),
                        registry());

        FieldPolicy policy = resolver.resolve("employee", caller(), ScopeContext.empty());

        assertThat(policy.fields().get("phone").action()).isEqualTo(HIDDEN);
        assertThat(policy.fields().get("phone").policyId()).isEqualTo("deny-masked");
    }

    @Test
    void supportsOnlyDeclaredObjectTypes() {
        IamFieldPolicyResolver resolver =
                new IamFieldPolicyResolver(
                        new StubPdp(
                                request ->
                                        AuthorizationDecision.deny(
                                                "no matching allow policy",
                                                "no matching allow policy",
                                                null)),
                        registry());

        assertThat(resolver.supports("employee")).isTrue();
        assertThat(resolver.supports("invoice")).isFalse();
    }

    @Test
    void unknownObjectTypeFails() {
        IamFieldPolicyResolver resolver =
                new IamFieldPolicyResolver(
                        new StubPdp(
                                request ->
                                        AuthorizationDecision.deny(
                                                "no matching allow policy",
                                                "no matching allow policy",
                                                null)),
                        registry());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> resolver.resolve("invoice", caller(), ScopeContext.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown object type: invoice");
    }

    private static final class StubPdp implements IamPolicyDecisionPoint {

        private final java.util.function.Function<AuthorizationRequest, AuthorizationDecision>
                decision;

        private StubPdp(
                java.util.function.Function<AuthorizationRequest, AuthorizationDecision> decision) {
            this.decision = decision;
        }

        @Override
        public AuthorizationDecision decide(AuthorizationRequest request) {
            return decision.apply(request);
        }
    }
}
