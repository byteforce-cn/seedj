package cn.byteforce.seedj.iam.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 权威属性快照：微秒截断、必填字段与相对请求时点的 fresh/stale 判定。 */
class AttributeSnapshotTest {

    private static final Instant AS_OF = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant RECORDED_AT = Instant.parse("2026-01-01T00:00:05Z");

    @Test
    @DisplayName("构造：时间截断到微秒，快照归一化为不可变 map")
    void normalizesFields() {
        AttributeSnapshot snapshot =
                new AttributeSnapshot(
                        "alice",
                        Instant.parse("2026-01-01T00:00:00.123456789Z"),
                        Instant.parse("2026-01-01T00:00:05.987654321Z"),
                        Instant.parse("2026-01-02T00:00:00.111222333Z"),
                        "hr",
                        "v1",
                        null);

        assertThat(snapshot.principal()).isEqualTo("alice");
        assertThat(snapshot.asOf()).isEqualTo(Instant.parse("2026-01-01T00:00:00.123456Z"));
        assertThat(snapshot.recordedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:05.987654Z"));
        assertThat(snapshot.expiresAt()).isEqualTo(Instant.parse("2026-01-02T00:00:00.111222Z"));
        assertThat(snapshot.source()).isEqualTo("hr");
        assertThat(snapshot.sourceVersion()).isEqualTo("v1");
        assertThat(snapshot.snapshot()).isEmpty();

        AttributeSnapshot withAttributes =
                new AttributeSnapshot(
                        "alice",
                        AS_OF,
                        RECORDED_AT,
                        null,
                        "hr",
                        null,
                        Map.of("department", "D001"));
        assertThat(withAttributes.snapshot()).containsEntry("department", "D001");
        assertThat(withAttributes.expiresAt()).isNull();
        assertThat(withAttributes.sourceVersion()).isNull();
        assertThatThrownBy(() -> withAttributes.snapshot().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("stateAt：无过期时间为 FRESH，请求时点早于过期时间才 FRESH")
    void stateAt() {
        AttributeSnapshot neverExpires =
                new AttributeSnapshot("alice", AS_OF, RECORDED_AT, null, "hr", "v1", Map.of());

        assertThat(neverExpires.stateAt(AS_OF.plusSeconds(3600))).isEqualTo(AttributeState.FRESH);

        AttributeSnapshot expires =
                new AttributeSnapshot(
                        "alice",
                        AS_OF,
                        RECORDED_AT,
                        Instant.parse("2026-01-01T01:00:00Z"),
                        "hr",
                        "v1",
                        Map.of());

        assertThat(expires.stateAt(Instant.parse("2026-01-01T00:59:59Z")))
                .isEqualTo(AttributeState.FRESH);
        assertThat(expires.stateAt(Instant.parse("2026-01-01T01:00:00Z")))
                .isEqualTo(AttributeState.STALE);
        assertThat(expires.stateAt(Instant.parse("2026-01-01T02:00:00Z")))
                .isEqualTo(AttributeState.STALE);
    }

    @Test
    @DisplayName("构造：principal/source/asOf/recordedAt 必填")
    void requiresMandatoryFields() {
        assertThatThrownBy(
                        () ->
                                new AttributeSnapshot(
                                        null, AS_OF, RECORDED_AT, null, "hr", "v1", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("principal");
        assertThatThrownBy(
                        () ->
                                new AttributeSnapshot(
                                        " ", AS_OF, RECORDED_AT, null, "hr", "v1", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new AttributeSnapshot(
                                        "alice", null, RECORDED_AT, null, "hr", "v1", Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () ->
                                new AttributeSnapshot(
                                        "alice", AS_OF, null, null, "hr", "v1", Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () ->
                                new AttributeSnapshot(
                                        "alice", AS_OF, RECORDED_AT, null, null, "v1", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source");
    }

    @Test
    @DisplayName("AttributeState：线格式名称全小写")
    void attributeStateWireNames() {
        assertThat(AttributeState.FRESH.wireName()).isEqualTo("fresh");
        assertThat(AttributeState.STALE.wireName()).isEqualTo("stale");
        assertThat(AttributeState.MISSING.wireName()).isEqualTo("missing");
        assertThat(AttributeState.SOURCE_UNAVAILABLE.wireName()).isEqualTo("source_unavailable");
        assertThat(AttributeState.NOT_REQUIRED.wireName()).isEqualTo("not_required");
    }
}
