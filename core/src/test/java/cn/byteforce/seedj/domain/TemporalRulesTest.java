package cn.byteforce.seedj.domain;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 双时间轴规则：追加前校验四时间字段、半开区间与写入类型。
 */
class TemporalRulesTest {

    private static final UUID ID = UUID.fromString("018f2e3a-1234-7000-8000-000000000001");

    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000002");

    private static final Instant NOW = Instant.parse("2026-11-06T09:00:00Z");

    private static AppendRequest request(AppendKind kind, Instant validFrom, Instant validTo) {
        return new AppendRequest("employee", ID, validFrom, validTo, ACTOR,
                Map.of("employee_no", "E-1"), kind);
    }

    private static TemporalRow current() {
        return new TemporalRow("employee", new ObjectVersion(ID, 1, NOW, null, NOW, null, ACTOR,
                NOW, null, null), Map.of("employee_no", "E-1"));
    }

    @Test
    @DisplayName("版本复核要求 valid_from 与 recorded_at 存在")
    void validateRequiresFourTimestamps() {
        assertThatThrownBy(() -> TemporalRules.validate(null))
                .isInstanceOf(NullPointerException.class);
        assertThatCode(() -> TemporalRules.validate(current().version()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("追加请求的 valid_to 必须晚于 valid_from")
    void appendRequiresHalfOpenValidRange() {
        AppendRequest request = request(AppendKind.CREATE, NOW, NOW);

        assertThatThrownBy(() -> TemporalRules.validateAppend(request, null, NOW))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.TEMPORAL_VIOLATION));
    }

    @Test
    @DisplayName("CREATE 不允许目标已存在，CORRECTION/LATE_FACT 要求目标存在")
    void appendGuardsByKind() {
        assertThatThrownBy(() -> TemporalRules.validateAppend(
                request(AppendKind.CREATE, NOW, null), current(), NOW))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.VERSION_CONFLICT));

        AppendRequest correction = request(AppendKind.CORRECTION, NOW, null);
        assertThatThrownBy(() -> TemporalRules.validateAppend(correction, null, NOW))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.OBJECT_NOT_FOUND));

        AppendRequest lateFact = request(AppendKind.LATE_FACT, NOW.minusSeconds(60), null);
        assertThatThrownBy(() -> TemporalRules.validateAppend(lateFact, null, NOW))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.OBJECT_NOT_FOUND));
        assertThatCode(() -> TemporalRules.validateAppend(lateFact, current(), NOW))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("当前行与请求目标不一致时失败")
    void appendRequiresMatchingCurrentRow() {
        TemporalRow other = new TemporalRow("invoice", new ObjectVersion(ID, 1, NOW, null, NOW,
                null, ACTOR, NOW, null, null), Map.of());

        assertThatThrownBy(() -> TemporalRules.validateAppend(
                request(AppendKind.CORRECTION, NOW, null), other, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("current row does not match request target");
    }

    @Test
    @DisplayName("分页与搜索声明拒绝非法值并归一缺省")
    void pageAndSearchGuards() {
        assertThat(PageRequest.defaults().offset()).isZero();
        assertThat(new PageRequest(3, 20).offset()).isEqualTo(40L);
        assertThat(Page.empty(PageRequest.defaults()).items()).isEmpty();
        assertThatThrownBy(() -> new PageRequest(0, 20))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.INVALID_PAYLOAD));
        assertThatThrownBy(() -> new PageRequest(1, 0))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.INVALID_PAYLOAD));
        assertThatThrownBy(() -> new Page<Integer>(List.of(), -1, 1, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("total must be >= 0");

        TemporalSearch search = TemporalSearch.current("employee");
        assertThat(search.equals()).isEmpty();
        assertThat(search.direction()).isEqualTo(SortDirection.ASC);
        assertThat(search.page()).isEqualTo(PageRequest.defaults());
        assertThatThrownBy(() -> new TemporalSearch(" ", Map.of(), null, null, null, null, null,
                null))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.INVALID_PAYLOAD));
        assertThatThrownBy(() -> new TemporalSearch("employee", Map.of(), null, null, 1L, null,
                null, null))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.reason()).contains("min_version"));
    }
}
