package cn.byteforce.seedj.read;

import cn.byteforce.seedj.core.clock.TestClock;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.ScopeContext;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.AppendResult;
import cn.byteforce.seedj.domain.FieldDefinition;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.Maskers;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.ObjectVersion;
import cn.byteforce.seedj.domain.Page;
import cn.byteforce.seedj.domain.Privacy;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.domain.SortDirection;
import cn.byteforce.seedj.domain.TemporalQuery;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.domain.TemporalSearch;
import cn.byteforce.seedj.domain.TemporalStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 读网关的单对象/历史/列表路径：字段策略合成、脱敏与读审计。
 */
class ReadGatewayReadPathsTest {

    private static final UUID ID = UUID.fromString("018f2e3a-1234-7000-8000-000000000001");

    private static final Instant NOW = Instant.parse("2026-11-06T09:00:00Z");

    private static final String TYPE = "employee";

    private static final Caller CALLER = new Caller("u-1", "alice", "default", Set.of(),
            Set.of("hr.employee.read"), Set.of(), Set.of("pwd"), "urn:seedj:acr:pwd", Map.of());

    private static final ObjectSchema SCHEMA = ObjectSchema.builder(TYPE, "hr_employee")
            .required("employee_no", FieldType.TEXT)
            .field(new FieldDefinition("phone", FieldType.TEXT, false, false, false, false, false,
                    false, Privacy.SENSITIVE, Maskers.last4()))
            .writeOnly("id_number_hash", FieldType.TEXT)
            .field("org_unit_id", FieldType.TEXT)
            .build();

    private final List<ReadAccessEvent> audited = new ArrayList<>();

    @Test
    @DisplayName("find 渲染行并按默认策略隐藏敏感与写入专用字段，写读审计")
    void findRendersWithDefaultPolicy() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("employee_no", "E-1");
        fields.put("phone", "13800138000");
        fields.put("id_number_hash", "hash");
        fields.put("not_in_schema", "ignored");
        ReadGateway gateway = gateway(new StubStore(Optional.of(row(fields)), List.of()));

        Optional<TemporalRow> found = gateway.find(TYPE, ID, TemporalQuery.current(), CALLER);

        assertThat(found).isPresent();
        assertThat(found.orElseThrow().fields())
                .containsExactly(Map.entry("employee_no", "E-1"));
        assertThat(audited).singleElement().satisfies(event -> {
            assertThat(event.operation()).isEqualTo(ReadAccessEvent.Operation.FIND);
            assertThat(event.rowIds()).containsExactly(ID);
            assertThat(event.callerId()).isEqualTo("u-1");
            assertThat(event.scopeDigest()).matches("^[0-9a-f]{64}$");
            assertThat(event.at()).isEqualTo(NOW);
        });
    }

    @Test
    @DisplayName("字段策略可以为敏感字段放行打码，并记录策略 id")
    void findMasksWhenPolicyAllows() {
        FieldPolicy masked = new FieldPolicy(Map.of("phone",
                new FieldAccess(FieldAccess.Action.MASKED, "policy-1")));
        ReadGateway gateway = gateway(new StubStore(Optional.of(row(Map.of("employee_no", "E-1",
                "phone", "13800138000"))), List.of()), List.of(fields(masked)));

        TemporalRow found = gateway.find(TYPE, ID, TemporalQuery.current(), CALLER).orElseThrow();

        assertThat(found.fields()).containsEntry("phone", "****8000");
        assertThat(audited).singleElement()
                .extracting(ReadAccessEvent::policyIds)
                .isEqualTo(Set.of("policy-1"));
    }

    @Test
    @DisplayName("find 未命中时不写行 id，但仍记录读审计")
    void findMissStillAudits() {
        ReadGateway gateway = gateway(new StubStore(Optional.empty(), List.of()));

        assertThat(gateway.find(TYPE, ID, TemporalQuery.current(), CALLER)).isEmpty();
        assertThat(audited).singleElement()
                .extracting(ReadAccessEvent::rowIds).isEqualTo(List.of());
    }

    @Test
    @DisplayName("history 渲染全部版本并按行 id 去重记录")
    void historyRendersRows() {
        TemporalRow first = row(Map.of("employee_no", "E-1", "phone", "13800138000"));
        TemporalRow second = row(Map.of("employee_no", "E-2"));
        ReadGateway gateway = gateway(new StubStore(Optional.empty(), List.of(first, second)));

        List<TemporalRow> history = gateway.history(TYPE, ID, CALLER);

        assertThat(history).hasSize(2);
        assertThat(history.get(0).fields()).containsExactly(Map.entry("employee_no", "E-1"));
        assertThat(history.get(1).fields()).containsExactly(Map.entry("employee_no", "E-2"));
        assertThat(audited).singleElement().extracting(ReadAccessEvent::operation)
                .isEqualTo(ReadAccessEvent.Operation.HISTORY);
    }

    @Test
    @DisplayName("search 的 objectType 必须与被查询对象一致")
    void searchRejectsMismatchedObjectType() {
        ReadGateway gateway = gateway(new StubStore(Optional.empty(), List.of()));

        TemporalSearch other = new TemporalSearch("invoice", Map.of(), null, null, null,
                "employee_no", SortDirection.ASC,
                cn.byteforce.seedj.domain.PageRequest.defaults());

        assertThatThrownBy(() -> gateway.search(TYPE, other, CALLER))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.INVALID_PAYLOAD));
    }

    @Test
    @DisplayName("多个字段策略按 order 依次收窄")
    void fieldPoliciesNarrowInOrder() {
        FieldPolicy hiding = new FieldPolicy(Map.of("employee_no",
                new FieldAccess(FieldAccess.Action.HIDDEN, "p-hide")));
        FieldPolicy allowing = new FieldPolicy(Map.of("employee_no",
                new FieldAccess(FieldAccess.Action.FULL, "p-full")));
        ReadGateway gateway = gateway(new StubStore(Optional.of(
                row(Map.of("employee_no", "E-1"))), List.of()),
                List.of(fields(allowing, 5), fields(hiding, 1)));

        TemporalRow found = gateway.find(TYPE, ID, TemporalQuery.current(), CALLER).orElseThrow();

        assertThat(found.fields()).isEmpty();
    }

    @Test
    @DisplayName("字段策略解析失败返回 503，不静默放行")
    void fieldPolicyFailureReturns503() {
        ReadGateway gateway = gateway(new StubStore(Optional.empty(), List.of()),
                List.of(failingFields(new IllegalStateException("pdp down"))));

        assertThatThrownBy(() -> gateway.find(TYPE, ID, TemporalQuery.current(), CALLER))
                .isInstanceOfSatisfying(ContractException.class, error -> {
                    assertThat(error.code()).isEqualTo(ContractErrorCode.UNAVAILABLE);
                    assertThat(error.getCause()).hasMessageContaining("pdp down");
                });
    }

    @Test
    @DisplayName("字段策略返回 null 时失败关闭")
    void nullFieldPolicyFails() {
        ReadGateway gateway = gateway(new StubStore(Optional.empty(), List.of()),
                List.of(nullFields()));

        assertThatThrownBy(() -> gateway.find(TYPE, ID, TemporalQuery.current(), CALLER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("field policy resolver returned null");
    }

    @Test
    @DisplayName("字段策略引用未知字段时失败关闭")
    void unknownPolicyFieldFails() {
        FieldPolicy bogus = new FieldPolicy(Map.of("nope",
                new FieldAccess(FieldAccess.Action.FULL, "p")));
        ReadGateway gateway = gateway(new StubStore(Optional.empty(), List.of()),
                List.of(fields(bogus)));

        assertThatThrownBy(() -> gateway.find(TYPE, ID, TemporalQuery.current(), CALLER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("field policy references unknown field: nope");
    }

    @Test
    @DisplayName("不支持该对象类型的字段策略被跳过")
    void unsupportedFieldPolicyIsSkipped() {
        FieldPolicyResolver other = new FieldPolicyResolver() {
            @Override
            public boolean supports(String objectType) {
                return false;
            }

            @Override
            public FieldPolicy resolve(String objectType, Caller caller, ScopeContext context) {
                throw new AssertionError("must not be called");
            }
        };
        ReadGateway gateway = gateway(new StubStore(Optional.of(
                row(Map.of("employee_no", "E-1"))), List.of()), List.of(other));

        assertThat(gateway.find(TYPE, ID, TemporalQuery.current(), CALLER)).isPresent();
    }

    @Test
    @DisplayName("读审计失败不影响读结果")
    void auditFailureDoesNotBreakRead() {
        ReadGateway gateway = new ReadGateway(registry(), new StubStore(Optional.of(
                row(Map.of("employee_no", "E-1"))), List.of()), List.of(allScope()), List.of(),
                event -> {
                    throw new IllegalStateException("audit sink down");
                },
                new TestClock(NOW), UUID::randomUUID);

        assertThat(gateway.find(TYPE, ID, TemporalQuery.current(), CALLER)).isPresent();
    }

    // ---------------------------------------------------------------- fixtures

    private ReadGateway gateway(TemporalStore store) {
        return gateway(store, List.of());
    }

    private ReadGateway gateway(TemporalStore store, List<FieldPolicyResolver> policies) {
        return new ReadGateway(registry(), store, List.of(allScope()), policies,
                audited::add, new TestClock(NOW), UUID::randomUUID);
    }

    private static DataScopeResolver allScope() {
        return scope(ScopePredicate.all(), 0);
    }

    private static DataScopeResolver scope(ScopePredicate predicate, int order) {
        return new DataScopeResolver() {
            @Override
            public boolean supports(String objectType) {
                return TYPE.equals(objectType);
            }

            @Override
            public ScopePredicate resolve(String objectType, Caller caller,
                                          ScopeContext context) {
                return predicate;
            }

            @Override
            public int order() {
                return order;
            }
        };
    }

    private static FieldPolicyResolver fields(FieldPolicy policy) {
        return fields(policy, 0);
    }

    private static FieldPolicyResolver fields(FieldPolicy policy, int order) {
        return new FieldPolicyResolver() {
            @Override
            public boolean supports(String objectType) {
                return TYPE.equals(objectType);
            }

            @Override
            public FieldPolicy resolve(String objectType, Caller caller,
                                       ScopeContext context) {
                return policy;
            }

            @Override
            public int order() {
                return order;
            }
        };
    }

    private static FieldPolicyResolver failingFields(RuntimeException failure) {
        return new FieldPolicyResolver() {
            @Override
            public boolean supports(String objectType) {
                return TYPE.equals(objectType);
            }

            @Override
            public FieldPolicy resolve(String objectType, Caller caller,
                                       ScopeContext context) {
                throw failure;
            }
        };
    }

    private static FieldPolicyResolver nullFields() {
        return new FieldPolicyResolver() {
            @Override
            public boolean supports(String objectType) {
                return TYPE.equals(objectType);
            }

            @Override
            public FieldPolicy resolve(String objectType, Caller caller,
                                       ScopeContext context) {
                return null;
            }
        };
    }

    private static TemporalRow row(Map<String, Object> fields) {
        return new TemporalRow(TYPE, new ObjectVersion(ID, 1, NOW, null, NOW, null, ID, NOW,
                null, null), fields);
    }

    private static ReadModelRegistry registry() {
        return new ReadModelRegistry() {
            @Override
            public Optional<ReadModel> find(String objectType) {
                return TYPE.equals(objectType)
                        ? Optional.of(new ReadModel(TYPE, SCHEMA, "hr.employee.read"))
                        : Optional.empty();
            }

            @Override
            public List<ReadModel> all() {
                return List.of(new ReadModel(TYPE, SCHEMA, "hr.employee.read"));
            }
        };
    }

    private static final class StubStore implements TemporalStore {

        private final Optional<TemporalRow> found;

        private final List<TemporalRow> history;

        private StubStore(Optional<TemporalRow> found, List<TemporalRow> history) {
            this.found = found;
            this.history = history;
        }

        @Override
        public Optional<TemporalRow> find(String objectType, UUID id, TemporalQuery query,
                                          ScopePredicate scope) {
            return found;
        }

        @Override
        public AppendResult append(AppendRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Page<TemporalRow> search(TemporalSearch search, ScopePredicate scope) {
            return new Page<>(history, history.size(), search.page().page(),
                    search.page().size());
        }

        @Override
        public List<TemporalRow> history(String objectType, UUID id, ScopePredicate scope) {
            return history;
        }
    }
}
