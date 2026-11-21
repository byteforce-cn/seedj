package cn.byteforce.seedj.iam.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 授权/代理绑定的双时间轴语义：业务时点决定“是否生效”，系统时点决定“当时是否已知”。 两个记录的时间语义必须一致，否则审计回放会得出不同结论。 */
class IamBindingAndProxyTest {

    private static final Instant VALID_FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant VALID_TO = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant RECORDED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant SUPERSEDED_AT = Instant.parse("2026-03-01T00:00:00Z");

    @Test
    void businessWindowIsHalfOpen() {
        assertThat(
                        binding(VALID_TO, RECORDED_AT, null)
                                .effectiveAt(VALID_FROM.minusSeconds(1), null))
                .isFalse();
        assertThat(binding(VALID_TO, RECORDED_AT, null).effectiveAt(VALID_FROM, null)).isTrue();
        assertThat(binding(VALID_TO, RECORDED_AT, null).effectiveAt(VALID_TO.minusSeconds(1), null))
                .isTrue();
        assertThat(binding(VALID_TO, RECORDED_AT, null).effectiveAt(VALID_TO, null)).isFalse();
    }

    @Test
    void openEndedBindingStaysEffective() {
        assertThat(
                        binding(null, RECORDED_AT, null)
                                .effectiveAt(Instant.parse("2030-01-01T00:00:00Z"), null))
                .isTrue();
    }

    @Test
    void currentKnowledgeIgnoresSupersededRows() {
        assertThat(binding(VALID_TO, RECORDED_AT, SUPERSEDED_AT).effectiveAt(VALID_FROM, null))
                .isFalse();
        assertThat(binding(VALID_TO, RECORDED_AT, null).effectiveAt(VALID_FROM, null)).isTrue();
    }

    @Test
    void recordedTimeKnowledgeUsesRecordAndSupersedeBoundaries() {
        IamBinding current = binding(VALID_TO, RECORDED_AT, null);

        assertThat(current.effectiveAt(VALID_FROM, RECORDED_AT.minusSeconds(1))).isFalse();
        assertThat(current.effectiveAt(VALID_FROM, RECORDED_AT)).isTrue();

        IamBinding superseded = binding(VALID_TO, RECORDED_AT, SUPERSEDED_AT);

        assertThat(superseded.effectiveAt(VALID_FROM, SUPERSEDED_AT.minusSeconds(1))).isTrue();
        assertThat(superseded.effectiveAt(VALID_FROM, SUPERSEDED_AT)).isFalse();
    }

    @Test
    void businessWindowWinsOverRecordedTime() {
        IamBinding binding = binding(VALID_TO, RECORDED_AT, null);

        assertThat(binding.effectiveAt(VALID_TO.plusSeconds(1), RECORDED_AT)).isFalse();
    }

    @Test
    void proxyFollowsTheSameTimeline() {
        IamProxy proxy = proxy(VALID_TO, RECORDED_AT, SUPERSEDED_AT);

        assertThat(proxy.effectiveAt(VALID_FROM.minusSeconds(1), null)).isFalse();
        assertThat(proxy.effectiveAt(VALID_FROM, null)).isFalse();
        assertThat(proxy.effectiveAt(VALID_FROM, SUPERSEDED_AT.minusSeconds(1))).isTrue();
        assertThat(proxy.effectiveAt(VALID_FROM, SUPERSEDED_AT)).isFalse();
    }

    @Test
    void bothRecordsApplyTheSameTruthTable() {
        List<Boolean> expectations = List.of(false, false, true, false);

        assertThat(timeline(binding(VALID_TO, RECORDED_AT, SUPERSEDED_AT))).isEqualTo(expectations);
        assertThat(timeline(proxy(VALID_TO, RECORDED_AT, SUPERSEDED_AT))).isEqualTo(expectations);
    }

    @Test
    void rejectsInvertedOrEmptyWindows() {
        assertThatThrownBy(() -> binding(VALID_FROM, RECORDED_AT, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("validTo must be after validFrom");
        assertThatThrownBy(() -> proxy(VALID_FROM, RECORDED_AT, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("validTo must be after validFrom");
    }

    @Test
    void rejectsBlankIdentifiers() {
        assertThatThrownBy(
                        () ->
                                new IamBinding(
                                        " ",
                                        "alice",
                                        "role",
                                        Map.of(),
                                        VALID_FROM,
                                        null,
                                        RECORDED_AT,
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new IamBinding(
                                        "b1",
                                        "alice",
                                        "",
                                        Map.of(),
                                        VALID_FROM,
                                        null,
                                        RECORDED_AT,
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new IamProxy(
                                        "p1",
                                        "",
                                        "agent",
                                        Map.of(),
                                        VALID_FROM,
                                        null,
                                        RECORDED_AT,
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new IamProxy(
                                        "p1",
                                        "alice",
                                        " ",
                                        Map.of(),
                                        VALID_FROM,
                                        null,
                                        RECORDED_AT,
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultsScopeToEmptyAndTruncatesToMicros() {
        Instant messy = Instant.parse("2026-01-01T00:00:00.123456789Z");
        IamBinding binding = new IamBinding("b1", "alice", "role", null, messy, null, messy, null);

        assertThat(binding.scope()).isEmpty();
        assertThat(binding.validFrom()).isEqualTo(messy.truncatedTo(ChronoUnit.MICROS));
        assertThat(binding.effectiveAt(messy, null)).isTrue();
    }

    private static List<Boolean> timeline(IamBinding binding) {
        return List.of(
                binding.effectiveAt(VALID_FROM, null),
                binding.effectiveAt(VALID_FROM, RECORDED_AT.minusSeconds(1)),
                binding.effectiveAt(VALID_FROM, SUPERSEDED_AT.minusSeconds(1)),
                binding.effectiveAt(VALID_FROM, SUPERSEDED_AT));
    }

    private static List<Boolean> timeline(IamProxy proxy) {
        return List.of(
                proxy.effectiveAt(VALID_FROM, null),
                proxy.effectiveAt(VALID_FROM, RECORDED_AT.minusSeconds(1)),
                proxy.effectiveAt(VALID_FROM, SUPERSEDED_AT.minusSeconds(1)),
                proxy.effectiveAt(VALID_FROM, SUPERSEDED_AT));
    }

    private static IamBinding binding(Instant validTo, Instant recordedAt, Instant supersededAt) {
        return new IamBinding(
                "b1",
                "alice",
                "role-sales",
                Map.of("region", "cn"),
                VALID_FROM,
                validTo,
                recordedAt,
                supersededAt);
    }

    private static IamProxy proxy(Instant validTo, Instant recordedAt, Instant supersededAt) {
        return new IamProxy(
                "p1",
                "alice",
                "agent-bob",
                Map.of("region", "cn"),
                VALID_FROM,
                validTo,
                recordedAt,
                supersededAt);
    }
}
