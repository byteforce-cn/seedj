package cn.byteforce.seedj.server.hr.employee;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.ScopeContext;
import cn.byteforce.seedj.core.security.ScopeContextHolder;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.ObjectSchemaRegistry;
import cn.byteforce.seedj.domain.Page;
import cn.byteforce.seedj.domain.PageRequest;
import cn.byteforce.seedj.domain.SortDirection;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.domain.TemporalSearch;
import cn.byteforce.seedj.infra.local.domain.InMemoryTemporalStore;
import cn.byteforce.seedj.module.ObjectModuleRegistry;
import cn.byteforce.seedj.module.ObjectModules;
import cn.byteforce.seedj.read.FieldAccess;
import cn.byteforce.seedj.read.FieldPolicy;
import cn.byteforce.seedj.read.FieldPolicyResolver;
import cn.byteforce.seedj.read.ReadAuditSink;
import cn.byteforce.seedj.read.ReadGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HR 读面：组织范围、字段隐私与分页 total 的端到端语义。
 */
class HrEmployeeReadE2eTest {

    private static final Instant T0 = Instant.parse("2026-11-06T09:00:00Z");

    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");

    private static final Caller MANAGER = new Caller("u-1", "manager", "default", Set.of(),
            Set.of("hr.employee.read"), Set.of(), Set.of("pwd"), "urn:seedj:acr:pwd", Map.of());

    private final AtomicReference<Instant> now = new AtomicReference<>(T0);

    private final Clock clock = now::get;

    private final ObjectModuleRegistry registry = registry();

    private final InMemoryTemporalStore store = new InMemoryTemporalStore(clock,
            new ObjectSchemaRegistry(List.of(registry.require("employee").schema())));

    private final FieldPolicyResolver phoneMasked = new FieldPolicyResolver() {
        @Override
        public boolean supports(String objectType) {
            return "employee".equals(objectType);
        }

        @Override
        public FieldPolicy resolve(String objectType, Caller caller, ScopeContext context) {
            return new FieldPolicy(Map.of("phone", new FieldAccess(
                    FieldAccess.Action.MASKED, "hr.employee.phone.masked")));
        }
    };

    private final ReadGateway gateway = new ReadGateway(registry, store,
            List.of(new HrOrgUnitScopeResolver()), List.of(phoneMasked), ReadAuditSink.noop(),
            clock, () -> UUID.randomUUID());

    @BeforeEach
    void seed() {
        now.set(T0);
        store.clear();
        seedRow("D01", "13800138000", "hash-1");
        seedRow("D02", "13900139000", "hash-2");
        seedRow("D03", "13700137000", "hash-3");
    }

    @Test
    void managerSeesSubtree() {
        Page<TemporalRow> page = search(List.of("D01", "D02"), new PageRequest(1, 20));

        assertThat(page.items()).hasSize(2);
        assertThat(page.total()).isEqualTo(2);
    }

    @Test
    void employeeSeesOwnUnitOnly() {
        Page<TemporalRow> page = search(List.of("D01"), new PageRequest(1, 20));

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).fields()).containsEntry("org_unit_id", "D01");
    }

    @Test
    void noOrgUnitsYieldsEmpty() {
        Page<TemporalRow> page = search(List.of(), new PageRequest(1, 20));

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isZero();
    }

    @Test
    void phoneIsMasked() {
        Page<TemporalRow> page = search(List.of("D01"), new PageRequest(1, 20));

        assertThat(page.items().get(0).fields()).containsEntry("phone", "****8000");
    }

    @Test
    void writeOnlyHashIsAbsent() {
        Page<TemporalRow> page = search(List.of("D01"), new PageRequest(1, 20));

        assertThat(page.items().get(0).fields()).doesNotContainKey("id_number_hash");
    }

    @Test
    void pageTotalReflectsScope() {
        Page<TemporalRow> page = search(List.of("D01", "D02"), new PageRequest(1, 1));

        assertThat(page.items()).hasSize(1);
        assertThat(page.total()).isEqualTo(2);
    }

    private Page<TemporalRow> search(List<String> units, PageRequest pageRequest) {
        try (ScopeContextHolder.Scope ignored = ScopeContextHolder.open(
                ScopeContext.of(Map.of("org.units", units)))) {
            return gateway.search("employee", new TemporalSearch("employee", Map.of(),
                    null, null, null, "employee_no", SortDirection.ASC, pageRequest), MANAGER);
        }
    }

    private void seedRow(String unit, String phone, String hash) {
        store.append(AppendRequest.create("employee", UUID.randomUUID(), T0, null, ACTOR,
                Map.of("employee_no", "E-" + unit, "name", "Employee " + unit,
                        "status", "ACTIVE", "phone", phone, "id_number_hash", hash,
                        "org_unit_id", unit)));
    }

    private static ObjectModuleRegistry registry() {
        ObjectModules modules = ObjectModules.create();
        var derived = new cn.byteforce.seedj.core.security.DerivedFieldRegistry(Map.of());
        var orgUnits = (OrgUnitDirectory) unit -> true;
        var create = new HrEmployeeCreateHandler(() -> UUID.randomUUID(),
                () -> "E-1", derived, orgUnits);
        var update = new HrEmployeeUpdateHandler(derived, orgUnits);
        new HrEmployeeModule(create, update).configure(modules);
        return modules.freeze();
    }
}
