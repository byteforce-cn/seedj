package cn.byteforce.seedj.iam.authz;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 代理绑定窗口边界与 SoD 作用域缺失键的处理。
 */
class IamProxyEdgeCaseTest {

    private static final Instant VALID_FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant VALID_TO = Instant.parse("2026-02-01T00:00:00Z");
    private static final Instant RECORDED_AT = Instant.parse("2026-01-02T00:00:00Z");

    private static IamProxy proxy() {
        return new IamProxy("p-1", "alice", "agent-1", Map.of("department", "D001"),
                VALID_FROM, VALID_TO, RECORDED_AT, null);
    }

    @Test
    @DisplayName("业务窗口右端点为开区间")
    void businessWindowIsHalfOpen() {
        assertThat(proxy().effectiveAt(VALID_TO.minusSeconds(1), null)).isTrue();
        assertThat(proxy().effectiveAt(VALID_TO, null)).isFalse();
        assertThat(proxy().effectiveAt(VALID_TO.plusSeconds(1), null)).isFalse();
    }

    @Test
    @DisplayName("系统时点早于记录时间时不可见")
    void recordedTimeBeforeRecordingIsNotVisible() {
        assertThat(proxy().effectiveAt(VALID_FROM, RECORDED_AT.minusSeconds(1))).isFalse();
        assertThat(proxy().effectiveAt(VALID_FROM, RECORDED_AT)).isTrue();
        assertThat(proxy().effectiveAt(VALID_FROM, null)).isTrue();
    }

    @Test
    @DisplayName("已封口版本仅在其封口时刻之前可见")
    void supersededProxyIsHiddenAfterSealing() {
        Instant supersededAt = Instant.parse("2026-01-10T00:00:00Z");
        IamProxy sealed = new IamProxy("p-1", "alice", "agent-1", null, VALID_FROM, null,
                RECORDED_AT, supersededAt);

        assertThat(sealed.scope()).isEmpty();
        assertThat(sealed.effectiveAt(VALID_FROM, RECORDED_AT)).isTrue();
        assertThat(sealed.effectiveAt(VALID_FROM, supersededAt)).isFalse();
        assertThat(sealed.effectiveAt(VALID_FROM, null)).isFalse();
    }

    @Test
    @DisplayName("asOf 必填")
    void asOfIsRequired() {
        assertThatThrownBy(() -> proxy().effectiveAt(null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("asOf");
    }

    @Test
    @DisplayName("SoD：作用域键缺失或空白不参与比较")
    void sodIgnoresMissingScopeKeys() {
        SodRule rule = new SodRule("SOD-001", "采购申请与审批分离",
                List.of("purchase.request.create", "purchase.request.approve"),
                SodScope.SAME_DEPARTMENT, SodEnforcement.PREVENT);

        List<SodFinding> noKey = SodEvaluator.findings(List.of(rule), Map.of(
                "purchase.request.create", List.of(Map.of("organization", "O001")),
                "purchase.request.approve", List.of(Map.of("organization", "O001"))));
        List<SodFinding> blankKey = SodEvaluator.findings(List.of(rule), Map.of(
                "purchase.request.create", List.of(Map.of("department", " ")),
                "purchase.request.approve", List.of(Map.of("department", " "))));

        assertThat(noKey).isEmpty();
        assertThat(blankKey).isEmpty();
    }

    @Test
    @DisplayName("SoD：任一冲突权限无授予时不冲突")
    void sodRequiresScopesForEveryConflictingPermission() {
        SodRule rule = new SodRule("SOD-002", "付款与审批分离",
                List.of("payment.create", "payment.approve"),
                SodScope.SAME_ORGANIZATION, SodEnforcement.PREVENT);

        assertThat(SodEvaluator.findings(List.of(rule), Map.of(
                "payment.create", List.of(Map.of("organization", "O001"))))).isEmpty();
        assertThat(SodEvaluator.findings(List.of(rule), Map.of(
                "payment.create", List.of(),
                "payment.approve", List.of(Map.of("organization", "O001"))))).isEmpty();
    }
}
