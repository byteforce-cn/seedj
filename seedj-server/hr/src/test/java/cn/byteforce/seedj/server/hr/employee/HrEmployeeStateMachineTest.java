package cn.byteforce.seedj.server.hr.employee;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.domain.AppendResult;
import cn.byteforce.seedj.domain.ObjectVersion;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.cqrs.CommandContext;
import cn.byteforce.seedj.module.ObjectModuleRegistry;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
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
 * 员工写路径规则：状态机、离职保护、入职日期与组织单元。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class HrEmployeeStateMachineTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private HrEmployeeCreateHandler createHandler;

    @Autowired
    private ObjectModuleRegistry registry;

    @Autowired
    private Clock clock;

    @Test
    @DisplayName("建档状态固定 pending_onboarding")
    void createStartsPendingOnboarding() throws Exception {
        String id = create("SM-1", """
                {"name":"张三","hire_date":"2026-10-01","org_unit_id":"D01"}
                """);

        mvc.perform(get("/objects/employee/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("pending_onboarding"))
                .andExpect(jsonPath("$.data.employee_no").value(
                        org.hamcrest.Matchers.startsWith("EMP-")));
    }

    @Test
    @DisplayName("离职后禁止改岗位：400 EIS-400-002")
    void terminatedEmployeeCannotChangeOrgUnit() throws Exception {
        String id = create("SM-2", """
                {"name":"李四","org_unit_id":"D01"}
                """);
        update(id, """
                {"status":"active"}
                """, "SM-2a");
        update(id, """
                {"status":"terminated"}
                """, "SM-2b");

        mvc.perform(post("/commands/hr.employee.update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(id, """
                                {"org_unit_id":"D02"}
                                """, "SM-2c")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EIS-400-002"))
                .andExpect(jsonPath("$.reason").value(
                        org.hamcrest.Matchers.containsString("terminated")));
    }

    @Test
    @DisplayName("非法状态迁移：pending_onboarding → terminated 被拒绝")
    void invalidStatusTransitionIsRejected() throws Exception {
        String id = create("SM-3", """
                {"name":"王五","org_unit_id":"D01"}
                """);

        mvc.perform(post("/commands/hr.employee.update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(id, """
                                {"status":"terminated"}
                                """, "SM-3a")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EIS-400-002"))
                .andExpect(jsonPath("$.reason").value(
                        org.hamcrest.Matchers.containsString("transition")));
    }

    @Test
    @DisplayName("未来入职日期被拒绝")
    void hireDateInFutureIsRejected() throws Exception {
        mvc.perform(post("/commands/hr.employee.create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"hr.employee.create",
                                 "payload":{"name":"赵六","hire_date":"2999-01-01"},
                                 "idempotency_key":"SM-4"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EIS-400-002"));
    }

    @Test
    @DisplayName("未知组织单元被拒绝")
    void unknownOrgUnitIsRejected() throws Exception {
        mvc.perform(post("/commands/hr.employee.create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"hr.employee.create",
                                 "payload":{"name":"钱七","org_unit_id":"D99"},
                                 "idempotency_key":"SM-5"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EIS-400-002"));
    }

    @Test
    @DisplayName("事件幂等键使用命令 ID 并递增")
    void createdEventKeyUsesCommandId() {
        UUID target = UUID.randomUUID();
        CommandContext context = new CommandContext(UUID.randomUUID(), target, Optional.empty(),
                Instant.parse("2026-11-06T09:00:00Z"), target, caller(), ScopePredicate.all(),
                registry.require("employee").schema(), () -> UUID.randomUUID());
        var request = createHandler.decide(new HrEmployeeCreate("张三", null, null, null,
                null, "D01", null), context);

        TemporalRow row = new TemporalRow("employee",
                new ObjectVersion(target, 1, context.now(), null, context.now(), null,
                        target, context.now(), null, null), request.fields());
        var events = createHandler.events(new HrEmployeeCreate("张三", null, null, null,
                null, "D01", null), new AppendResult(row, Optional.empty()), context);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).idempotencyKey())
                .isEqualTo(context.commandId() + ":hr.employee.created:0");
    }

    private String create(String key, String payload) throws Exception {
        String response = mvc.perform(post("/commands/hr.employee.create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"hr.employee.create",
                                 "payload":%s,
                                 "idempotency_key":"%s"}
                                """.formatted(payload, key)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.locator");
    }

    private void update(String id, String payload, String key) throws Exception {
        mvc.perform(post("/commands/hr.employee.update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(id, payload, key)))
                .andExpect(status().isAccepted());
    }

    private static String updateBody(String id, String payload, String key) {
        return """
                {"type":"hr.employee.update",
                 "payload":{"employee_id":"%s",%s},
                 "idempotency_key":"%s"}
                """.formatted(id, payload.strip().substring(1, payload.strip().length() - 1), key);
    }

    private static Caller caller() {
        return new Caller("u-1", "tester", "default", Set.of(), Set.of("hr.employee.write"),
                Set.of(), Set.of(Caller.DEFAULT_AMR), Caller.DEFAULT_ACR,
                Map.of("org_units", List.of("D01")));
    }
}
