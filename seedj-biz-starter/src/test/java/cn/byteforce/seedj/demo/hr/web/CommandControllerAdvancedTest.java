package cn.byteforce.seedj.demo.hr.web;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.clock.Clocks;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.CallerIdCodec;
import cn.byteforce.seedj.cqrs.CommandAgent;
import cn.byteforce.seedj.cqrs.CommandCatalog;
import cn.byteforce.seedj.cqrs.CommandPolicy;
import cn.byteforce.seedj.cqrs.CommandRuntime;
import cn.byteforce.seedj.cqrs.spi.CommandStore;
import cn.byteforce.seedj.demo.hr.app.OnboardingAgent;
import cn.byteforce.seedj.demo.hr.config.HrEmployeeModule;
import cn.byteforce.seedj.demo.hr.domain.EmployeeId;
import cn.byteforce.seedj.demo.hr.domain.EmployeeIdCodec;
import cn.byteforce.seedj.demo.hr.domain.EmployeeRepository;
import cn.byteforce.seedj.demo.hr.domain.EventSourcedEmployeeRepository;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.domain.event.DomainEvent;
import cn.byteforce.seedj.eventbus.EventMessage;
import cn.byteforce.seedj.eventbus.EventPublisher;
import cn.byteforce.seedj.infra.local.cqrs.InMemoryCommandStore;
import cn.byteforce.seedj.infra.local.cqrs.InMemoryEventStore;
import cn.byteforce.seedj.infra.local.domain.InMemoryTemporalStore;
import cn.byteforce.seedj.infra.local.id.Uuid7IdGenerator;
import cn.byteforce.seedj.lock.LockProvider;
import cn.byteforce.seedj.module.ObjectModuleRegistry;
import cn.byteforce.seedj.module.ObjectModules;
import cn.byteforce.seedj.starter.cqrs.web.CommandController;
import cn.byteforce.seedj.starter.web.ContractErrorHandler;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 事件溯源命令的 HTTP 面：同一个 {@code /commands/{type}} 入口，回执用 {@code target_ref}。
 *
 * <p>纯 JUnit + standalone MockMvc：不需要 {@code @SpringBootTest}，因此不改变
 * plain/spring 测试比预算。</p>
 */
class CommandControllerAdvancedTest {

    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");

    private static final Caller CALLER = new Caller(ACTOR.toString(), "alice", "default",
            Set.of(), Set.of("hr.employee.write"), Set.of(), Set.of("pwd"),
            Caller.DEFAULT_ACR, Map.of());

    private MockMvc mvc;

    private InMemoryEventStore<EmployeeId> events;

    @BeforeEach
    void setUp() {
        Clock clock = Clocks.fixed(Instant.parse("2026-11-07T09:00:00Z"));
        events = new InMemoryEventStore<>(new EmployeeIdCodec());
        EmployeeRepository repository = new EventSourcedEmployeeRepository(events);
        OnboardingAgent agent = new OnboardingAgent(repository, new NoopPublisher(),
                new EmployeeIdCodec(), CommandAgent.Options.<EmployeeId>defaults()
                .withLockProvider(LockProvider.disabled()));
        ObjectModules modules = ObjectModules.create();
        new HrEmployeeModule(agent).configure(modules);
        ObjectModuleRegistry registry = modules.freeze();

        CommandStore commandStore = new InMemoryCommandStore();
        CommandRuntime runtime = new CommandRuntime(CommandRuntime.Options.forTests(clock,
                new Uuid7IdGenerator(clock), commandStore, new InMemoryTemporalStore(clock)));
        CommandCatalog catalog = new CommandCatalog(registry.commands(),
                CommandPolicy.permissions(), runtime,
                (objectType, caller) -> ScopePredicate.all());
        CommandController controller = new CommandController(catalog, commandStore,
                () -> Optional.of(CALLER), CallerIdCodec.uuid());
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ContractErrorHandler())
                .build();
    }

    @Test
    @DisplayName("POST /commands 返回 202 + target_ref + version，不返回 locator")
    void postReturnsTargetRefAndVersion() throws Exception {
        mvc.perform(post("/commands/hr.employee.onboard")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("http-adv-1", "emp-1", "E-001")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.command_id").isNotEmpty())
                .andExpect(jsonPath("$.status").value("accepted"))
                .andExpect(jsonPath("$.target_ref").value("emp-1"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.locator").doesNotExist())
                .andExpect(jsonPath("$.event_ids").isEmpty());

        assertThat(events.read(cn.byteforce.seedj.cqrs.StreamId.of("hr.employee",
                EmployeeId.of("emp-1"))).version()).isEqualTo(1);
    }

    @Test
    @DisplayName("同键同载荷重放：同 command_id 与 target_ref，流版本不增加")
    void replayReturnsSameCommandIdAndTargetRef() throws Exception {
        String first = mvc.perform(post("/commands/hr.employee.onboard")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("http-adv-2", "emp-2", "E-002")))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String commandId = JsonPath.read(first, "$.command_id");

        mvc.perform(post("/commands/hr.employee.onboard")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("http-adv-2", "emp-2", "E-002")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.command_id").value(commandId))
                .andExpect(jsonPath("$.target_ref").value("emp-2"))
                .andExpect(jsonPath("$.version").value(1));

        assertThat(events.read(cn.byteforce.seedj.cqrs.StreamId.of("hr.employee",
                EmployeeId.of("emp-2"))).version()).isEqualTo(1);
    }

    @Test
    @DisplayName("GET /commands/{id} 返回 target_ref")
    void getCommandStatusIncludesTargetRef() throws Exception {
        String created = mvc.perform(post("/commands/hr.employee.onboard")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("http-adv-3", "emp-3", "E-003")))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String commandId = JsonPath.read(created, "$.command_id");

        mvc.perform(get("/commands/" + commandId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.target_ref").value("emp-3"))
                .andExpect(jsonPath("$.status").value("accepted"));
    }

    @Test
    @DisplayName("advanced 命令带 targets：400")
    void advancedTargetsAreRejected() throws Exception {
        mvc.perform(post("/commands/hr.employee.onboard")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"hr.employee.onboard",
                                 "payload":{"employee_id":"emp-4","employee_no":"E-004",
                                            "name":"张三","department":"D01",
                                            "position":"P1","hire_date":"2024-03-01"},
                                 "idempotency_key":"http-adv-4",
                                 "targets":["018f2e3a-1234-7000-8000-000000000001"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EIS-400-001"));
    }

    private static String body(String key, String employeeId, String employeeNo) {
        return """
                {"type":"hr.employee.onboard",
                 "payload":{"employee_id":"%s","employee_no":"%s","name":"张三",
                            "department":"研发中心","position":"后端工程师",
                            "hire_date":"2024-03-01"},
                 "idempotency_key":"%s"}
                """.formatted(employeeId, employeeNo, key);
    }

    private static final class NoopPublisher implements EventPublisher {

        @Override
        public void publish(DomainEvent event) {
        }

        @Override
        public void publish(EventMessage message) {
        }
    }
}
