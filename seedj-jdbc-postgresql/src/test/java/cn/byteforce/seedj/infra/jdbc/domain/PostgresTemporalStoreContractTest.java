package cn.byteforce.seedj.infra.jdbc.domain;

import cn.byteforce.seedj.core.clock.TestClock;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.AppendResult;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.domain.TemporalQuery;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.domain.TemporalStore;
import cn.byteforce.seedj.domain.TemporalStoreContractTest;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PostgreSQL 实现跑公共契约套件（真实数据库 + 真实约束）。
 */
@Testcontainers
class PostgresTemporalStoreContractTest extends TemporalStoreContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("seedj")
                    .withUsername("seedj")
                    .withPassword("seedj");

    private static JdbcTemplate jdbc;

    private static TestClock clock;

    private static TemporalTable typedTable;

    private static TemporalStore store;

    @BeforeAll
    static void initialize() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        new TemporalSchemaCapability(new JdbcMigrationRunner(dataSource)).initialize();
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("name", "TEXT");
        columns.put("status", "TEXT");
        columns.put("secret", "TEXT");
        columns.put("hire_date", "DATE");
        TemporalTable table = new TemporalTable("probe", "probe_object", columns, Set.of(),
                Set.of("name", "status", "hire_date"),
                Map.of("name", FieldType.TEXT, "status", FieldType.TEXT,
                        "secret", FieldType.TEXT, "hire_date", FieldType.DATE),
                Set.of("name"), Set.of());
        ObjectSchema typedSchema = ObjectSchema.builder("probe_typed", "probe_typed_object")
                .field("name", FieldType.TEXT)
                .field("hire_date", FieldType.DATE)
                .field("ref_id", FieldType.UUID)
                .build();
        typedTable = TemporalTable.from(typedSchema);
        jdbc.execute(TemporalDdl.createTable(table));
        jdbc.execute(TemporalDdl.createTable(typedTable));
        clock = new TestClock(T0);
        store = new PostgresTemporalStore(jdbc, clock,
                new TemporalTables(List.of(table, typedTable)));
    }

    @Override
    protected TemporalStore store() {
        return store;
    }

    @Override
    protected TestClock clock() {
        return clock;
    }

    @Override
    protected void reset() {
        jdbc.execute("TRUNCATE probe_object");
        jdbc.execute("TRUNCATE probe_typed_object");
    }

    @Test
    void dateAndUuidFieldsRoundTripThroughJdbc() {
        LocalDate hireDate = LocalDate.parse("2026-10-01");
        UUID refId = UUID.fromString("018f2e3a-9999-7000-8000-000000000001");

        AppendResult result = store.append(AppendRequest.create("probe_typed", ID, T0, null,
                ACTOR, Map.of("name", "Alice", "hire_date", hireDate, "ref_id", refId)));
        TemporalRow row = store.find("probe_typed", ID, TemporalQuery.current(), ScopePredicate.all()).orElseThrow();

        assertThat(result.appended().version().version()).isEqualTo(1L);
        assertThat(row.fields())
                .containsEntry("hire_date", hireDate)
                .containsEntry("ref_id", refId);
        assertThat(row.fields().get("hire_date"))
                .isInstanceOf(LocalDate.class);
    }

    @Test
    void unknownObjectTypeIsRejectedClearly() {
        assertThatThrownBy(() -> store.find("missing", ID, TemporalQuery.current(), ScopePredicate.all()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
    }
}
