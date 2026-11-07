package cn.byteforce.seedj.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.domain.Page;
import cn.byteforce.seedj.domain.PageRequest;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.domain.SortDirection;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.domain.TemporalSearch;
import cn.byteforce.seedj.domain.TemporalStore;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import cn.byteforce.seedj.infra.jdbc.domain.PostgresTemporalStore;
import cn.byteforce.seedj.infra.jdbc.domain.TemporalDdl;
import cn.byteforce.seedj.infra.jdbc.domain.TemporalSchemaCapability;
import cn.byteforce.seedj.infra.jdbc.domain.TemporalTable;
import cn.byteforce.seedj.infra.jdbc.domain.TemporalTables;
import cn.byteforce.seedj.server.hr.employee.HrEmployeeFields;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 当前视图快路径基准：10 万对象 + 10 万历史版本，列表 p95 必须低于 200ms。
 *
 * <p>默认不跑（{@code @Tag("perf")} 被 surefire 排除）；发布门禁使用 {@code mvn -Pperf
 * -Dtest=TemporalCurrentViewPerformanceTest test}。
 */
@Tag("perf")
@Testcontainers
class TemporalCurrentViewPerformanceTest {

    private static final int OBJECTS = 100_000;

    private static final Instant T0 = Instant.parse("2026-11-07T09:00:00Z");

    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("seedj")
                    .withUsername("seedj")
                    .withPassword("seedj");

    private static JdbcTemplate jdbc;

    private static TemporalStore store;

    @BeforeAll
    static void initialize() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        JdbcMigrationRunner runner = new JdbcMigrationRunner(dataSource);
        new TemporalSchemaCapability(runner).initialize();
        TemporalTable table = TemporalTable.from(HrEmployeeFields.schema());
        jdbc.execute(TemporalDdl.createTable(table));
        seedHistory(table);
        store = new PostgresTemporalStore(jdbc, () -> T0, new TemporalTables(List.of(table)));
    }

    private static void seedHistory(TemporalTable table) {
        String sql =
                "INSERT INTO "
                        + table.tableName()
                        + " ("
                        + "id, version, valid_from, valid_to, recorded_at, superseded_at,"
                        + " created_by, created_at, updated_by, updated_at,"
                        + " employee_no, name, status, hire_date) VALUES"
                        + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        for (int base = 0; base < OBJECTS; base += 1_000) {
            int size = Math.min(1_000, OBJECTS - base);
            List<Row> rows = new ArrayList<>(size * 2);
            for (int i = base; i < base + size; i++) {
                UUID id = UUID.randomUUID();
                String no = "EMP-%06d".formatted(i);
                rows.add(new Row(id, 1, T0.minusSeconds(60), T0, no, "历史 " + i));
                rows.add(new Row(id, 1, T0, null, no, "当前 " + i));
            }
            jdbc.batchUpdate(
                    sql,
                    new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                        @Override
                        public void setValues(PreparedStatement ps, int index) throws SQLException {
                            Row row = rows.get(index);
                            ps.setObject(1, row.id());
                            ps.setLong(2, row.version());
                            ps.setTimestamp(3, Timestamp.from(row.validFrom()));
                            ps.setTimestamp(
                                    4,
                                    row.validTo() == null ? null : Timestamp.from(row.validTo()));
                            ps.setTimestamp(5, Timestamp.from(row.recordedAt()));
                            ps.setTimestamp(
                                    6,
                                    row.supersededAt() == null
                                            ? null
                                            : Timestamp.from(row.supersededAt()));
                            ps.setObject(7, ACTOR);
                            ps.setTimestamp(8, Timestamp.from(T0));
                            ps.setObject(9, null);
                            ps.setTimestamp(10, null);
                            ps.setString(11, row.employeeNo());
                            ps.setString(12, row.name());
                            ps.setString(13, "active");
                            ps.setDate(14, java.sql.Date.valueOf(LocalDate.of(2026, 1, 1)));
                        }

                        @Override
                        public int getBatchSize() {
                            return rows.size();
                        }
                    });
        }
    }

    @Test
    @DisplayName("10 万对象当前视图列表 p95 < 200ms，EXPLAIN 输出快路径")
    void currentViewP95IsBelowBudget() {
        long[] elapsed = new long[20];
        for (int i = 0; i < elapsed.length + 3; i++) {
            long started = System.nanoTime();
            Page<TemporalRow> page =
                    store.search(
                            new TemporalSearch(
                                    "employee",
                                    Map.of(),
                                    List.of(),
                                    null,
                                    null,
                                    null,
                                    "employee_no",
                                    SortDirection.ASC,
                                    new PageRequest(1, 20),
                                    null),
                            ScopePredicate.all());
            long nanos = System.nanoTime() - started;
            assertThat(page.items()).hasSize(20);
            if (i >= 3) {
                elapsed[i - 3] = nanos;
            }
        }
        Arrays.sort(elapsed);
        long p95 = elapsed[(int) Math.ceil(elapsed.length * 0.95) - 1];
        System.out.printf(
                "current-view p50=%dms p95=%dms%n",
                elapsed[elapsed.length / 2] / 1_000_000, p95 / 1_000_000);
        for (String line :
                jdbc.queryForList(
                        "EXPLAIN (FORMAT TEXT) SELECT * FROM hr_employee"
                                + " WHERE superseded_at IS NULL ORDER BY employee_no, id LIMIT 20",
                        String.class)) {
            System.out.println("EXPLAIN: " + line);
        }
        assertThat(p95).isLessThan(200_000_000L);
    }

    private record Row(
            UUID id,
            long version,
            Instant validFrom,
            Instant validTo,
            Instant recordedAt,
            Instant supersededAt,
            String employeeNo,
            String name) {

        Row(
                UUID id,
                long version,
                Instant validFrom,
                Instant supersededAt,
                String employeeNo,
                String name) {
            this(
                    id,
                    version,
                    validFrom,
                    null,
                    supersededAt == null ? T0 : T0.minusSeconds(120),
                    supersededAt,
                    employeeNo,
                    name);
        }
    }
}
