package cn.byteforce.seedj.server.hr.employee;

import cn.byteforce.seedj.core.Migration;
import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.domain.TemporalQuery;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.domain.TemporalStore;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import cn.byteforce.seedj.infra.jdbc.domain.PostgresTemporalStore;
import cn.byteforce.seedj.infra.jdbc.domain.TemporalSchemaCapability;
import cn.byteforce.seedj.infra.jdbc.domain.TemporalTable;
import cn.byteforce.seedj.infra.jdbc.domain.TemporalTables;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1 → V2 升级证据：加字段只新增迁移，旧行、双时间轴与写入都不受损。
 *
 * <p>场景刻意按发布顺序执行：先只跑 V1 并写一行（升级前的库），再跑签入的 V2 候选
 * {@code ADD COLUMN}，然后用升级后的表映射读回旧行、写新版本、按记录时点读回 v1。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@Testcontainers
class HrEmployeeUpgradeMigrationTest {

    private static final Instant T0 = Instant.parse("2026-11-06T09:00:00Z");

    private static final UUID ACTOR = UUID.fromString("018f2e3a-1234-7000-8000-000000000001");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("seedj")
                    .withUsername("seedj")
                    .withPassword("seedj");

    private static final AtomicReference<Instant> NOW = new AtomicReference<>(T0);

    private static final Clock CLOCK = NOW::get;

    private static JdbcTemplate jdbc;

    private static JdbcMigrationRunner runner;

    private static TemporalStore v1Store;

    private static TemporalStore v2Store;

    @BeforeAll
    static void initialize() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        runner = new JdbcMigrationRunner(dataSource);
        new TemporalSchemaCapability(runner).initialize();

        ObjectSchema v1 = legacySchema();
        ObjectSchema v2 = withBadgeNo(v1);
        v1Store = store(v1);
        v2Store = store(v2);
        runner.migrate(HrEmployeeSchemaCapability.NAME, List.of(v1Migration()));
    }

    @Test
    @DisplayName("V1 已有数据在 V2 加列后仍可读，新列可写，按记录时点可读回旧版本")
    void upgradeKeepsHistoryAndAcceptsNewColumn() {
        UUID id = UUID.randomUUID();
        v1Store.append(AppendRequest.create("employee", id, T0, null, ACTOR, fields(null)));

        TemporalRow before = v1Store.find("employee", id, TemporalQuery.current(),
                ScopePredicate.all()).orElseThrow();
        assertThat(before.version().version()).isEqualTo(1);
        assertThat(before.fields()).doesNotContainKey("badge_no");

        runner.migrate(HrEmployeeSchemaCapability.NAME, List.of(v1Migration(), v2Migration()));

        TemporalRow upgraded = v2Store.find("employee", id, TemporalQuery.current(),
                ScopePredicate.all()).orElseThrow();
        assertThat(upgraded.version().version()).isEqualTo(1);
        assertThat(upgraded.fields())
                .containsEntry("employee_no", "E-1")
                .containsEntry("name", "张三")
                .doesNotContainKey("badge_no");

        NOW.set(T0.plusSeconds(5));
        v2Store.append(AppendRequest.correction("employee", id, T0, null, ACTOR,
                fields("B-42")));

        TemporalRow current = v2Store.find("employee", id, TemporalQuery.current(),
                ScopePredicate.all()).orElseThrow();
        assertThat(current.version().version()).isEqualTo(2);
        assertThat(current.fields()).containsEntry("badge_no", "B-42");

        TemporalRow historical = v2Store.find("employee", id,
                new TemporalQuery(null, T0.plusSeconds(1), null), ScopePredicate.all())
                .orElseThrow();
        assertThat(historical.version().version()).isEqualTo(1);
        assertThat(historical.fields()).doesNotContainKey("badge_no");

        assertThat(jdbc.queryForList(
                "SELECT version FROM seedj_schema_history WHERE scope = ? ORDER BY version",
                Integer.class, HrEmployeeSchemaCapability.NAME))
                .containsExactly(1, 2);
    }

    private static TemporalStore store(ObjectSchema schema) {
        return new PostgresTemporalStore(jdbc, CLOCK,
                new TemporalTables(List.of(TemporalTable.from(schema))));
    }

    /**
     * V1 时代的物理 schema：新 schema 已经演进到 eis 契约，但本测试要证明旧库可升级，
     * 因此显式保留 V1 的字段集合。
     */
    private static ObjectSchema legacySchema() {
        return ObjectSchema.builder("employee", "hr_employee")
                .field("employee_no", FieldType.TEXT)
                .field("name", FieldType.TEXT)
                .field("status", FieldType.TEXT)
                .field("hire_date", FieldType.DATE)
                .field("employment_type", FieldType.TEXT)
                .field("work_location", FieldType.TEXT)
                .field("phone", FieldType.TEXT)
                .field("id_number_hash", FieldType.TEXT)
                .field("org_unit_id", FieldType.TEXT)
                .build();
    }

    private static ObjectSchema withBadgeNo(ObjectSchema schema) {
        ObjectSchema.Builder builder = ObjectSchema.builder(schema.objectType(),
                schema.tableName());
        schema.fields().forEach(builder::field);
        builder.field("badge_no", FieldType.TEXT);
        return builder.build();
    }

    private static Map<String, Object> fields(String badgeNo) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("employee_no", "E-1");
        fields.put("name", "张三");
        fields.put("status", "active");
        fields.put("hire_date", LocalDate.of(2026, 1, 5));
        fields.put("employment_type", "FULL_TIME");
        fields.put("work_location", "SH");
        fields.put("phone", "13800000000");
        fields.put("id_number_hash", "hash");
        fields.put("org_unit_id", "D01");
        if (badgeNo != null) {
            fields.put("badge_no", badgeNo);
        }
        return fields;
    }

    private static Migration v1Migration() {
        return new Migration(1, "create hr_employee table", HrEmployeeSchemaCapability.v1());
    }

    private static Migration v2Migration() {
        return new Migration(2, "add badge_no", v2Sql());
    }

    private static String v2Sql() {
        try (InputStream in = HrEmployeeUpgradeMigrationTest.class
                .getResourceAsStream("V2__hr_employee_add_badge_no.sql")) {
            if (in == null) {
                throw new IllegalStateException("missing V2 migration resource");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException("cannot read V2 migration", error);
        }
    }
}
