package cn.byteforce.seedj.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.core.clock.TestClock;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.ScopeContext;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.AppendResult;
import cn.byteforce.seedj.domain.FieldDefinition;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.Page;
import cn.byteforce.seedj.domain.PageRequest;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.domain.ScopePredicates;
import cn.byteforce.seedj.domain.SortDirection;
import cn.byteforce.seedj.domain.TemporalQuery;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.domain.TemporalSearch;
import cn.byteforce.seedj.domain.TemporalStore;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 自定义查询：网关注入行范围与字段策略，SQL 生成留在适配器。 */
class ReadGatewayQueryScopeTest {

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
            ObjectSchema.builder("employee", "hr_employee")
                    .field(FieldDefinition.required("employee_no", FieldType.TEXT).withSortable())
                    .field(FieldDefinition.of("org_unit_id", FieldType.TEXT).withFilterable())
                    .field(FieldDefinition.of("status", FieldType.TEXT).withFilterable())
                    .writeOnly("id_number_hash", FieldType.TEXT)
                    .build();

    private static final QuerySpec SPEC =
            new QuerySpec(
                    "employee",
                    "SELECT employee_no, org_unit_id, status FROM hr_employee WHERE employee_no = :number",
                    Set.of("employee_no", "org_unit_id", "status"),
                    Set.of("number"),
                    List.of("employee_no"),
                    "employee_no");

    private final RecordingExecutor executor = new RecordingExecutor();

    @Test
    @DisplayName("行范围谓词原样进入执行计划，不在网关里编译成 SQL")
    void passesScopeToPlan() {
        ScopePredicate predicate = ScopePredicate.in("org_unit_id", List.of("D01", "D02"));

        query(predicate);

        assertThat(executor.plan.scope()).isEqualTo(predicate);
    }

    @Test
    @DisplayName("复合谓词只做规范化，不改变语义")
    void passesCompositePredicate() {
        ScopePredicate predicate =
                ScopePredicates.and(
                        List.of(
                                ScopePredicate.not(ScopePredicate.eq("status", "LEFT")),
                                ScopePredicate.or(
                                        ScopePredicate.notNull("org_unit_id"),
                                        ScopePredicate.in(
                                                "status", List.of("ACTIVE", "PENDING")))));

        query(predicate);

        assertThat(executor.plan.scope()).isEqualTo(predicate);
    }

    @Test
    @DisplayName("查询声明的字段必须是 schema 字段")
    void rejectsUnknownProjectedField() {
        QuerySpec spec =
                new QuerySpec(
                        "employee",
                        "SELECT employee_no, salary FROM hr_employee",
                        Set.of("employee_no", "salary"),
                        Set.of(),
                        List.of("employee_no"),
                        "employee_no");
        ReadGateway gateway = gateway(ScopePredicate.all(), spec);

        assertThatThrownBy(
                        () ->
                                gateway.query(
                                        spec, Map.of(), null, null, PageRequest.defaults(), CALLER))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error ->
                                assertThat(error.reason())
                                        .contains("unknown field for employee: salary"));
    }

    @Test
    @DisplayName("排序字段必须可排序且不能是写入专用字段")
    void rejectsNonSortableField() {
        QuerySpec spec =
                new QuerySpec(
                        "employee",
                        "SELECT employee_no, id_number_hash FROM hr_employee",
                        Set.of("employee_no", "id_number_hash"),
                        Set.of(),
                        List.of("employee_no"),
                        "employee_no");
        ReadGateway gateway = gateway(ScopePredicate.all(), spec);

        assertThatThrownBy(
                        () ->
                                gateway.query(
                                        spec,
                                        Map.of(),
                                        "id_number_hash",
                                        null,
                                        PageRequest.defaults(),
                                        CALLER))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error -> assertThat(error.reason()).contains("sort field is not allowed"));
    }

    @Test
    @DisplayName("schema 未声明 sortable 的字段不能用于排序")
    void rejectsSchemaNonSortableField() {
        QuerySpec spec =
                new QuerySpec(
                        "employee",
                        "SELECT employee_no, org_unit_id, status FROM hr_employee",
                        Set.of("employee_no", "org_unit_id", "status"),
                        Set.of(),
                        List.of("status"),
                        "status");
        ReadGateway gateway = gateway(ScopePredicate.all(), spec);

        assertThatThrownBy(
                        () ->
                                gateway.query(
                                        spec, Map.of(), null, null, PageRequest.defaults(), CALLER))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error ->
                                assertThat(error.reason())
                                        .contains("field is not sortable: status"));
    }

    @Test
    @DisplayName("未声明的参数键返回 400；声明但缺值也返回 400")
    void rejectsParameters() {
        ReadGateway gateway = gateway(ScopePredicate.all(), SPEC);

        assertThatThrownBy(
                        () ->
                                gateway.query(
                                        SPEC,
                                        Map.of("number", "E-1", "extra", "x"),
                                        null,
                                        null,
                                        PageRequest.defaults(),
                                        CALLER))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error ->
                                assertThat(error.code())
                                        .isEqualTo(ContractErrorCode.INVALID_PAYLOAD));
        assertThatThrownBy(
                        () ->
                                gateway.query(
                                        SPEC, Map.of(), null, null, PageRequest.defaults(), CALLER))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error -> assertThat(error.reason()).contains("missing parameter value"));
    }

    @Test
    @DisplayName("无参数查询接受 null 参数映射")
    void acceptsNullParameters() {
        QuerySpec spec =
                new QuerySpec(
                        "employee",
                        "SELECT employee_no, org_unit_id, status FROM hr_employee",
                        Set.of("employee_no", "org_unit_id", "status"),
                        Set.of(),
                        List.of("employee_no"),
                        "employee_no");
        ReadGateway gateway = gateway(ScopePredicate.all(), spec);

        Page<Map<String, Object>> page = gateway.query(spec, null, null, null, null, CALLER);

        assertThat(page.items()).hasSize(1);
        assertThat(executor.plan.parameters()).isEmpty();
        assertThat(executor.plan.page()).isEqualTo(PageRequest.defaults());
    }

    @Test
    @DisplayName("行范围常量可空：null 依旧进入计划，由适配器编译为恒假")
    void nullScopeValueIsPassedThrough() {
        ScopePredicate predicate = new ScopePredicate.Eq("org_unit_id", null);

        Page<Map<String, Object>> page = query(predicate);

        assertThat(executor.plan.scope()).isEqualTo(predicate);
        assertThat(page.total()).isEqualTo(1);
    }

    @Test
    @DisplayName("自定义查询没有返回声明的字段时失败关闭")
    void rejectsRowMissingDeclaredField() {
        executor.omitStatus = true;
        ReadGateway gateway = gateway(ScopePredicate.all(), SPEC);

        assertThatThrownBy(
                        () ->
                                gateway.query(
                                        SPEC,
                                        Map.of("number", "E-1"),
                                        null,
                                        null,
                                        PageRequest.defaults(),
                                        CALLER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("custom query did not return declared field: status");
    }

    @Test
    @DisplayName("未显式指定时使用默认分页与升序")
    void defaultsPagingAndDirection() {
        Page<Map<String, Object>> page = query(ScopePredicate.all());

        assertThat(executor.plan.sortField()).isEqualTo("employee_no");
        assertThat(executor.plan.direction()).isEqualTo(SortDirection.ASC);
        assertThat(executor.plan.page()).isEqualTo(PageRequest.defaults());
        assertThat(page.page()).isEqualTo(PageRequest.defaults().page());
    }

    @Test
    @DisplayName("显式分页与降序被透传")
    void explicitPagingAndDirection() {
        ReadGateway gateway = gateway(ScopePredicate.all(), SPEC);

        gateway.query(
                SPEC,
                Map.of("number", "E-1"),
                "employee_no",
                SortDirection.DESC,
                new PageRequest(3, 5),
                CALLER);

        assertThat(executor.plan.direction()).isEqualTo(SortDirection.DESC);
        assertThat(executor.plan.page()).isEqualTo(new PageRequest(3, 5));
    }

    @Test
    @DisplayName("字段策略为 HIDDEN 的字段不进入结果，MASKED 无打码器时不输出")
    void policyControlsProjection() {
        Map<String, FieldAccess> granted = new LinkedHashMap<>();
        granted.put("status", new FieldAccess(FieldAccess.Action.HIDDEN, "p-hide"));
        granted.put("org_unit_id", new FieldAccess(FieldAccess.Action.MASKED, "p-mask"));
        ReadGateway gateway =
                new ReadGateway(
                        registry(),
                        new StubStore(),
                        List.of(scope(ScopePredicate.all())),
                        List.of(policy(new FieldPolicy(granted))),
                        event -> {},
                        new TestClock(Instant.parse("2026-11-06T09:00:00Z")),
                        UUID::randomUUID,
                        executor);

        Page<Map<String, Object>> page =
                gateway.query(
                        SPEC, Map.of("number", "E-1"), null, null, PageRequest.defaults(), CALLER);

        assertThat(page.items())
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row).doesNotContainKey("status");
                            assertThat(row).doesNotContainKey("org_unit_id");
                        });
    }

    private Page<Map<String, Object>> query(ScopePredicate predicate) {
        return gateway(predicate, SPEC)
                .query(SPEC, Map.of("number", "E-1"), null, null, PageRequest.defaults(), CALLER);
    }

    private ReadGateway gateway(ScopePredicate predicate, QuerySpec spec) {
        return new ReadGateway(
                registry(),
                new StubStore(),
                List.of(scope(predicate)),
                List.of(policy(FieldPolicy.defaults(SCHEMA))),
                event -> {},
                new TestClock(Instant.parse("2026-11-06T09:00:00Z")),
                UUID::randomUUID,
                executor);
    }

    private static DataScopeResolver scope(ScopePredicate predicate) {
        return new DataScopeResolver() {
            @Override
            public boolean supports(String objectType) {
                return "employee".equals(objectType);
            }

            @Override
            public ScopePredicate resolve(String objectType, Caller caller, ScopeContext context) {
                return predicate;
            }
        };
    }

    private static FieldPolicyResolver policy(FieldPolicy value) {
        return new FieldPolicyResolver() {
            @Override
            public boolean supports(String objectType) {
                return "employee".equals(objectType);
            }

            @Override
            public FieldPolicy resolve(String objectType, Caller caller, ScopeContext context) {
                return value;
            }
        };
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

    private static final class RecordingExecutor implements QueryExecutor {

        private int calls;

        private QueryPlan plan;

        private boolean omitStatus;

        @Override
        public Page<Map<String, Object>> execute(QueryPlan plan) {
            calls++;
            this.plan = plan;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("employee_no", "E-1");
            row.put("org_unit_id", "D01");
            if (!omitStatus) {
                row.put("status", "ACTIVE");
            }
            return new Page<>(List.of(row), 1, plan.page().page(), plan.page().size());
        }
    }

    private static final class StubStore implements TemporalStore {

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
            return new Page<>(List.of(), 0, search.page().page(), search.page().size());
        }

        @Override
        public List<TemporalRow> history(String objectType, UUID id, ScopePredicate scope) {
            return List.of();
        }
    }
}
