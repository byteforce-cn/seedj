package cn.byteforce.seedj.infra.jdbc.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.domain.FieldDefinition;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL 对象表 DDL：索引、唯一当前、排他约束、时点函数。 */
@Testcontainers
class TemporalDdlTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("seedj")
                    .withUsername("seedj")
                    .withPassword("seedj");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void initialize() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        new TemporalSchemaCapability(new JdbcMigrationRunner(dataSource)).initialize();
        TemporalTable table =
                new TemporalTable(
                        "probe", "probe_object", new LinkedHashMap<>(Map.of("name", "TEXT")));
        jdbc.execute(TemporalDdl.createTable(table));
        ObjectSchema indexed =
                ObjectSchema.builder("probe_indexed", "probe_indexed")
                        .field("name", FieldType.TEXT)
                        .field(FieldDefinition.of("employee_no", FieldType.TEXT).withIndexed())
                        .build();
        jdbc.execute(TemporalDdl.createTable(TemporalTable.from(indexed)));
    }

    @Test
    void createsBothTemporalIndexesAndCurrentUniqueIndex() {
        List<String> indexes =
                jdbc.queryForList(
                        "SELECT indexname FROM pg_indexes WHERE tablename = 'probe_object'",
                        String.class);
        assertThat(indexes)
                .contains(
                        "uq_probe_object_current",
                        "idx_probe_object_valid_time",
                        "idx_probe_object_recorded_time",
                        "idx_probe_object_bitemporal");
    }

    @Test
    void createsPartialIndexForIndexedField() {
        String definition =
                jdbc.queryForObject(
                        "SELECT indexdef FROM pg_indexes WHERE indexname ="
                                + " 'idx_probe_indexed_employee_no'",
                        String.class);

        assertThat(definition).contains("(employee_no)").contains("WHERE (superseded_at IS NULL)");
    }

    @Test
    void rejectsSecondCurrentRow() {
        String id = "018f2e3a-1234-7000-8000-000000000001";
        insert(id, 1, "2026-10-08T09:00:00Z", null, "2026-10-08T09:00:00Z", null);
        assertThatThrownBy(
                        () ->
                                insert(
                                        id,
                                        2,
                                        "2026-10-08T09:00:00Z",
                                        null,
                                        "2026-10-08T09:00:01Z",
                                        null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsOverlappingValidRanges() {
        String id = "018f2e3a-1234-7000-8000-000000000002";
        insert(id, 1, "2026-10-08T09:00:00Z", null, "2026-10-08T09:00:00Z", "2026-10-08T09:00:02Z");
        assertThatThrownBy(
                        () ->
                                insert(
                                        id,
                                        2,
                                        "2026-10-08T09:00:01Z",
                                        null,
                                        "2026-10-08T09:00:01Z",
                                        null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void createsAsOfQueryFunctions() {
        List<String> functions =
                jdbc.queryForList(
                        "SELECT proname FROM pg_proc WHERE proname IN "
                                + "('probe_object_as_of_valid','probe_object_as_of_recorded',"
                                + "'probe_object_as_of_bitemporal')",
                        String.class);
        assertThat(functions)
                .containsExactlyInAnyOrder(
                        "probe_object_as_of_valid",
                        "probe_object_as_of_recorded",
                        "probe_object_as_of_bitemporal");
    }

    private static void insert(
            String id,
            int version,
            String validFrom,
            String validTo,
            String recordedAt,
            String supersededAt) {
        jdbc.update(
                "INSERT INTO probe_object (id, version, valid_from, valid_to, recorded_at, "
                        + "superseded_at, created_by, created_at, name) VALUES "
                        + "(?::uuid, ?, ?::timestamptz, ?::timestamptz, ?::timestamptz, "
                        + "?::timestamptz, ?::uuid, ?::timestamptz, 'x')",
                id,
                version,
                validFrom,
                validTo,
                recordedAt,
                supersededAt,
                id,
                recordedAt);
    }
}
