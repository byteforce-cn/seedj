package cn.byteforce.seedj.domain;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 追加请求：时间统一微秒、字段不可变，写入类型决定必需的目标状态。
 */
class AppendRequestTest {

    private static final UUID ID = UUID.fromString("018f2e3a-1234-7000-8000-000000000001");

    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000002");

    private static final Instant NOW = Instant.parse("2026-11-06T09:00:00.123456789Z");

    @Test
    @DisplayName("创建请求把时间截断到微秒并冻结字段")
    void createTruncatesAndFreezesFields() {
        AppendRequest request = AppendRequest.create("employee", ID, NOW, null, ACTOR,
                Map.of("employee_no", "E-1"));

        assertThat(request.objectType()).isEqualTo("employee");
        assertThat(request.validFrom().getNano() % 1000).isZero();
        assertThat(request.validTo()).isNull();
        assertThat(request.kind()).isEqualTo(AppendKind.CREATE);
        assertThatThrownBy(() -> request.fields().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("objectType 不可为空白")
    void rejectsBlankObjectType() {
        assertThatThrownBy(() -> new AppendRequest(" ", ID, NOW, null, ACTOR, Map.of(),
                AppendKind.CREATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("objectType must not be blank");
    }

    @Test
    @DisplayName("validTo 同样截断到微秒")
    void truncatesValidTo() {
        AppendRequest request = new AppendRequest("employee", ID, NOW, NOW.plusSeconds(60),
                ACTOR, Map.of(), AppendKind.CORRECTION);

        assertThat(request.validTo().getNano() % 1000).isZero();
    }

    @Test
    @DisplayName("TemporalRow 拒绝空白对象类型")
    void temporalRowRejectsBlankObjectType() {
        ObjectVersion version = new ObjectVersion(ID, 1, NOW, null, NOW, null, ACTOR, NOW, null,
                null);

        assertThatThrownBy(() -> new TemporalRow(" ", version, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("objectType must not be blank");
    }

    @Test
    @DisplayName("AppendResult 把 null superseded 归一化为 empty")
    void appendResultNormalisesNullSuperseded() {
        ObjectVersion version = new ObjectVersion(ID, 1, NOW, null, NOW, null, ACTOR, NOW, null,
                null);
        TemporalRow row = new TemporalRow("employee", version, Map.of("employee_no", "E-1"));

        assertThat(new AppendResult(row, null).superseded()).isEmpty();
        assertThat(new AppendResult(row, java.util.Optional.of(row)).superseded()).contains(row);
    }

    @Test
    @DisplayName("ObjectVersion 版本号必须 >= 1，superseded_at 必须晚于 recorded_at")
    void objectVersionGuards() {
        assertThatThrownBy(() -> new ObjectVersion(ID, 0, NOW, null, NOW, null, ACTOR, NOW, null,
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version must be >= 1");
        assertThatThrownBy(() -> new ObjectVersion(ID, 1, NOW, null, NOW, NOW, ACTOR, NOW, null,
                null))
                .isInstanceOfSatisfying(ContractException.class, error -> {
                    assertThat(error.code()).isEqualTo(ContractErrorCode.TEMPORAL_VIOLATION);
                    assertThat(error.reason()).contains("superseded_at must be after recorded_at");
                });
    }
}
