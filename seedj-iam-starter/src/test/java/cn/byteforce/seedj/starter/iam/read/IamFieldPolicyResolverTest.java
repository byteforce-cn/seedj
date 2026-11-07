package cn.byteforce.seedj.starter.iam.read;

import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.ScopeContext;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.Maskers;
import cn.byteforce.seedj.domain.Privacy;
import cn.byteforce.seedj.iam.IamAuthorizationDecision;
import cn.byteforce.seedj.iam.IamAuthorizationRequest;
import cn.byteforce.seedj.iam.spi.IamPolicyDecisionPoint;
import cn.byteforce.seedj.module.ObjectModuleRegistry;
import cn.byteforce.seedj.module.ObjectModules;
import cn.byteforce.seedj.read.FieldPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.byteforce.seedj.read.FieldAccess.Action.FULL;
import static cn.byteforce.seedj.read.FieldAccess.Action.HIDDEN;
import static cn.byteforce.seedj.read.FieldAccess.Action.MASKED;
import static org.assertj.core.api.Assertions.assertThat;

class IamFieldPolicyResolverTest {

    private static ObjectModuleRegistry registry() {
        ObjectModules modules = ObjectModules.create();
        modules.object("employee", "hr_employee")
                .readPermission("hr.employee.read")
                .field("name", FieldType.TEXT)
                .field("phone", FieldType.TEXT).privacy(Privacy.SENSITIVE)
                        .mask(Maskers.last4())
                .field("id_number_hash", FieldType.TEXT).writeOnly();
        return modules.freeze();
    }

    private static Caller caller() {
        return new Caller("u-1", "alice", "default", Set.of(), Set.of(), Set.of(),
                Set.of("pwd"), "urn:seedj:acr:pwd", Map.of());
    }

    @Test
    void readAllowMeansFull() {
        IamFieldPolicyResolver resolver = new IamFieldPolicyResolver(
                new StubPdp(request -> request.action().equals("read")
                        ? IamAuthorizationDecision.allow("allow-read")
                        : IamAuthorizationDecision.deny("no matching allow policy")),
                registry());

        FieldPolicy policy = resolver.resolve("employee", caller(), ScopeContext.empty());

        assertThat(policy.fields().get("phone").action()).isEqualTo(FULL);
        assertThat(policy.fields().get("phone").policyId()).isEqualTo("allow-read");
    }

    @Test
    void maskedAllowMeansMaskedWhenReadIsAbsent() {
        IamFieldPolicyResolver resolver = new IamFieldPolicyResolver(
                new StubPdp(request -> request.action().equals("read.masked")
                        && request.resourceId().equals("phone")
                        ? IamAuthorizationDecision.allow("allow-masked")
                        : IamAuthorizationDecision.deny("no matching allow policy")),
                registry());

        FieldPolicy policy = resolver.resolve("employee", caller(), ScopeContext.empty());

        assertThat(policy.fields().get("phone").action()).isEqualTo(MASKED);
        assertThat(policy.fields().get("phone").policyId()).isEqualTo("allow-masked");
    }

    @Test
    void explicitDenyWins() {
        IamFieldPolicyResolver resolver = new IamFieldPolicyResolver(
                new StubPdp(request -> request.action().equals("read")
                        ? IamAuthorizationDecision.deny("denied", "deny-read")
                        : IamAuthorizationDecision.allow("allow-masked")),
                registry());

        FieldPolicy policy = resolver.resolve("employee", caller(), ScopeContext.empty());

        assertThat(policy.fields().get("phone").action()).isEqualTo(HIDDEN);
        assertThat(policy.fields().get("phone").policyId()).isEqualTo("deny-read");
    }

    @Test
    void fieldWithoutMatchingPolicyIsSkipped() {
        IamFieldPolicyResolver resolver = new IamFieldPolicyResolver(
                new StubPdp(request -> IamAuthorizationDecision.deny("no matching allow policy")),
                registry());

        FieldPolicy policy = resolver.resolve("employee", caller(), ScopeContext.empty());

        assertThat(policy.fields()).isEmpty();
    }

    @Test
    void writeOnlyFieldIsNeverQueried() {
        List<IamAuthorizationRequest> requests = new ArrayList<>();
        IamFieldPolicyResolver resolver = new IamFieldPolicyResolver(new StubPdp(request -> {
            requests.add(request);
            return IamAuthorizationDecision.deny("no matching allow policy");
        }), registry());

        resolver.resolve("employee", caller(), ScopeContext.empty());

        assertThat(requests).extracting(IamAuthorizationRequest::resourceId)
                .doesNotContain("id_number_hash");
    }

    @Test
    void classificationIsPassedToPdp() {
        List<IamAuthorizationRequest> requests = new ArrayList<>();
        IamFieldPolicyResolver resolver = new IamFieldPolicyResolver(new StubPdp(request -> {
            requests.add(request);
            return IamAuthorizationDecision.deny("no matching allow policy");
        }), registry());

        resolver.resolve("employee", caller(), ScopeContext.empty());

        assertThat(requests).filteredOn(request -> request.resourceId().equals("phone"))
                .allSatisfy(request -> assertThat(request.resourceAttributes())
                        .containsEntry("classification", "SENSITIVE"));
    }

    @Test
    void maskedExplicitDenyIsHidden() {
        IamFieldPolicyResolver resolver = new IamFieldPolicyResolver(
                new StubPdp(request -> request.action().equals("read.masked")
                        ? IamAuthorizationDecision.deny("denied", "deny-masked")
                        : IamAuthorizationDecision.deny("no matching allow policy")),
                registry());

        FieldPolicy policy = resolver.resolve("employee", caller(), ScopeContext.empty());

        assertThat(policy.fields().get("phone").action()).isEqualTo(HIDDEN);
        assertThat(policy.fields().get("phone").policyId()).isEqualTo("deny-masked");
    }

    @Test
    void supportsOnlyDeclaredObjectTypes() {
        IamFieldPolicyResolver resolver = new IamFieldPolicyResolver(
                new StubPdp(request -> IamAuthorizationDecision.deny("no matching allow policy")),
                registry());

        assertThat(resolver.supports("employee")).isTrue();
        assertThat(resolver.supports("invoice")).isFalse();
    }

    @Test
    void unknownObjectTypeFails() {
        IamFieldPolicyResolver resolver = new IamFieldPolicyResolver(
                new StubPdp(request -> IamAuthorizationDecision.deny("no matching allow policy")),
                registry());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> resolver.resolve("invoice", caller(), ScopeContext.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown object type: invoice");
    }

    private static final class StubPdp implements IamPolicyDecisionPoint {

        private final java.util.function.Function<IamAuthorizationRequest,
                IamAuthorizationDecision> decision;

        private StubPdp(java.util.function.Function<IamAuthorizationRequest,
                IamAuthorizationDecision> decision) {
            this.decision = decision;
        }

        @Override
        public IamAuthorizationDecision decide(IamAuthorizationRequest request) {
            return decision.apply(request);
        }
    }
}
