package cn.byteforce.seedj.starter.iam.authz;

import cn.byteforce.seedj.iam.authz.AttributeLookup;
import cn.byteforce.seedj.iam.authz.AttributeSnapshot;
import cn.byteforce.seedj.iam.authz.AttributeState;
import cn.byteforce.seedj.iam.authz.IamBinding;
import cn.byteforce.seedj.iam.authz.IamCheckResult;
import cn.byteforce.seedj.iam.authz.IamCondition;
import cn.byteforce.seedj.iam.authz.IamConditionOperator;
import cn.byteforce.seedj.iam.authz.IamConditionTerm;
import cn.byteforce.seedj.iam.authz.IamPermissionCondition;
import cn.byteforce.seedj.iam.authz.IamProxy;
import cn.byteforce.seedj.iam.authz.IamResolution;
import cn.byteforce.seedj.iam.authz.SodRuleSet;

import cn.byteforce.seedj.iam.IamRole;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamAttributeSource;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamAuthorizationRepository;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamBindingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 时点解析、代理、SoD 与属性条件 fail-closed。
 */
class IamContractAuthorizationServiceTest {

    private static final Instant JAN = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant SEP = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant OCT1 = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant OCT15 = Instant.parse("2026-10-15T12:00:00Z");
    private static final Instant OCT25 = Instant.parse("2026-10-25T12:00:00Z");
    private static final Instant NOV15 = Instant.parse("2026-11-15T12:00:00Z");

    private final InMemoryIamBindingStore bindings = new InMemoryIamBindingStore();
    private final InMemoryIamAuthorizationRepository roles = new InMemoryIamAuthorizationRepository();
    private final InMemoryIamAttributeSource attributes = new InMemoryIamAttributeSource();

    @BeforeEach
    void setUp() {
        bindings.clear();
        attributes.clear();
        roles.createRole(IamRole.create("r-approver", "default", "approver", "审批人", "",
                null, false));
        roles.createRole(IamRole.create("r-requester", "default", "requester", "申请人", "",
                null, false));
        roles.createRole(IamRole.create("r-inventory", "default", "inventory", "库存", "",
                null, false));
        roles.grantPermission("r-approver", "purchase.request.approve");
        roles.grantPermission("r-approver", "purchase.request.approve.senior");
        roles.grantPermission("r-approver", "purchase.request.approve.lead");
        roles.grantPermission("r-requester", "purchase.request.create");
        roles.grantPermission("r-inventory", "inventory.count");
        roles.grantPermission("r-inventory", "inventory.adjust");

        bindings.save(binding("b-away", "away", "r-approver", OCT1, null, OCT1));
        bindings.save(binding("b-both-approve", "both", "r-approver", JAN, null, JAN));
        bindings.save(binding("b-both-create", "both", "r-requester", JAN, null, JAN));
        bindings.save(binding("b-worker", "worker", "r-approver", JAN, null, JAN));
        bindings.save(binding("b-requester", "requester", "r-requester", JAN, null, JAN));
        bindings.save(new IamBinding("b-both-inventory", "both", "r-inventory",
                Map.of("warehouse", "W001"), JAN, null, JAN, null));
        bindings.save(binding("b-outage", "outage", "r-approver", JAN, null, JAN));
        bindings.save(new IamProxy("p-away", "away", "agent", Map.of("department", "D001"),
                OCT1, Instant.parse("2026-11-01T00:00:00Z"), OCT1, null));

        attributes.save(snapshot("worker", "2026-09-01T00:00:00Z", "2026-12-01T00:00:00Z",
                "P5", List.of("P5")));
        attributes.save(snapshot("worker", "2026-10-10T00:00:00Z", "2026-10-20T00:00:00Z",
                "M2", List.of("M2", "P6")));
        attributes.markSourceUnavailable("outage");
    }

    @Test
    @DisplayName("生效前：NO_PERMISSION")
    void historicalCheckBeforeValidFromIsNoPermission() {
        IamCheckResult result = service(List.of()).check("away", "purchase.request.approve",
                Instant.parse("2026-09-30T12:00:00Z"), null, null, null);

        assertThat(result.allowed()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("NO_PERMISSION");
        assertThat(result.attributeState()).isEqualTo(AttributeState.NOT_REQUIRED);
    }

    @Test
    @DisplayName("代理期内解析被代理人角色")
    void proxyResolvesDelegatorRolesInsideWindow() {
        IamResolution resolution = service(List.of()).resolve("agent", OCT15, null, Map.of());

        assertThat(resolution.roles()).contains("approver");
        assertThat(resolution.permissions()).contains("purchase.request.approve");
    }

    @Test
    @DisplayName("代理期外不残留角色")
    void proxyOutsideWindowIsDenied() {
        IamResolution resolution = service(List.of()).resolve("agent", NOV15, null, Map.of());

        assertThat(resolution.roles()).isEmpty();
        assertThat(resolution.permissions()).isEmpty();
    }

    @Test
    @DisplayName("prevent SoD 拒绝冲突双方")
    void preventSodDeniesBothConflictingPermissions() {
        IamContractAuthorizationService service = service(List.of());

        assertThat(service.check("both", "purchase.request.approve", OCT15, null, null, null)
                .reasonCode()).isEqualTo("SOD-001");
        assertThat(service.check("both", "purchase.request.create", OCT15, null, null, null)
                .reasonCode()).isEqualTo("SOD-001");
    }

    @Test
    @DisplayName("warn SoD 进 resolve.sod_warnings")
    void resolveReportsWarnSod() {
        IamResolution resolution = service(List.of()).resolve("both", OCT15, null, Map.of());

        assertThat(resolution.sodWarnings()).contains("SOD-003");
    }
    @Test
    @DisplayName("条件匹配主岗 P5 允许")
    void conditionMatchesPrimaryGrade() {
        IamCheckResult result = service(conditions()).check("worker",
                "purchase.request.approve", SEP, null, null, null);

        assertThat(result.allowed()).isTrue();
        assertThat(result.attributeState()).isEqualTo(AttributeState.FRESH);
    }

    @Test
    @DisplayName("条件不匹配：ATTRIBUTE_MISMATCH")
    void conditionMismatchIsAttributeMismatch() {
        IamCheckResult result = service(conditions()).check("worker",
                "purchase.request.approve", OCT15, null, null, null);

        assertThat(result.allowed()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("ATTRIBUTE_MISMATCH");
        assertThat(result.reason()).contains("primary_position.grade");
    }

    @Test
    @DisplayName("快照过期：ATTRIBUTE_STALE")
    void staleSnapshotFailsClosed() {
        IamCheckResult result = service(conditions()).check("worker",
                "purchase.request.approve", OCT25, null, null, null);

        assertThat(result.allowed()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("ATTRIBUTE_STALE");
        assertThat(result.attributeState()).isEqualTo(AttributeState.STALE);
    }

    @Test
    @DisplayName("无快照：ATTRIBUTE_MISSING")
    void missingSnapshotFailsClosed() {
        IamCheckResult result = service(conditions()).check("worker",
                "purchase.request.approve", Instant.parse("2026-08-01T00:00:00Z"),
                null, null, null);

        assertThat(result.reasonCode()).isEqualTo("ATTRIBUTE_MISSING");
    }

    @Test
    @DisplayName("属主不可达：ATTRIBUTE_SOURCE_UNAVAILABLE")
    void unavailableSourceFailsClosed() {
        IamCheckResult result = service(conditions()).check("outage",
                "purchase.request.approve", OCT15, null, null, null);

        assertThat(result.reasonCode()).isEqualTo("ATTRIBUTE_SOURCE_UNAVAILABLE");
    }

    @Test
    @DisplayName("无条件权限不受属性缺失影响")
    void permissionWithoutConditionIgnoresMissingSnapshot() {
        IamCheckResult result = service(conditions()).check("requester",
                "purchase.request.create", Instant.parse("2026-08-01T00:00:00Z"),
                null, null, null);

        assertThat(result.allowed()).isTrue();
        assertThat(result.attributeState()).isEqualTo(AttributeState.NOT_REQUIRED);
    }

    @Test
    @DisplayName("any + positions[*] 任一岗位命中")
    void anyPositionsStarMatchesAnyGrade() {
        assertThat(service(conditions()).check("worker", "purchase.request.approve.senior",
                OCT15, null, null, null).allowed()).isTrue();
    }

    @Test
    @DisplayName("not_in 全称语义")
    void notInIsUniversal() {
        IamContractAuthorizationService service = service(conditions());

        assertThat(service.check("worker", "purchase.request.approve.lead", OCT15, null,
                null, null).allowed()).isFalse();
        assertThat(service.check("worker", "purchase.request.approve.lead", SEP, null,
                null, null).allowed()).isTrue();
    }

    @Test
    @DisplayName("重复的权限条件声明在构造期失败")
    void duplicateConditionFails() {
        List<IamPermissionCondition> duplicated = List.of(
                new IamPermissionCondition("purchase.request.approve", new IamCondition("all",
                        List.of(new IamConditionTerm("primary_position.grade",
                                IamConditionOperator.IN, List.of("P5"))))),
                new IamPermissionCondition("purchase.request.approve", new IamCondition("any",
                        List.of(new IamConditionTerm("primary_position.grade",
                                IamConditionOperator.EQ, List.of("M2"))))));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new IamContractAuthorizationService(bindings, roles, attributes,
                        duplicated, SodRuleSet.defaults()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate condition for permission: "
                        + "purchase.request.approve");
    }

    @Test
    @DisplayName("无条件声明与空规则集都不影响解析")
    void nullConditionsAndRuleSetResolve() {
        IamContractAuthorizationService service = new IamContractAuthorizationService(bindings,
                roles, attributes, null, null);

        IamResolution resolution = service.resolve("requester", OCT15, null, Map.of());
        IamCheckResult result = service.check("requester", "purchase.request.create", OCT15,
                null, null, null);

        assertThat(resolution.roles()).containsExactly("requester");
        assertThat(resolution.sodWarnings()).isEmpty();
        assertThat(result.allowed()).isTrue();
    }

    @Test
    @DisplayName("作用域过滤：不匹配的绑定与代理都被排除")
    void scopeFilterExcludesMismatchedBindings() {
        IamContractAuthorizationService service = service(List.of());

        IamResolution matched = service.resolve("worker", OCT15, null,
                Map.of("department", "D001"));
        IamResolution mismatched = service.resolve("worker", OCT15, null,
                Map.of("department", "D999"));
        IamResolution nullScope = service.resolve("worker", OCT15, null, null);

        assertThat(matched.permissions()).contains("purchase.request.approve");
        assertThat(mismatched.permissions()).isEmpty();
        assertThat(nullScope.permissions()).contains("purchase.request.approve");
    }

    @Test
    @DisplayName("代理作用域不匹配时忽略代理授权")
    void proxyScopeMismatchIsIgnored() {
        IamContractAuthorizationService service = service(List.of());

        IamResolution matched = service.resolve("agent", OCT15, null,
                Map.of("department", "D001"));
        IamResolution mismatched = service.resolve("agent", OCT15, null,
                Map.of("department", "D002"));

        assertThat(matched.roles()).contains("approver");
        assertThat(mismatched.roles()).isEmpty();
    }

    @Test
    @DisplayName("系统时点过滤：recorded_as_of 之前的绑定才生效")
    void recordedAsOfFiltersBindings() {
        IamContractAuthorizationService service = service(List.of());

        assertThat(service.resolve("requester", OCT15, Instant.parse("2025-12-31T00:00:00Z"),
                Map.of()).permissions()).isEmpty();
        assertThat(service.resolve("requester", OCT15, OCT15, Map.of()).permissions())
                .containsExactly("purchase.request.create");
    }

    @Test
    @DisplayName("调用方快照优先于属性源，状态缺省为 fresh")
    void providedSnapshotIsUsedWithDefaultState() {
        IamContractAuthorizationService service = service(conditions());

        IamCheckResult allowed = service.check("worker", "purchase.request.approve", OCT15,
                null, Map.of("primary_position", Map.of("grade", "P5")), null);
        IamCheckResult denied = service.check("worker", "purchase.request.approve", OCT15,
                null, Map.of("primary_position", Map.of("grade", "M2")), null);

        assertThat(allowed.allowed()).isTrue();
        assertThat(allowed.attributeState()).isEqualTo(AttributeState.FRESH);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.reasonCode()).isEqualTo("ATTRIBUTE_MISMATCH");
    }

    @Test
    @DisplayName("调用方声明的快照状态非 fresh 时失败关闭")
    void providedStateFailsClosed() {
        IamContractAuthorizationService service = service(conditions());

        assertThat(service.check("worker", "purchase.request.approve", OCT15, null, Map.of(),
                AttributeState.STALE).reasonCode()).isEqualTo("ATTRIBUTE_STALE");
        assertThat(service.check("worker", "purchase.request.approve", OCT15, null, Map.of(),
                AttributeState.MISSING).reasonCode()).isEqualTo("ATTRIBUTE_MISSING");
        assertThat(service.check("worker", "purchase.request.approve", OCT15, null, Map.of(),
                AttributeState.SOURCE_UNAVAILABLE).reasonCode())
                .isEqualTo("ATTRIBUTE_SOURCE_UNAVAILABLE");
        IamCheckResult unexpected = service.check("worker", "purchase.request.approve", OCT15,
                null, Map.of(), AttributeState.NOT_REQUIRED);
        assertThat(unexpected.reasonCode()).isEqualTo("ATTRIBUTE_MISSING");
        assertThat(unexpected.reason()).isEqualTo("attribute snapshot is not fresh");
    }

    @Test
    @DisplayName("属性源返回 fresh 但无快照时条件按空属性失败")
    void freshLookupWithoutSnapshotEvaluatesEmptyBody() {
        IamContractAuthorizationService service = new IamContractAuthorizationService(bindings,
                roles, (principal, asOf, recordedAsOf) ->
                        new AttributeLookup(AttributeState.FRESH, null),
                conditions(), SodRuleSet.defaults());

        IamCheckResult result = service.check("worker", "purchase.request.approve", OCT15,
                null, null, null);

        assertThat(result.allowed()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("ATTRIBUTE_MISSING");
        assertThat(result.attributeState()).isEqualTo(AttributeState.FRESH);
    }

    @Test
    @DisplayName("attributes 直接返回属性源结果")
    void attributesDelegatesToSource() {
        IamContractAuthorizationService service = service(List.of());

        AttributeLookup lookup = service.attributes("worker", OCT15, null);

        assertThat(lookup.state()).isEqualTo(AttributeState.FRESH);
        assertThat(lookup.snapshot().principal()).isEqualTo("worker");
    }

    private IamContractAuthorizationService service(List<IamPermissionCondition> conditions) {
        return new IamContractAuthorizationService(bindings, roles, attributes, conditions,
                SodRuleSet.defaults());
    }

    private static List<IamPermissionCondition> conditions() {
        return List.of(
                new IamPermissionCondition("purchase.request.approve", new IamCondition("all",
                        List.of(new IamConditionTerm("primary_position.grade",
                                IamConditionOperator.IN, List.of("P5"))))),
                new IamPermissionCondition("purchase.request.approve.senior",
                        new IamCondition("any", List.of(
                                new IamConditionTerm("positions[*].grade",
                                        IamConditionOperator.IN, List.of("P6")),
                                new IamConditionTerm("employment_type",
                                        IamConditionOperator.EQ, List.of("contractor"))))),
                new IamPermissionCondition("purchase.request.approve.lead",
                        new IamCondition("all", List.of(
                                new IamConditionTerm("positions[*].grade",
                                        IamConditionOperator.NOT_IN, List.of("P6"))))));
    }

    private static IamBinding binding(String id, String principal, String role, Instant from,
                                      Instant to, Instant recordedAt) {
        return new IamBinding(id, principal, role, Map.of("department", "D001"), from, to,
                recordedAt, null);
    }

    private static AttributeSnapshot snapshot(String principal, String asOf, String expiresAt,
                                              String primaryGrade, List<String> grades) {
        Map<String, Object> primary = Map.of("grade", primaryGrade);
        List<Map<String, Object>> positions = grades.stream()
                .map(grade -> Map.<String, Object>of("grade", grade))
                .toList();
        return new AttributeSnapshot(principal, Instant.parse(asOf), Instant.parse(asOf),
                Instant.parse(expiresAt), "hr", "1.0.0",
                Map.of("employment_type", "full_time", "primary_position", primary,
                        "positions", positions));
    }
}
