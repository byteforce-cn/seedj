package cn.byteforce.seedj.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 列表查询参数：游标/排序一致性、minVersion 与默认值。 */
class TemporalSearchTest {

    @Test
    @DisplayName("默认值：空过滤器、升序、默认分页")
    void defaults() {
        TemporalSearch search =
                new TemporalSearch("probe", null, null, null, null, null, null, null, null, null);

        assertThat(search.equals()).isEmpty();
        assertThat(search.filters()).isEmpty();
        assertThat(search.direction()).isEqualTo(SortDirection.ASC);
        assertThat(search.page()).isEqualTo(PageRequest.defaults());
    }

    @Test
    @DisplayName("minVersion 不适用于列表")
    void minVersionIsRejected() {
        assertThatThrownBy(
                        () ->
                                new TemporalSearch(
                                        "probe", Map.of(), List.of(), null, null, 1L, null, null,
                                        null, null))
                .isInstanceOf(ContractException.class)
                .hasMessageContaining("min_version");
    }

    @Test
    @DisplayName("对象类型必填")
    void objectTypeIsRequired() {
        assertThatThrownBy(
                        () ->
                                new TemporalSearch(
                                        " ", Map.of(), null, null, null, null, null, null, null,
                                        null))
                .isInstanceOf(ContractException.class);
    }

    @Test
    @DisplayName("游标排序字段必须与 sort 一致")
    void cursorMustMatchSort() {
        PageCursor cursor = new PageCursor("name", "a", UUID.randomUUID());

        assertThatThrownBy(
                        () ->
                                new TemporalSearch(
                                        "probe",
                                        Map.of(),
                                        List.of(),
                                        null,
                                        null,
                                        null,
                                        "status",
                                        SortDirection.ASC,
                                        null,
                                        cursor))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error ->
                                assertThat(error.code())
                                        .isEqualTo(ContractErrorCode.INVALID_PAYLOAD));
        assertThatThrownBy(
                        () ->
                                new TemporalSearch(
                                        "probe",
                                        Map.of(),
                                        List.of(),
                                        null,
                                        null,
                                        null,
                                        "name",
                                        SortDirection.ASC,
                                        null,
                                        new PageCursor(null, null, UUID.randomUUID())))
                .isInstanceOf(ContractException.class);
    }

    @Test
    @DisplayName("current 工厂返回当前时点查询")
    void currentFactory() {
        TemporalSearch search = TemporalSearch.current("probe");
        assertThat(search.asOf()).isNull();
        assertThat(search.recordedAsOf()).isNull();
    }

    @Test
    @DisplayName("游标与排序匹配时通过")
    void matchingCursorPasses() {
        PageCursor cursor = new PageCursor("name", "a", UUID.randomUUID());
        TemporalSearch search =
                new TemporalSearch(
                        "probe",
                        Map.of(),
                        List.of(),
                        null,
                        null,
                        null,
                        "name",
                        SortDirection.ASC,
                        null,
                        cursor);

        assertThat(search.cursor()).isEqualTo(cursor);
        assertThat(search.filters()).isEmpty();
    }

    @Test
    @DisplayName("时点保留")
    void timestampsAreKept() {
        Instant at = Instant.parse("2026-11-07T09:00:00Z");
        TemporalSearch search =
                new TemporalSearch(
                        "probe", Map.of(), List.of(), at, at, null, null, null, null, null);

        assertThat(search.asOf()).isEqualTo(at);
        assertThat(search.recordedAsOf()).isEqualTo(at);
    }
}
