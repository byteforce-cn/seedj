package cn.byteforce.seedj.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.core.clock.TestClock;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
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

/** QuerySpec：网关注入行范围/字段策略/审计，并拒绝绕过校验的 SQL。 */
class ReadGatewayQueryTest {

    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");

    private static final Caller CALLER = new Caller(ACTOR.toString(), "alice", "default", null,
            Set.of("employee.read"), null, null, null, null);

    private static final ObjectSchema SCHEMA = ObjectSchema.builder("employee", "hr_employee")
            .field(FieldDefinition.required("employee_no", FieldType.TEXT)
                    .withFilterable().withSortable())
            .field(new FieldDefinition("phone", FieldType.TEXT, false, false, false, false,
                    false, false, Privacy.SENSITIVE, Maskers.last4()))
            .writeOnly("id_number_hash", FieldType.TEXT)
            .field(FieldDefinition.of("org_unit_id", FieldType.TEXT).withFilterable())
            .build();

    private static final QuerySpec SPEC = new QuerySpec("employee",
            "SELECT employee_no, phone, id_number_hash, org_unit_id FROM hr_employee"
                    + " WHERE employee_no = :number",
            Set.of("employee_no", "phone", "id_number_hash", "org_unit_id"),
            Set.of("number"), List.of("employee_no"), "employee_no");

    private final RecordingExecutor executor = new RecordingExecutor();

    @Test
    @DisplayName("注入行范围与分页参数，返回前按字段策略脱敏并写审计")
    void injectsScopeAndMasks() {
        ReadGateway gateway = gateway(new ScopePredicate.Eq("org_unit_id", "D01"), true,
                FieldPolicy.defaults(SCHEMA));

        Page<Map<String, Object>> page = gateway.query(SPEC, Map.of("number", "E-1"), null,
                null, new PageRequest(2, 10), CALLER);

        assertThat(executor.pageSql).isEqualTo("SELECT * FROM (SELECT employee_no, phone,"
                + " id_number_hash, org_unit_id FROM hr_employee WHERE employee_no = ?)"
                + " seedj_q WHERE seedj_q.\"org_unit_id\" = ?"
                + " ORDER BY seedj_q.\"employee_no\" ASC LIMIT ? OFFSET ?");
        assertThat(executor.pageArguments)
                .containsExactly("E-1", "D01", 10, 10L);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0)).containsEntry("employee_no", "E-1");
        assertThat(page.items().get(0)).doesNotContainKeys("id_number_hash", "phone");
        assertThat(page.total()).isEqualTo(0);
        assertThat(executor.calls).containsExactly("page", "count");
    }

    @Test
    @DisplayName("字段策略允许 masked 时按 schema 打码器输出")
    void masksWhenPolicyAllows() {
        FieldPolicy masked = new FieldPolicy(Map.of("phone",
                new FieldAccess(FieldAccess.Action.MASKED, "p1")));
        ReadGateway gateway = gateway(new ScopePredicate.All(), true, masked);

        Page<Map<String, Object>> page = gateway.query(SPEC, Map.of("number", "E-1"), null,
                null, PageRequest.defaults(), CALLER);

        assertThat(page.items().get(0)).containsEntry("phone", "****0101");
    }

    @Test
    @DisplayName("排序字段必须同时出现在白名单与 schema sortable 中")
    void rejectsUnlistedSort() {
        ReadGateway gateway = gateway(new ScopePredicate.All(), true, FieldPolicy.defaults(SCHEMA));

        assertThatThrownBy(() -> gateway.query(SPEC, Map.of("number", "E-1"), "org_unit_id",
                SortDirection.ASC, PageRequest.defaults(), CALLER))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.INVALID_PAYLOAD));
    }

    @Test
    @DisplayName("未声明的参数、缺失的参数值都返回 400")
    void rejectsParameters() {
        ReadGateway gateway = gateway(new ScopePredicate.All(), true, FieldPolicy.defaults(SCHEMA));

        assertThatThrownBy(() -> gateway.query(SPEC, Map.of("number", "E-1", "extra", "x"), null,
                null, PageRequest.defaults(), CALLER))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.INVALID_PAYLOAD));
        assertThatThrownBy(() -> gateway.query(SPEC, Map.of(), null, null,
                PageRequest.defaults(), CALLER))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.INVALID_PAYLOAD));
    }

    @Test
    @DisplayName("行范围为 None 时直接空页，不访问存储")
    void noneScopeSkipsExecutor() {
        ReadGateway gateway = gateway(new ScopePredicate.None(), true, FieldPolicy.defaults(SCHEMA));

        Page<Map<String, Object>> page = gateway.query(SPEC, Map.of("number", "E-1"), null, null,
                PageRequest.defaults(), CALLER);

        assertThat(page.items()).isEmpty();
        assertThat(executor.calls).isEmpty();
    }

    @Test
    @DisplayName("没有执行器时失败关闭（503），不静默退化为无过滤查询")
    void missingExecutorFails() {
        ReadGateway gateway = gateway(new ScopePredicate.All(), false, FieldPolicy.defaults(SCHEMA));

        assertThatThrownBy(() -> gateway.query(SPEC, Map.of("number", "E-1"), null, null,
                PageRequest.defaults(), CALLER))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.UNAVAILABLE));
    }

    @Test
    @DisplayName("单表仓储走同一网关，返回类型化页")
    void temporalQueryRepositoryMapsRows() {
        ReadGateway gateway = gateway(new ScopePredicate.All(), true,
                FieldPolicy.defaults(SCHEMA));
        TemporalQueryRepository<String> repository = new TemporalQueryRepository<>(gateway,
                "employee", "employee_no", SortDirection.ASC,
                fields -> String.valueOf(fields.get("employee_no")));

        Page<String> page = repository.find(Map.of("org_unit_id", "D01"), CALLER);

        assertThat(page.items()).containsExactly("E-1");
        assertThat(page.total()).isEqualTo(1);
    }

    private ReadGateway gateway(ScopePredicate scope, boolean withExecutor, FieldPolicy policy) {
        DataScopeResolver scopes = new DataScopeResolver() {
            @Override
            public boolean supports(String objectType) {
                return "employee".equals(objectType);
            }

            @Override
            public ScopePredicate resolve(String objectType, Caller caller,
                                          cn.byteforce.seedj.core.security.ScopeContext context) {
                return scope;
            }
        };
        FieldPolicyResolver fields = new FieldPolicyResolver() {
            @Override
            public boolean supports(String objectType) {
                return "employee".equals(objectType);
            }

            @Override
            public FieldPolicy resolve(String objectType, Caller caller,
                                       cn.byteforce.seedj.core.security.ScopeContext context) {
                return policy;
            }
        };
        return new ReadGateway(registry(),
                new StubStore(),
                List.of(scopes),
                List.of(fields),
                event -> { },
                new TestClock(Instant.parse("2026-11-06T09:00:00Z")),
                UUID::randomUUID,
                withExecutor ? executor : null);
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

        final List<String> calls = new ArrayList<>();

        String pageSql;

        List<Object> pageArguments;

        @Override
        public List<Map<String, Object>> page(String sql, List<Object> parameters) {
            calls.add("page");
            pageSql = sql;
            pageArguments = parameters;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("employee_no", "E-1");
            row.put("phone", "110101199001010101");
            row.put("id_number_hash", "hash");
            row.put("org_unit_id", "D01");
            return List.of(row);
        }

        @Override
        public long count(String sql, List<Object> parameters) {
            calls.add("count");
            return 0;
        }
    }

    private static final class StubStore implements TemporalStore {

        TemporalSearch searched;

        @Override
        public Optional<TemporalRow> find(String objectType, UUID id, TemporalQuery query,
                                          ScopePredicate scope) {
            return Optional.empty();
        }

        @Override
        public AppendResult append(AppendRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Page<TemporalRow> search(TemporalSearch search, ScopePredicate scope) {
            searched = search;
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("employee_no", "E-1");
            Instant now = Instant.parse("2026-11-06T09:00:00Z");
            TemporalRow row = new TemporalRow("employee",
                    new cn.byteforce.seedj.domain.ObjectVersion(
                            UUID.fromString("018f2e3a-0000-7000-8000-000000000009"),
                            1, now, null, now, null, ACTOR, now, null, null),
                    fields);
            return new Page<>(List.of(row), 1, search.page().page(), search.page().size());
        }

        @Override
        public List<TemporalRow> history(String objectType, UUID id, ScopePredicate scope) {
            return List.of();
        }
    }
}
