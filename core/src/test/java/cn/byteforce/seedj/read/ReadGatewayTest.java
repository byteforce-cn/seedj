package cn.byteforce.seedj.read;

import cn.byteforce.seedj.core.clock.TestClock;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.ScopeContext;
import cn.byteforce.seedj.core.security.ScopeContextHolder;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.AppendResult;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.Maskers;
import cn.byteforce.seedj.domain.ObjectVersion;
import cn.byteforce.seedj.domain.Page;
import cn.byteforce.seedj.domain.PageRequest;
import cn.byteforce.seedj.domain.Privacy;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.domain.ScopePredicates;
import cn.byteforce.seedj.domain.TemporalQuery;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.domain.TemporalSearch;
import cn.byteforce.seedj.domain.TemporalStore;
import cn.byteforce.seedj.module.ObjectModuleRegistry;
import cn.byteforce.seedj.module.ObjectModules;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static cn.byteforce.seedj.read.FieldAccess.Action.FULL;
import static cn.byteforce.seedj.read.FieldAccess.Action.MASKED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReadGatewayTest {

    private static final UUID ID = UUID.fromString("018f2e3a-1234-7000-8000-000000000001");

    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");

    private static final Instant NOW = Instant.parse("2026-11-06T09:00:00Z");

    private static final String TYPE = "employee";

    private static ObjectModuleRegistry registry() {
        ObjectModules modules = ObjectModules.create();
        modules.object(TYPE, "hr_employee")
                .readPermission("hr.employee.read")
                .field("employee_no", FieldType.TEXT).required().filterable().sortable()
                .field("phone", FieldType.TEXT).privacy(Privacy.SENSITIVE)
                        .mask(Maskers.last4())
                .field("id_number_hash", FieldType.TEXT).writeOnly()
                .field("org_unit_id", FieldType.TEXT).filterable()
                .field("status", FieldType.TEXT).filterable();
        return modules.freeze();
    }

    private static Caller caller() {
        return new Caller("u-1", "alice", "default", Set.of(), Set.of("hr.employee.read"),
                Set.of(), Set.of("pwd"), "urn:seedj:acr:pwd", Map.of());
    }

    private static TemporalRow row() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("employee_no", "E-1");
        fields.put("phone", "13800138000");
        fields.put("id_number_hash", "hash");
        fields.put("org_unit_id", "D01");
        fields.put("status", "ACTIVE");
        return new TemporalRow(TYPE, new ObjectVersion(ID, 1, NOW, null, NOW, null,
                ACTOR, NOW, null, null), fields);
    }

    private static TemporalSearch search() {
        return new TemporalSearch(TYPE, Map.of("status", "ACTIVE"), null, null, null,
                "employee_no", null, new PageRequest(1, 20));
    }

    private static DataScopeResolver scope(String objectType, ScopePredicate predicate) {
        return new DataScopeResolver() {
            @Override
            public boolean supports(String type) {
                return objectType.equals(type);
            }

            @Override
            public ScopePredicate resolve(String type, Caller caller, ScopeContext context) {
                return predicate;
            }
        };
    }

    private static FieldPolicyResolver fields(FieldPolicy policy) {
        return new FieldPolicyResolver() {
            @Override
            public boolean supports(String objectType) {
                return true;
            }

            @Override
            public FieldPolicy resolve(String objectType, Caller caller, ScopeContext context) {
                return policy;
            }
        };
    }

    private static ReadGateway gateway(TemporalStore store, List<DataScopeResolver> scopes,
                                       List<FieldPolicyResolver> fields, ReadAuditSink audit) {
        return new ReadGateway(registry(), store, scopes, fields, audit,
                new TestClock(NOW), () -> UUID.randomUUID());
    }

    private static ReadGateway gateway(TemporalStore store, List<DataScopeResolver> scopes) {
        return gateway(store, scopes, List.of(), ReadAuditSink.noop());
    }

    @Test
    void missingResolverReturns403WithoutTouchingStore() {
        RecordingStore store = new RecordingStore(row());

        assertThatThrownBy(() -> gateway(store, List.of()).search(TYPE, search(), caller()))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.FORBIDDEN));
        assertThat(store.calls).isZero();
    }

    @Test
    void twoResolversAreAnded() {
        RecordingStore store = new RecordingStore(row());
        ReadGateway gateway = gateway(store, List.of(
                scope(TYPE, ScopePredicate.eq("org_unit_id", "D01")),
                scope(TYPE, ScopePredicate.eq("status", "ACTIVE"))));

        gateway.search(TYPE, search(), caller());

        assertThat(store.lastScope).isInstanceOf(ScopePredicate.And.class);
        assertThat(ScopePredicates.matches(row(), store.lastScope,
                registry().require(TYPE).schema())).isTrue();
    }

    @Test
    void sensitiveFieldIsHiddenByDefault() {
        RecordingStore store = new RecordingStore(row());

        TemporalRow rendered = gateway(store, List.of(scope(TYPE, ScopePredicate.all())))
                .find(TYPE, ID, TemporalQuery.current(), caller()).orElseThrow();

        assertThat(rendered.fields()).containsKey("employee_no")
                .doesNotContainKey("phone")
                .doesNotContainKey("id_number_hash");
    }

    @Test
    void maskedFieldUsesMasker() {
        RecordingStore store = new RecordingStore(row());
        ReadGateway gateway = gateway(store, List.of(scope(TYPE, ScopePredicate.all())),
                List.of(fields(new FieldPolicy(Map.of("phone",
                        new FieldAccess(MASKED, "hr.employee.masked"))))),
                ReadAuditSink.noop());

        TemporalRow rendered = gateway.find(TYPE, ID, TemporalQuery.current(), caller())
                .orElseThrow();

        assertThat(rendered.fields()).containsEntry("phone", "****8000");
    }

    @Test
    void writeOnlyFieldIsAlwaysRemoved() {
        RecordingStore store = new RecordingStore(row());
        ReadGateway gateway = gateway(store, List.of(scope(TYPE, ScopePredicate.all())),
                List.of(fields(new FieldPolicy(Map.of("id_number_hash",
                        new FieldAccess(FULL, "too.wide"))))),
                ReadAuditSink.noop());

        TemporalRow rendered = gateway.find(TYPE, ID, TemporalQuery.current(), caller())
                .orElseThrow();

        assertThat(rendered.fields()).doesNotContainKey("id_number_hash");
    }

    @Test
    void resolverFailureBecomes503() {
        RecordingStore store = new RecordingStore(row());
        DataScopeResolver broken = new DataScopeResolver() {
            @Override
            public boolean supports(String objectType) {
                return true;
            }

            @Override
            public ScopePredicate resolve(String objectType, Caller caller,
                                          ScopeContext context) {
                throw new IllegalStateException("db down");
            }
        };

        assertThatThrownBy(() -> gateway(store, List.of(broken))
                .find(TYPE, ID, TemporalQuery.current(), caller()))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.UNAVAILABLE));
        assertThat(store.calls).isZero();
    }

    @Test
    void unknownObjectIs404AndMissingCallerIs401() {
        RecordingStore store = new RecordingStore(row());
        ReadGateway gateway = gateway(store, List.of(scope(TYPE, ScopePredicate.all())));

        assertThatThrownBy(() -> gateway.find("missing", ID, TemporalQuery.current(), caller()))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.OBJECT_NOT_FOUND));
        assertThatThrownBy(() -> gateway.find(TYPE, ID, TemporalQuery.current(), null))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.UNAUTHENTICATED));
        assertThat(store.calls).isZero();
    }

    @Test
    void callerWithoutReadPermissionIs403() {
        RecordingStore store = new RecordingStore(row());
        Caller nobody = new Caller("u-2", "bob", "default", Set.of(), Set.of(), Set.of(),
                Set.of("pwd"), "urn:seedj:acr:pwd", Map.of());

        assertThatThrownBy(() -> gateway(store, List.of(scope(TYPE, ScopePredicate.all())))
                .search(TYPE, search(), nobody))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.FORBIDDEN));
        assertThat(store.calls).isZero();
    }

    @Test
    void resolverReturningUnknownFieldIs500() {
        RecordingStore store = new RecordingStore(row());

        assertThatThrownBy(() -> gateway(store, List.of(
                scope(TYPE, ScopePredicate.eq("missing", "x"))))
                .search(TYPE, search(), caller()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing");
        assertThat(store.calls).isZero();
    }

    @Test
    void fieldPolicyWithUnknownFieldIs500() {
        RecordingStore store = new RecordingStore(row());
        ReadGateway gateway = gateway(store, List.of(scope(TYPE, ScopePredicate.all())),
                List.of(fields(new FieldPolicy(Map.of("missing", new FieldAccess(FULL, "p"))))),
                ReadAuditSink.noop());

        assertThatThrownBy(() -> gateway.find(TYPE, ID, TemporalQuery.current(), caller()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void auditRecordsScopeDigestAndPolicyIds() {
        RecordingStore store = new RecordingStore(row());
        RecordingAudit audit = new RecordingAudit();
        ReadGateway gateway = gateway(store, List.of(scope(TYPE,
                        ScopePredicate.eq("org_unit_id", "D01"))),
                List.of(fields(new FieldPolicy(Map.of("phone",
                        new FieldAccess(MASKED, "hr.employee.masked"))))),
                audit);

        gateway.search(TYPE, search(), caller());

        assertThat(audit.events).hasSize(1);
        ReadAccessEvent event = audit.events.get(0);
        assertThat(event.operation()).isEqualTo(ReadAccessEvent.Operation.SEARCH);
        assertThat(event.scopeDigest()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(event.policyIds()).contains("hr.employee.masked");
        assertThat(event.filters()).containsEntry("status", "ACTIVE");
        assertThat(event.callerId()).isEqualTo("u-1");
    }

    @Test
    void auditFailureDoesNotBreakRead() {
        RecordingStore store = new RecordingStore(row());
        ReadAuditSink broken = event -> {
            throw new IllegalStateException("audit down");
        };

        assertThat(gateway(store, List.of(scope(TYPE, ScopePredicate.all())), List.of(), broken)
                .find(TYPE, ID, TemporalQuery.current(), caller())).isPresent();
    }

    @Test
    void searchRendersPageAndHistoryUsesSameScope() {
        RecordingStore store = new RecordingStore(row());
        ReadGateway gateway = gateway(store, List.of(scope(TYPE, ScopePredicate.all())));

        Page<TemporalRow> page = gateway.search(TYPE, search(), caller());
        List<TemporalRow> history = gateway.history(TYPE, ID, caller());

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items().get(0).fields()).doesNotContainKey("phone");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).fields()).doesNotContainKey("phone");
    }

    @Test
    void contextIsAvailableToResolver() {
        RecordingStore store = new RecordingStore(row());
        DataScopeResolver readsContext = new DataScopeResolver() {
            @Override
            public boolean supports(String objectType) {
                return true;
            }

            @Override
            public ScopePredicate resolve(String objectType, Caller caller,
                                          ScopeContext context) {
                return ScopePredicate.eq("org_unit_id",
                        context.get("org.unit").orElseThrow());
            }
        };

        try (ScopeContextHolder.Scope ignored = ScopeContextHolder.open(
                ScopeContext.of(Map.of("org.unit", "D01")))) {
            gateway(store, List.of(readsContext)).search(TYPE, search(), caller());
        }

        assertThat(ScopePredicates.matches(row(), store.lastScope,
                registry().require(TYPE).schema())).isTrue();
    }

    private static final class RecordingStore implements TemporalStore {

        private final TemporalRow row;

        private int calls;

        private ScopePredicate lastScope;

        private RecordingStore(TemporalRow row) {
            this.row = row;
        }

        @Override
        public Optional<TemporalRow> find(String objectType, UUID id, TemporalQuery query,
                                          ScopePredicate scope) {
            calls++;
            lastScope = scope;
            return Optional.of(row);
        }

        @Override
        public Page<TemporalRow> search(TemporalSearch search, ScopePredicate scope) {
            calls++;
            lastScope = scope;
            return new Page<>(List.of(row), 1, search.page().page(), search.page().size());
        }

        @Override
        public List<TemporalRow> history(String objectType, UUID id, ScopePredicate scope) {
            calls++;
            lastScope = scope;
            return List.of(row);
        }

        @Override
        public AppendResult append(AppendRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RecordingAudit implements ReadAuditSink {

        private final List<ReadAccessEvent> events = new ArrayList<>();

        @Override
        public void record(ReadAccessEvent event) {
            events.add(event);
        }
    }
}
