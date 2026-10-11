package cn.byteforce.seedj.starter.eis.authz;

import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzBinding;
import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzDecision;
import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzPrincipal;
import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzRole;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link EisAuthzEngine} 属性条件判定测试（ADR-0004 / ATR-001..007：
 * 新鲜快照求值、主岗 vs 任一岗位、缺失/过期/属主不可达/条件不匹配一律 fail-closed、
 * 未声明条件的调用行为不变）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class EisAuthzEngineAttributesTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final UUID PRINCIPAL = UUID.fromString("01900000-0000-7000-8000-000000000001");

    private static final UUID ROLE = UUID.fromString("01900000-0000-7000-8000-0000000000a1");

    private static final Instant T_BASE = Instant.parse("2026-01-01T00:00:00Z");

    private static final Instant AS_OF = Instant.parse("2026-10-15T12:00:00Z");

    private static final String PERMISSION = "purchase.request.approve";

    private static InMemoryEisAuthzStore fixtures() {
        return InMemoryEisAuthzStore.builder()
                .principal(new EisAuthzPrincipal(PRINCIPAL, "person", "away", T_BASE, null, T_BASE, null))
                .role(new EisAuthzRole(ROLE, "approver", List.of(PERMISSION), T_BASE, null, T_BASE, null))
                .binding(new EisAuthzBinding(UUID.randomUUID(), PRINCIPAL, ROLE.toString(), null, T_BASE, null,
                        T_BASE, null))
                .build();
    }

    private static EisAuthzAttributeSnapshot snapshot(String state, String grade) {
        return EisAuthzAttributeSnapshot.fromJson(MAPPER.readTree("""
                {"principal":"%s","as_of":"2026-10-15T12:00:00Z","recorded_at":"2026-10-15T12:00:00Z",
                 "expires_at":"2026-12-01T00:00:00Z","source":"hr","source_version":"1",
                 "attribute_state":"%s",
                 "snapshot":{"employee_status":"active","employment_type":"full_time",
                   "primary_position":{"position_id":"01900000-0000-7000-8000-0000000000b1","code":"P-1",
                     "title":"Engineer","grade":"%s","org_unit_id":"01900000-0000-7000-8000-0000000000c1",
                     "org_unit_code":"D001","is_primary":true},
                   "positions":[
                     {"position_id":"01900000-0000-7000-8000-0000000000b1","code":"P-1","title":"Engineer",
                      "grade":"%s","org_unit_id":"01900000-0000-7000-8000-0000000000c1","org_unit_code":"D001",
                      "is_primary":true},
                     {"position_id":"01900000-0000-7000-8000-0000000000b2","code":"P-2","title":"Specialist",
                      "grade":"P4","org_unit_id":"01900000-0000-7000-8000-0000000000c2","org_unit_code":"D002",
                      "is_primary":false}
                   ]}}
                """.formatted(PRINCIPAL, state, grade, grade)));
    }

    @Test
    void freshSnapshotEvaluatesPrimaryPositionCondition() {
        EisAuthzAttributeSource source = (ref, asOf, recordedAsOf) -> snapshot("fresh", "P5");
        EisAuthzEngine engine = new EisAuthzEngine(fixtures(), source);

        EisAuthzDecision decision = engine.check("away", PERMISSION, AS_OF, null,
                List.of(EisAuthzAttributeCondition.primaryPosition("grade", "P5")), null);

        assertThat(decision.isAllowed()).isTrue();
        assertThat(decision.getReasonCode()).isEqualTo("ALLOWED");
        assertThat(decision.getAttributeState()).isEqualTo(EisAuthzAttributeState.FRESH);
    }

    @Test
    void anyPositionConditionMatchesAnyPosition() {
        EisAuthzEngine engine = new EisAuthzEngine(fixtures(),
                (ref, asOf, recordedAsOf) -> snapshot("fresh", "P5"));

        EisAuthzDecision decision = engine.check("away", PERMISSION, AS_OF, null,
                List.of(EisAuthzAttributeCondition.anyPosition("grade", "P4")), null);

        assertThat(decision.isAllowed()).isTrue();
        assertThat(decision.getAttributeState()).isEqualTo(EisAuthzAttributeState.FRESH);
    }

    @Test
    void conditionMismatchDeniesWithoutLeakingAttributeValues() {
        EisAuthzEngine engine = new EisAuthzEngine(fixtures(),
                (ref, asOf, recordedAsOf) -> snapshot("fresh", "P5"));

        EisAuthzDecision decision = engine.check("away", PERMISSION, AS_OF, null,
                List.of(EisAuthzAttributeCondition.primaryPosition("grade", "P6")), null);

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.getReasonCode()).isEqualTo("ATTRIBUTE_MISMATCH");
        assertThat(decision.getAttributeState()).isEqualTo(EisAuthzAttributeState.FRESH);
        assertThat(decision.getReason()).contains("primary_position.grade");
        assertThat(decision.getReason()).doesNotContain("P5");
    }

    @Test
    void staleSnapshotFailClosed() {
        EisAuthzEngine engine = new EisAuthzEngine(fixtures(),
                (ref, asOf, recordedAsOf) -> snapshot("stale", "P5"));

        EisAuthzDecision decision = engine.check("away", PERMISSION, AS_OF, null,
                List.of(EisAuthzAttributeCondition.primaryPosition("grade", "P5")), null);

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.getReasonCode()).isEqualTo("ATTRIBUTE_STALE");
        assertThat(decision.getAttributeState()).isEqualTo(EisAuthzAttributeState.STALE);
    }

    @Test
    void missingSnapshotFailClosed() {
        EisAuthzEngine engine = new EisAuthzEngine(fixtures(),
                (ref, asOf, recordedAsOf) -> {
                    throw EisAuthzAttributeSourceException.missing(ref);
                });

        EisAuthzDecision decision = engine.check("away", PERMISSION, AS_OF, null,
                List.of(EisAuthzAttributeCondition.employeeStatus("active")), null);

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.getReasonCode()).isEqualTo("ATTRIBUTE_MISSING");
        assertThat(decision.getAttributeState()).isEqualTo(EisAuthzAttributeState.MISSING);
    }

    @Test
    void sourceUnavailableFailClosed() {
        EisAuthzEngine engine = new EisAuthzEngine(fixtures(),
                (ref, asOf, recordedAsOf) -> {
                    throw EisAuthzAttributeSourceException.unavailable(ref, new IllegalStateException("timeout"));
                });

        EisAuthzDecision decision = engine.check("away", PERMISSION, AS_OF, null,
                List.of(EisAuthzAttributeCondition.employeeStatus("active")), null);

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.getReasonCode()).isEqualTo("ATTRIBUTE_SOURCE_UNAVAILABLE");
        assertThat(decision.getAttributeState()).isEqualTo(EisAuthzAttributeState.SOURCE_UNAVAILABLE);
    }

    @Test
    void unexpectedSourceFailureFailClosed() {
        EisAuthzEngine engine = new EisAuthzEngine(fixtures(), (ref, asOf, recordedAsOf) -> {
            throw new IllegalStateException("boom");
        });

        EisAuthzDecision decision = engine.check("away", PERMISSION, AS_OF, null,
                List.of(EisAuthzAttributeCondition.employeeStatus("active")), null);

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.getReasonCode()).isEqualTo("ATTRIBUTE_SOURCE_UNAVAILABLE");
    }

    @Test
    void noSourceConfiguredFailClosed() {
        EisAuthzEngine engine = new EisAuthzEngine(fixtures());

        EisAuthzDecision decision = engine.check("away", PERMISSION, AS_OF, null,
                List.of(EisAuthzAttributeCondition.employeeStatus("active")), null);

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.getReasonCode()).isEqualTo("ATTRIBUTE_SOURCE_UNAVAILABLE");
        assertThat(decision.getAttributeState()).isEqualTo(EisAuthzAttributeState.SOURCE_UNAVAILABLE);
    }

    @Test
    void providedSnapshotSkipsSourcePull() {
        EisAuthzAttributeSource source = mock(EisAuthzAttributeSource.class);
        EisAuthzEngine engine = new EisAuthzEngine(fixtures(), source);

        EisAuthzDecision decision = engine.check("away", PERMISSION, AS_OF, null,
                List.of(EisAuthzAttributeCondition.primaryPosition("grade", "P5")), snapshot("fresh", "P5"));

        assertThat(decision.isAllowed()).isTrue();
        assertThat(decision.getAttributeState()).isEqualTo(EisAuthzAttributeState.FRESH);
        verify(source, never()).snapshot(any(), any(), any());
    }

    @Test
    void withoutConditionsBehaviorIsUnchanged() {
        EisAuthzAttributeSource source = mock(EisAuthzAttributeSource.class);
        when(source.snapshot(any(), any(), any())).thenReturn(snapshot("fresh", "P5"));
        EisAuthzEngine engine = new EisAuthzEngine(fixtures(), source);

        EisAuthzDecision decision = engine.check("away", PERMISSION, AS_OF, null);

        assertThat(decision.isAllowed()).isTrue();
        assertThat(decision.getReasonCode()).isEqualTo("ALLOWED");
        assertThat(decision.getAttributeState()).isNull();
        verify(source, never()).snapshot(any(), any(), any());
    }

    @Test
    void permissionDeniedSkipsAttributeEvaluation() {
        EisAuthzAttributeSource source = mock(EisAuthzAttributeSource.class);
        EisAuthzEngine engine = new EisAuthzEngine(fixtures(), source);

        EisAuthzDecision decision = engine.check("away", "purchase.request.other", AS_OF, null,
                List.of(EisAuthzAttributeCondition.employeeStatus("active")), null);

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.getReasonCode()).isEqualTo("NO_PERMISSION");
        assertThat(decision.getAttributeState()).isEqualTo(EisAuthzAttributeState.NOT_REQUIRED);
        verify(source, never()).snapshot(any(), any(), any());
    }
}
