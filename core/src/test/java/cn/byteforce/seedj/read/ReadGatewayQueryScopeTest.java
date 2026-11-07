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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 自定义查询：行范围谓词到 SQL 的编译、字段白名单与投影校验。 */
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
    @DisplayName("In 谓词编译为占位符列表并按类型转换常量")
    void compilesInPredicate() {
        Page<Map<String, Object>> page =
                query(ScopePredicate.in("org_unit_id", List.of("D01", "D02")));

        assertThat(executor.pageSql).contains("seedj_q.\"org_unit_id\" IN (?,?)");
        assertThat(executor.pageArguments).containsExactly("E-1", "D01", "D02", 20, 0L);
        assertThat(page.items())
                .singleElement()
                .satisfies(row -> assertThat(row).containsEntry("employee_no", "E-1"));
    }

    @Test
    @DisplayName("Not/Or/And/NotNull 谓词编译为嵌套条件")
    void compilesCompositePredicates() {
        ScopePredicate predicate =
                ScopePredicates.and(
                        List.of(
                                ScopePredicate.not(ScopePredicate.eq("status", "LEFT")),
                                ScopePredicate.or(
                                        ScopePredicate.notNull("org_unit_id"),
                                        ScopePredicate.in(
                                                "status", List.of("ACTIVE", "PENDING")))));

        query(predicate);

        assertThat(executor.pageSql)
                .contains(
                        "(NOT (seedj_q.\"status\" = ?)"
                                + " AND (seedj_q.\"org_unit_id\" IS NOT NULL"
                                + " OR seedj_q.\"status\" IN (?,?)))");
        assertThat(executor.pageArguments)
                .containsExactly("E-1", "LEFT", "ACTIVE", "PENDING", 20, 0L);
    }

    @Test
    @DisplayName("空 In 列表退化为恒假，不绑定任何常量")
    void emptyInBecomesFalse() {
        query(new ScopePredicate.In("org_unit_id", List.of()));

        assertThat(executor.pageSql).contains("WHERE 1=0");
        assertThat(executor.pageArguments).containsExactly("E-1", 20, 0L);
    }

    @Test
    @DisplayName("行范围引用未投影字段时失败关闭")
    void scopeFieldMustBeProjected() {
        QuerySpec narrow =
                new QuerySpec(
                        "employee",
                        "SELECT employee_no FROM hr_employee",
                        Set.of("employee_no"),
                        Set.of(),
                        List.of("employee_no"),
                        "employee_no");
        ReadGateway gateway = gateway(ScopePredicate.eq("org_unit_id", "D01"), narrow);

        assertThatThrownBy(
                        () ->
                                gateway.query(
                                        narrow,
                                        Map.of(),
                                        null,
                                        null,
                                        PageRequest.defaults(),
                                        CALLER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("row scope uses field org_unit_id");
        assertThat(executor.calls).isEmpty();
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
        assertThat(executor.pageArguments)
                .containsExactly(PageRequest.defaults().size(), PageRequest.defaults().offset());
    }

    @Test
    @DisplayName("行范围常量可空：摘要按 null 字面量记录")
    void nullScopeValueIsDigested() {
        Page<Map<String, Object>> page = query(new ScopePredicate.Eq("org_unit_id", null));

        assertThat(executor.pageArguments).containsExactly("E-1", null, 20, 0L);
        assertThat(page.total()).isEqualTo(1);
    }

    @Test
    @DisplayName("SQL 含位置占位符时拒绝执行")
    void rejectsPositionalPlaceholders() {
        QuerySpec spec =
                new QuerySpec(
                        "employee",
                        "SELECT employee_no, org_unit_id, status FROM hr_employee WHERE status = ?",
                        Set.of("employee_no", "org_unit_id", "status"),
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
                        error -> {
                            assertThat(error.code()).isEqualTo(ContractErrorCode.INVALID_PAYLOAD);
                            assertThat(error.reason()).contains("named parameters");
                        });
    }

    @Test
    @DisplayName("SQL 引用未声明的命名参数时失败")
    void rejectsUndeclaredNamedParameter() {
        QuerySpec spec =
                new QuerySpec(
                        "employee",
                        "SELECT employee_no, org_unit_id, status FROM hr_employee"
                                + " WHERE status = :state",
                        Set.of("employee_no", "org_unit_id", "status"),
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
                                        .contains("undeclared parameter in query: state"));
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

        assertThat(executor.pageSql)
                .endsWith("ORDER BY seedj_q.\"employee_no\" ASC" + " LIMIT ? OFFSET ?");
        assertThat(executor.pageArguments)
                .containsExactly(
                        "E-1", PageRequest.defaults().size(), PageRequest.defaults().offset());
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

        assertThat(executor.pageSql)
                .endsWith("ORDER BY seedj_q.\"employee_no\" DESC" + " LIMIT ? OFFSET ?");
        assertThat(executor.pageArguments).containsExactly("E-1", 5, 10L);
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

        private final List<String> calls = new ArrayList<>();

        private String pageSql;

        private List<Object> pageArguments;

        private boolean omitStatus;

        @Override
        public List<Map<String, Object>> page(String sql, List<Object> parameters) {
            calls.add("page");
            pageSql = sql;
            pageArguments = parameters;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("employee_no", "E-1");
            row.put("org_unit_id", "D01");
            if (!omitStatus) {
                row.put("status", "ACTIVE");
            }
            return List.of(row);
        }

        @Override
        public long count(String sql, List<Object> parameters) {
            calls.add("count");
            return 1;
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
