package cn.byteforce.seedj.starter.eis.authz;

import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzBinding;
import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzDecision;
import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzPrincipal;
import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzProxy;
import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzResolution;
import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzRole;
import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzSodRule;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link EisAuthzEngine} 语义测试（对齐 {@code tools/authz-resolver --self-test} +
 * 作用域过滤 / 系统轴 / warn 上报 / 代理范围约束）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class EisAuthzEngineTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final UUID P_AWAY = UUID.fromString("01900000-0000-7000-8000-000000000001");

    private static final UUID P_AGENT = UUID.fromString("01900000-0000-7000-8000-000000000002");

    private static final UUID P_BOTH = UUID.fromString("01900000-0000-7000-8000-000000000003");

    private static final UUID R_APPROVE = UUID.fromString("01900000-0000-7000-8000-0000000000a1");

    private static final UUID R_CREATE = UUID.fromString("01900000-0000-7000-8000-0000000000a2");

    private static final Instant T_BASE = Instant.parse("2026-01-01T00:00:00Z");

    private static final Instant T_OCT = Instant.parse("2026-10-01T00:00:00Z");

    private static final Instant AFTER = Instant.parse("2026-10-15T12:00:00Z");

    private static final Instant BEFORE = Instant.parse("2026-09-30T12:00:00Z");

    private static JsonNode scope(String json) {
        return MAPPER.readTree(json);
    }

    private static InMemoryEisAuthzStore fixtures() {
        return InMemoryEisAuthzStore.builder()
                .principal(new EisAuthzPrincipal(P_AWAY, "person", "away", T_BASE, null, T_BASE, null))
                .principal(new EisAuthzPrincipal(P_AGENT, "person", "agent", T_BASE, null, T_BASE, null))
                .principal(new EisAuthzPrincipal(P_BOTH, "person", "both", T_BASE, null, T_BASE, null))
                .role(new EisAuthzRole(R_APPROVE, "approver", List.of("purchase.request.approve"), T_BASE, null,
                        T_BASE, null))
                .role(new EisAuthzRole(R_CREATE, "requester", List.of("purchase.request.create"), T_BASE, null,
                        T_BASE, null))
                .binding(new EisAuthzBinding(UUID.randomUUID(), P_AWAY, R_APPROVE.toString(),
                        scope("{\"department\":\"D001\"}"), T_OCT, null, T_OCT, null))
                .binding(new EisAuthzBinding(UUID.randomUUID(), P_BOTH, R_APPROVE.toString(),
                        scope("{\"department\":\"D001\"}"), T_BASE, null, T_BASE, null))
                .binding(new EisAuthzBinding(UUID.randomUUID(), P_BOTH, R_CREATE.toString(),
                        scope("{\"department\":\"D001\"}"), T_BASE, null, T_BASE, null))
                .proxy(new EisAuthzProxy(UUID.randomUUID(), P_AWAY, P_AGENT,
                        scope("{\"department\":\"D001\"}"), T_OCT, Instant.parse("2026-11-01T00:00:00Z"),
                        T_OCT, null))
                .build();
    }

    private final EisAuthzEngine engine = new EisAuthzEngine(fixtures());

    @Test
    void checkBeforeGrantTimeIsDenied() {
        EisAuthzDecision decision = engine.check("away", "purchase.request.approve", BEFORE, null);

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.getReasonCode()).isEqualTo("NO_PERMISSION");
        assertThat(decision.getReason()).contains("无权限 purchase.request.approve");
    }

    @Test
    void checkAfterGrantTimeIsAllowed() {
        EisAuthzDecision decision = engine.check("away", "purchase.request.approve", AFTER, null);

        assertThat(decision.isAllowed()).isTrue();
        assertThat(decision.getReasonCode()).isEqualTo("ALLOWED");
        assertThat(decision.getWarnings()).isEmpty();
    }

    @Test
    void proxyAgentActsWithinWindow() {
        assertThat(engine.check("agent", "purchase.request.approve", AFTER, null).isAllowed()).isTrue();
    }

    @Test
    void proxyAgentOutsideWindowIsDenied() {
        EisAuthzDecision decision = engine.check("agent", "purchase.request.approve",
                Instant.parse("2026-11-15T12:00:00Z"), null);

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.getReasonCode()).isEqualTo("NO_PERMISSION");
    }

    @Test
    void sodPreventDeniesConflictingPermission() {
        EisAuthzDecision decision = engine.check("both", "purchase.request.approve", AFTER, null);

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.getReasonCode()).isEqualTo("SOD-001");
        assertThat(decision.getReason()).contains("违反职责分离 SOD-001");
    }

    @Test
    void resolveExpandsProxyRoles() {
        EisAuthzResolution resolution = engine.resolve("agent", AFTER, null, null);

        assertThat(resolution.getRoles()).contains("approver");
        assertThat(resolution.getPermissions()).contains("purchase.request.approve");
        assertThat(resolution.getPrincipal()).isEqualTo(P_AGENT);
    }

    @Test
    void resolveAppliesScopeFilter() {
        EisAuthzResolution matching = engine.resolve("away", AFTER, null, scope("{\"department\":\"D001\"}"));
        EisAuthzResolution mismatching = engine.resolve("away", AFTER, null, scope("{\"department\":\"D999\"}"));

        assertThat(matching.getRoles()).containsExactly("approver");
        assertThat(mismatching.getRoles()).isEmpty();
        assertThat(mismatching.getPermissions()).isEmpty();
    }

    @Test
    void resolveHonoursRecordedAxis() {
        EisAuthzResolution beforeRecord = engine.resolve("away", AFTER, Instant.parse("2026-09-15T00:00:00Z"),
                null);

        assertThat(beforeRecord.getRoles()).isEmpty();
    }

    @Test
    void proxyScopeConstraintMustMatchBindingScope() {
        InMemoryEisAuthzStore store = InMemoryEisAuthzStore.builder()
                .principal(new EisAuthzPrincipal(P_AWAY, "person", "away", T_BASE, null, T_BASE, null))
                .principal(new EisAuthzPrincipal(P_AGENT, "person", "agent", T_BASE, null, T_BASE, null))
                .role(new EisAuthzRole(R_APPROVE, "approver", List.of("purchase.request.approve"), T_BASE,
                        null, T_BASE, null))
                .binding(new EisAuthzBinding(UUID.randomUUID(), P_AWAY, R_APPROVE.toString(),
                        scope("{\"department\":\"D002\"}"), T_BASE, null, T_BASE, null))
                .proxy(new EisAuthzProxy(UUID.randomUUID(), P_AWAY, P_AGENT,
                        scope("{\"department\":\"D001\"}"), T_BASE, null, T_BASE, null))
                .build();

        assertThat(new EisAuthzEngine(store).check("agent", "purchase.request.approve", AFTER, null).isAllowed())
                .isFalse();
    }

    @Test
    void warnLevelSodIsReportedNotBlocked() {
        UUID roleId = UUID.randomUUID();
        InMemoryEisAuthzStore store = InMemoryEisAuthzStore.builder()
                .principal(new EisAuthzPrincipal(P_BOTH, "person", "both", T_BASE, null, T_BASE, null))
                .role(new EisAuthzRole(roleId, "stock", List.of("inventory.count", "inventory.adjust"), T_BASE,
                        null, T_BASE, null))
                .binding(new EisAuthzBinding(UUID.randomUUID(), P_BOTH, roleId.toString(),
                        scope("{\"warehouse\":\"W1\"}"), T_BASE, null, T_BASE, null))
                .build();
        EisAuthzEngine warnEngine = new EisAuthzEngine(store);

        EisAuthzDecision decision = warnEngine.check("both", "inventory.count", AFTER, null);
        EisAuthzResolution resolution = warnEngine.resolve("both", AFTER, null, null);

        assertThat(decision.isAllowed()).isTrue();
        assertThat(decision.getWarnings()).containsExactly("SOD-003");
        assertThat(resolution.getSodWarnings()).containsExactly("SOD-003");
    }

    @Test
    void sodWithoutScopeKeyConflictsAcrossAnyScope() {
        UUID roleId = UUID.randomUUID();
        InMemoryEisAuthzStore store = InMemoryEisAuthzStore.builder()
                .principal(new EisAuthzPrincipal(P_BOTH, "person", "both", T_BASE, null, T_BASE, null))
                .role(new EisAuthzRole(roleId, "dual", List.of("x.a", "x.b"), T_BASE, null, T_BASE, null))
                .binding(new EisAuthzBinding(UUID.randomUUID(), P_BOTH, roleId.toString(), scope("{}"),
                        T_BASE, null, T_BASE, null))
                .sodRules(List.of(new EisAuthzSodRule("SOD-X", "无作用域冲突", List.of("x.a", "x.b"), null,
                        "prevent")))
                .build();

        EisAuthzDecision decision = new EisAuthzEngine(store).check("both", "x.a", AFTER, null);

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.getReasonCode()).isEqualTo("SOD-X");
    }

    @Test
    void sodScopeKeyMissingOnBothSidesConflicts() {
        UUID roleId = UUID.randomUUID();
        InMemoryEisAuthzStore store = InMemoryEisAuthzStore.builder()
                .principal(new EisAuthzPrincipal(P_BOTH, "person", "both", T_BASE, null, T_BASE, null))
                .role(new EisAuthzRole(roleId, "dual", List.of("purchase.request.create",
                        "purchase.request.approve"), T_BASE, null, T_BASE, null))
                .binding(new EisAuthzBinding(UUID.randomUUID(), P_BOTH, roleId.toString(), scope("{}"),
                        T_BASE, null, T_BASE, null))
                .build();

        EisAuthzDecision decision = new EisAuthzEngine(store).check("both", "purchase.request.approve", AFTER,
                null);

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.getReasonCode()).isEqualTo("SOD-001");
    }

    @Test
    void samePermissionInDifferentDepartmentsDoesNotConflict() {
        UUID roleApprove = UUID.randomUUID();
        UUID roleCreate = UUID.randomUUID();
        InMemoryEisAuthzStore store = InMemoryEisAuthzStore.builder()
                .principal(new EisAuthzPrincipal(P_BOTH, "person", "both", T_BASE, null, T_BASE, null))
                .role(new EisAuthzRole(roleApprove, "approver", List.of("purchase.request.approve"), T_BASE,
                        null, T_BASE, null))
                .role(new EisAuthzRole(roleCreate, "requester", List.of("purchase.request.create"), T_BASE,
                        null, T_BASE, null))
                .binding(new EisAuthzBinding(UUID.randomUUID(), P_BOTH, roleApprove.toString(),
                        scope("{\"department\":\"D001\"}"), T_BASE, null, T_BASE, null))
                .binding(new EisAuthzBinding(UUID.randomUUID(), P_BOTH, roleCreate.toString(),
                        scope("{\"department\":\"D002\"}"), T_BASE, null, T_BASE, null))
                .build();

        EisAuthzDecision decision = new EisAuthzEngine(store).check("both", "purchase.request.approve", AFTER,
                null);

        assertThat(decision.isAllowed()).isTrue();
    }

    @Test
    void bindingRoleCanReferenceByKey() {
        UUID roleId = UUID.randomUUID();
        InMemoryEisAuthzStore store = InMemoryEisAuthzStore.builder()
                .principal(new EisAuthzPrincipal(P_AWAY, "person", "away", T_BASE, null, T_BASE, null))
                .role(new EisAuthzRole(roleId, "approver", List.of("purchase.request.approve"), T_BASE, null,
                        T_BASE, null))
                .binding(new EisAuthzBinding(UUID.randomUUID(), P_AWAY, "approver", scope("{}"), T_BASE, null,
                        T_BASE, null))
                .build();

        assertThat(new EisAuthzEngine(store).check("away", "purchase.request.approve", AFTER, null).isAllowed())
                .isTrue();
    }

    @Test
    void roleVersionIsSelectedByAsOf() {
        UUID roleId = UUID.randomUUID();
        InMemoryEisAuthzStore store = InMemoryEisAuthzStore.builder()
                .principal(new EisAuthzPrincipal(P_AWAY, "person", "away", T_BASE, null, T_BASE, null))
                .role(new EisAuthzRole(roleId, "approver", List.of("purchase.request.approve"), T_BASE, T_OCT,
                        T_BASE, T_OCT))
                .role(new EisAuthzRole(roleId, "approver", List.of("purchase.request.approve.v2"), T_OCT, null,
                        T_OCT, null))
                .binding(new EisAuthzBinding(UUID.randomUUID(), P_AWAY, roleId.toString(), scope("{}"), T_BASE,
                        null, T_BASE, null))
                .build();
        EisAuthzEngine versioned = new EisAuthzEngine(store);

        // 版本 v1 窗口内（2026-09-30）：旧权限有效
        assertThat(versioned.check("away", "purchase.request.approve", BEFORE, null).isAllowed()).isTrue();
        // 版本 v2 窗口内（2026-10-15）：旧权限失效、新权限生效
        assertThat(versioned.check("away", "purchase.request.approve", AFTER, null).isAllowed()).isFalse();
        assertThat(versioned.check("away", "purchase.request.approve.v2", AFTER, null).isAllowed()).isTrue();
    }

    @Test
    void unknownPrincipalRaisesDedicatedException() {
        assertThatThrownBy(() -> engine.check("nobody", "x", AFTER, null))
                .isInstanceOf(EisAuthzUnknownPrincipalException.class);
    }

    @Test
    void scopeKeysAreExposed() {
        assertThat(EisAuthzEngine.scopeKeys()).containsEntry("same_department", "department")
                .containsEntry("same_organization", "organization")
                .containsEntry("same_warehouse", "warehouse");
    }
}
