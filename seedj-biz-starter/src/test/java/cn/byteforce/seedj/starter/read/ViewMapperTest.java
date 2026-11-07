package cn.byteforce.seedj.starter.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.domain.ObjectVersion;
import cn.byteforce.seedj.domain.TemporalRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 视图映射：元结构、snake_case、类型转换与失败语义。 */
class ViewMapperTest {

    private static final Instant NOW = Instant.parse("2026-11-06T09:00:00Z");

    private static final UUID ID = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");

    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000002");

    private final ViewMapper mapper = new ViewMapper();

    @Test
    @DisplayName("十个元结构组件全部来自版本，业务字段走 snake_case")
    void metadataAndSnakeCaseAreMapped() {
        TemporalRow row = row(Map.of("employee_no", "E-1", "name", "Alice"));

        FullView view = (FullView) mapper.map(row, FullView.class);

        assertThat(view.id()).isEqualTo(ID);
        assertThat(view.version()).isEqualTo(7);
        assertThat(view.validFrom()).isEqualTo(NOW);
        assertThat(view.validTo()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(view.recordedAt()).isEqualTo(NOW);
        assertThat(view.supersededAt()).isEqualTo(NOW.plusSeconds(7200));
        assertThat(view.createdBy()).isEqualTo(ACTOR);
        assertThat(view.createdAt()).isEqualTo(NOW);
        assertThat(view.updatedBy()).isEqualTo(ACTOR);
        assertThat(view.updatedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(view.employeeNo()).isEqualTo("E-1");
        assertThat(view.name()).isEqualTo("Alice");
    }

    @Test
    @DisplayName("字段名已经是 camelCase 时直接命中，不再下钻 snake_case")
    void camelCaseFieldWins() {
        TemporalRow row =
                row(
                        new LinkedHashMap<>(
                                Map.of("employeeNo", "E-direct", "employee_no", "E-snake")));

        SimpleView view = (SimpleView) mapper.map(row, SimpleView.class);

        assertThat(view.employeeNo()).isEqualTo("E-direct");
    }

    @Test
    @DisplayName("缺值保持 null；primitive 缺值直接失败，不静默给 0")
    void missingValuesFailOnlyForPrimitives() {
        TemporalRow row = row(Map.of());

        assertThat(((NullableView) mapper.map(row, NullableView.class)).name()).isNull();
        assertThatThrownBy(() -> mapper.map(row, PrimitiveView.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("count");
    }

    @Test
    @DisplayName("字符串到 Integer/Long/BigDecimal/Boolean/LocalDate/Instant/UUID 的转换")
    void stringConversionsAreSupported() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("count_int", "3");
        fields.put("count_long", "4");
        fields.put("amount", "12.50");
        fields.put("active", "true");
        fields.put("joined", "2026-10-01");
        fields.put("seen_at", "2026-10-01T00:00:00Z");
        fields.put("owner", "018f2e3a-0000-7000-8000-000000000003");
        fields.put("label", 42);

        ConvertedView view = (ConvertedView) mapper.map(row(fields), ConvertedView.class);

        assertThat(view.countInt()).isEqualTo(3);
        assertThat(view.countLong()).isEqualTo(4L);
        assertThat(view.amount()).isEqualByComparingTo("12.50");
        assertThat(view.active()).isTrue();
        assertThat(view.joined()).isEqualTo(LocalDate.parse("2026-10-01"));
        assertThat(view.seenAt()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(view.owner()).isEqualTo(UUID.fromString("018f2e3a-0000-7000-8000-000000000003"));
        assertThat(view.label()).isEqualTo("42");
    }

    @Test
    @DisplayName("数字实例走 Number 分支，不重新解析字符串")
    void numberInstancesAreConvertedDirectly() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("count_int", 5);
        fields.put("count_long", 6L);
        fields.put("amount", new BigDecimal("1.25"));

        NumericView view = (NumericView) mapper.map(row(fields), NumericView.class);

        assertThat(view.countInt()).isEqualTo(5);
        assertThat(view.countLong()).isEqualTo(6L);
        assertThat(view.amount()).isEqualByComparingTo("1.25");
    }

    @Test
    @DisplayName("视图必须是 record；无法转换到目标类型时失败")
    void unsupportedShapesFail() {
        assertThatThrownBy(() -> mapper.map(row(Map.of()), NotARecord.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a record");

        assertThatThrownBy(() -> mapper.map(row(Map.of("value", "1")), UnsupportedView.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot convert");
    }

    @Test
    @DisplayName("null 行或视图类型是编程错误")
    void nullArgumentsFail() {
        assertThatThrownBy(() -> mapper.map(null, SimpleView.class))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> mapper.map(row(Map.of()), null))
                .isInstanceOf(NullPointerException.class);
    }

    private static TemporalRow row(Map<String, Object> fields) {
        return new TemporalRow(
                "employee",
                new ObjectVersion(
                        ID,
                        7,
                        NOW,
                        NOW.plusSeconds(3600),
                        NOW,
                        NOW.plusSeconds(7200),
                        ACTOR,
                        NOW,
                        ACTOR,
                        NOW.plusSeconds(60)),
                fields);
    }

    /** 全字段视图。 */
    record FullView(
            UUID id,
            long version,
            Instant validFrom,
            Instant validTo,
            Instant recordedAt,
            Instant supersededAt,
            UUID createdBy,
            Instant createdAt,
            UUID updatedBy,
            Instant updatedAt,
            String employeeNo,
            String name) {}

    /** 最小视图。 */
    record SimpleView(String employeeNo) {}

    /** 可空字段。 */
    record NullableView(String name) {}

    /** primitive 缺值。 */
    record PrimitiveView(int count) {}

    /** 转换目标。 */
    record ConvertedView(
            int countInt,
            long countLong,
            BigDecimal amount,
            boolean active,
            LocalDate joined,
            Instant seenAt,
            UUID owner,
            String label) {}

    /** 数字转换目标。 */
    record NumericView(int countInt, long countLong, BigDecimal amount) {}

    /** 非 record。 */
    static final class NotARecord {}

    /** 不支持的目标类型。 */
    record UnsupportedView(double value) {}
}
