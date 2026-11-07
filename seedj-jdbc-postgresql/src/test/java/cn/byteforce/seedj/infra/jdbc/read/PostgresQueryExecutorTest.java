package cn.byteforce.seedj.infra.jdbc.read;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.core.clock.TestClock;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.AppendResult;
import cn.byteforce.seedj.domain.FieldDefinition;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.Maskers;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.Page;
import cn.byteforce.seedj.domain.PageRequest;
import cn.byteforce.seedj.domain.Privacy;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.domain.TemporalQuery;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.domain.TemporalSearch;
import cn.byteforce.seedj.domain.TemporalStore;
import cn.byteforce.seedj.read.DataScopeResolver;
import cn.byteforce.seedj.read.FieldAccess;
import cn.byteforce.seedj.read.FieldPolicy;
import cn.byteforce.seedj.read.FieldPolicyResolver;
import cn.byteforce.seedj.read.QuerySpec;
import cn.byteforce.seedj.read.ReadGateway;
import cn.byteforce.seedj.read.ReadModel;
import cn.byteforce.seedj.read.ReadModelRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** QuerySpec 在真实 PostgreSQL 上的 join + 行范围 + 脱敏 + 分页。 */
@Testcontainers
class PostgresQueryExecutorTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("seedj")
                    .withUsername("seedj")
                    .withPassword("seedj");

    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");

    private static final Caller CALLER =
            new Caller(
                    ACTOR.toString(),
                    "alice",
                    "default",
                    null,
                    Set.of("employee.read"),
                    null,
                    null,
                    null,
                    null);

    private static final ObjectSchema SCHEMA =
            ObjectSchema.builder("employee", "employee")
                    .field(FieldDefinition.required("employee_no", FieldType.TEXT).withSortable())
                    .field(
                            new FieldDefinition(
                                    "phone",
                                    FieldType.TEXT,
                                    false,
                                    false,
                                    false,
                                    false,
                                    false,
                                    false,
                                    Privacy.SENSITIVE,
                                    Maskers.last4()))
                    .field(FieldDefinition.of("org_unit_id", FieldType.TEXT).withFilterable())
                    .build();

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void seed() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE org_unit (id TEXT PRIMARY KEY, name TEXT NOT NULL)");
        jdbc.execute(
                "CREATE TABLE employee (employee_no TEXT PRIMARY KEY, phone TEXT,"
                        + " org_unit_id TEXT NOT NULL)");
        jdbc.update("INSERT INTO org_unit (id, name) VALUES ('D01', 'R&D'), ('D02', 'Sales')");
        jdbc.update(
                "INSERT INTO employee (employee_no, phone, org_unit_id) VALUES"
                        + " ('E-1', '110101199001010101', 'D01'),"
                        + " ('E-2', '110101199001010202', 'D01'),"
                        + " ('E-3', '110101199001010303', 'D02')");
    }

    @Test
    @DisplayName("join 查询被注入行范围，敏感字段按策略打码，分页与总数正确")
    void joinWithScopeAndMasking() {
        ReadGateway gateway =
                new ReadGateway(
                        registry(),
                        new UnusedStore(),
                        List.of(scope()),
                        List.of(maskedPhone()),
                        event -> {},
                        new TestClock(Instant.parse("2026-11-06T09:00:00Z")),
                        UUID::randomUUID,
                        new PostgresQueryExecutor(jdbc));
        QuerySpec spec =
                new QuerySpec(
                        "employee",
                        "SELECT e.employee_no, e.phone, e.org_unit_id FROM employee e"
                                + " JOIN org_unit o ON o.id = e.org_unit_id"
                                + " WHERE o.name = :unit_name",
                        Set.of("employee_no", "phone", "org_unit_id"),
                        Set.of("unit_name"),
                        List.of("employee_no"),
                        "employee_no");

        Page<Map<String, Object>> page =
                gateway.query(
                        spec,
                        Map.of("unit_name", "R&D"),
                        null,
                        null,
                        new PageRequest(1, 1),
                        CALLER);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0)).containsEntry("employee_no", "E-1");
        assertThat(page.items().get(0)).containsEntry("org_unit_id", "D01");
        assertThat(page.items().get(0)).containsEntry("phone", "****0101");
    }

    @Test
    @DisplayName("行范围在 SQL 外层收窄，不能通过 join 读到其他组织的数据")
    void scopeCannotBeBypassedByJoin() {
        ReadGateway gateway =
                new ReadGateway(
                        registry(),
                        new UnusedStore(),
                        List.of(scope()),
                        List.of(maskedPhone()),
                        event -> {},
                        new TestClock(Instant.parse("2026-11-06T09:00:00Z")),
                        UUID::randomUUID,
                        new PostgresQueryExecutor(jdbc));
        QuerySpec spec =
                new QuerySpec(
                        "employee",
                        "SELECT employee_no, phone, org_unit_id FROM employee WHERE org_unit_id = :unit",
                        Set.of("employee_no", "phone", "org_unit_id"),
                        Set.of("unit"),
                        List.of("employee_no"),
                        "employee_no");

        Page<Map<String, Object>> page =
                gateway.query(
                        spec, Map.of("unit", "D02"), null, null, PageRequest.defaults(), CALLER);

        assertThat(page.total()).isZero();
        assertThat(page.items()).isEmpty();
    }

    private static ReadModelRegistry registry() {
        return new ReadModelRegistry() {
            @Override
            public Optional<ReadModel> find(String objectType) {
                return "employee".equals(objectType)
                        ? Optional.of(new ReadModel("employee", SCHEMA, "employee.read"))
                        : Optional.empty();
            }

            @Override
            public List<ReadModel> all() {
                return List.of(new ReadModel("employee", SCHEMA, "employee.read"));
            }
        };
    }

    private static DataScopeResolver scope() {
        return new DataScopeResolver() {
            @Override
            public boolean supports(String objectType) {
                return "employee".equals(objectType);
            }

            @Override
            public ScopePredicate resolve(
                    String objectType,
                    Caller caller,
                    cn.byteforce.seedj.core.security.ScopeContext context) {
                return new ScopePredicate.In("org_unit_id", List.of("D01"));
            }
        };
    }

    private static FieldPolicyResolver maskedPhone() {
        return new FieldPolicyResolver() {
            @Override
            public boolean supports(String objectType) {
                return "employee".equals(objectType);
            }

            @Override
            public FieldPolicy resolve(
                    String objectType,
                    Caller caller,
                    cn.byteforce.seedj.core.security.ScopeContext context) {
                return new FieldPolicy(
                        Map.of("phone", new FieldAccess(FieldAccess.Action.MASKED, "test")));
            }
        };
    }

    private static final class UnusedStore implements TemporalStore {

        @Override
        public Optional<TemporalRow> find(
                String objectType, UUID id, TemporalQuery query, ScopePredicate scope) {
            return Optional.empty();
        }

        @Override
        public AppendResult append(AppendRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Page<TemporalRow> search(TemporalSearch search, ScopePredicate scope) {
            return Page.empty(search.page());
        }

        @Override
        public List<TemporalRow> history(String objectType, UUID id, ScopePredicate scope) {
            return List.of();
        }
    }
}
