package cn.byteforce.seedj.starter.domain.web;

import cn.byteforce.seedj.starter.web.ContractErrorHandler;
import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.ScopeContext;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ObjectSchemaRegistry;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.infra.local.domain.InMemoryTemporalStore;
import cn.byteforce.seedj.module.ObjectModuleRegistry;
import cn.byteforce.seedj.module.ObjectModules;
import cn.byteforce.seedj.read.DataScopeResolver;
import cn.byteforce.seedj.read.ReadAuditSink;
import cn.byteforce.seedj.read.ReadGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 对象时点查询的线格式与 404/409 语义（经 ReadGateway）。
 */
class ObjectQueryControllerTest {

    private static final Instant T0 = Instant.parse("2026-10-08T09:00:00Z");

    private static final UUID ID = UUID.fromString("018f2e3a-1234-7000-8000-000000000031");

    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");

    private static final Caller CALLER = new Caller("u-1", "alice", "default", Set.of(),
            Set.of("probe.read"), Set.of(), Set.of("pwd"), "urn:seedj:acr:pwd", Map.of());

    private final AtomicReference<Instant> now = new AtomicReference<>(T0);

    private final Clock clock = now::get;

    private final ObjectModuleRegistry registry = registry();

    private final InMemoryTemporalStore store = new InMemoryTemporalStore(clock,
            new ObjectSchemaRegistry(List.of(registry.require("probe").schema())));

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        now.set(T0);
        store.clear();
        DataScopeResolver all = new DataScopeResolver() {
            @Override
            public boolean supports(String objectType) {
                return true;
            }

            @Override
            public ScopePredicate resolve(String objectType, Caller caller,
                                          ScopeContext context) {
                return ScopePredicate.all();
            }
        };
        ReadGateway gateway = new ReadGateway(registry, store, List.of(all), List.of(),
                ReadAuditSink.noop(), clock, () -> UUID.randomUUID());
        mvc = MockMvcBuilders.standaloneSetup(
                        new ObjectQueryController(gateway, () -> Optional.of(CALLER)))
                .setControllerAdvice(new ContractErrorHandler())
                .build();
    }

    @Test
    @DisplayName("当前对象：snake_case 元结构 + data")
    void returnsCurrentObjectWithSnakeCaseData() throws Exception {
        seedTwoVersions();

        mvc.perform(get("/objects/probe/" + ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object_type").value("probe"))
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.valid_from").value(T0.plusSeconds(60).toString()))
                .andExpect(jsonPath("$.valid_to").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.recorded_at").value(T0.plusSeconds(60).toString()))
                .andExpect(jsonPath("$.superseded_at").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.name").value("v2"));
    }

    @Test
    @DisplayName("as_of 半开区间：边界命中后继版本")
    void asOfUsesHalfOpenBounds() throws Exception {
        seedTwoVersions();

        mvc.perform(get("/objects/probe/" + ID).queryParam("as_of", T0.plusSeconds(59).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.data.name").value("v1"));

        mvc.perform(get("/objects/probe/" + ID).queryParam("as_of", T0.plusSeconds(60).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.data.name").value("v2"));
    }

    @Test
    @DisplayName("recorded_as_of 返回系统时间切点处的信念")
    void recordedAsOfReturnsHistoricalBelief() throws Exception {
        seedTwoVersions();

        mvc.perform(get("/objects/probe/" + ID)
                        .queryParam("recorded_as_of", T0.plusSeconds(30).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.data.name").value("v1"));

        mvc.perform(get("/objects/probe/" + ID)
                        .queryParam("recorded_as_of", T0.plusSeconds(90).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    @DisplayName("对象不存在：404 契约信封")
    void missingObjectIs404() throws Exception {
        mvc.perform(get("/objects/probe/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EIS-404-001"))
                .andExpect(jsonPath("$.message").value("object not found"));
    }

    @Test
    @DisplayName("min_version 未追平：409 + Retry-After + reason_code")
    void minVersionNotReadableIs409WithRetryAfter() throws Exception {
        seedTwoVersions();

        mvc.perform(get("/objects/probe/" + ID).queryParam("min_version", "3"))
                .andExpect(status().isConflict())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("EIS-409-003"))
                .andExpect(jsonPath("$.details.reason_code").value("EIS-409-003"))
                .andExpect(jsonPath("$.details.minVersion").value(3))
                .andExpect(jsonPath("$.version").doesNotExist());
    }

    @Test
    @DisplayName("非法时点：400 契约信封")
    void invalidTimestampIs400() throws Exception {
        mvc.perform(get("/objects/probe/" + ID).queryParam("as_of", "not-a-time"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EIS-400-001"))
                .andExpect(jsonPath("$.message").value("invalid as_of"));
    }

    @Test
    @DisplayName("未知对象类型：404 且消息含类型名")
    void unknownObjectTypeIs404WithMessageContainingType() throws Exception {
        mvc.perform(get("/objects/missing/" + ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EIS-404-001"))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("missing")));
    }

    @Test
    @DisplayName("列表：size=1 返回 1 条、total=2、page=1")
    void listReturnsPage() throws Exception {
        seed("a", T0);
        seed("b", T0);

        mvc.perform(get("/objects/probe").queryParam("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(1));
    }

    @Test
    @DisplayName("列表：未知过滤字段 400；size>200 400")
    void listRejectsUnknownFilterAndOversizePage() throws Exception {
        seed("a", T0);

        mvc.perform(get("/objects/probe").queryParam("missing", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EIS-400-001"));

        mvc.perform(get("/objects/probe").queryParam("size", "201"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EIS-400-001"));
    }

    @Test
    @DisplayName("列表：as_of 只返回当时有效的行")
    void listAppliesAsOf() throws Exception {
        store.append(AppendRequest.create("probe", ID, T0, null, ACTOR, Map.of("name", "v1")));
        now.set(T0.plusSeconds(60));
        store.append(AppendRequest.correction("probe", ID, T0.plusSeconds(60), null, ACTOR,
                Map.of("name", "v2")));

        mvc.perform(get("/objects/probe").queryParam("as_of", T0.plusSeconds(30).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].data.name").value("v1"));
    }

    private void seed(String name, Instant validFrom) {
        store.append(AppendRequest.create("probe", UUID.randomUUID(), validFrom, null, ACTOR,
                Map.of("name", name)));
    }

    private void seedTwoVersions() {
        store.append(AppendRequest.create("probe", ID, T0, T0.plusSeconds(60), ACTOR,
                Map.of("name", "v1")));
        now.set(T0.plusSeconds(60));
        store.append(AppendRequest.correction("probe", ID, T0.plusSeconds(60), null, ACTOR,
                Map.of("name", "v2")));
    }

    private static ObjectModuleRegistry registry() {
        ObjectModules modules = ObjectModules.create();
        modules.object("probe", "probe_object")
                .readPermission("probe.read")
                .field("name", FieldType.TEXT).filterable().sortable()
                .field("status", FieldType.TEXT).filterable()
                .field("hire_date", FieldType.DATE).filterable();
        return modules.freeze();
    }
}
