package cn.byteforce.seedj.server.hr.employee;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.byteforce.seedj.core.security.Caller;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** local profile：create/get/update/as_of/幂等/错误矩阵端到端。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class HrEmployeeHttpTest {

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("create 202 → get 200（工号存在、hash 不返回）→ update 202 version=2")
    void createGetUpdateRoundTrip() throws Exception {
        String created = mvc.perform(post("/commands/hr.employee.create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"hr.employee.create",
                                 "payload":{"name":"张三","hire_date":"2026-10-01",
                                            "org_unit_id":"D01",
                                            "id_number":"110101199001011234"},
                                 "idempotency_key":"http-1"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.locator");

        mvc.perform(get("/objects/employee/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.employee_no").isNotEmpty())
                .andExpect(jsonPath("$.data.id_number_hash").doesNotExist())
                .andExpect(jsonPath("$.data.name").value("张三"));

        mvc.perform(post("/commands/hr.employee.update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"hr.employee.update",
                                 "payload":{"employee_id":"%s","name":"张三丰"},
                                 "idempotency_key":"http-2"}
                                """.formatted(id)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.locator").value(id));
    }

    @Test
    @DisplayName("HR 读面：list 与 typed get 返回视图，不含证件散列")
    void typedListAndGet() throws Exception {
        String created = mvc.perform(post("/commands/hr.employee.create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"hr.employee.create",
                                 "payload":{"name":"张三","hire_date":"2026-10-01",
                                            "org_unit_id":"D01",
                                            "id_number":"110101199001011234"},
                                 "idempotency_key":"http-typed-1"}
                                """))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.locator");

        mvc.perform(get("/api/hr/employees").queryParam("page", "1").queryParam("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.items[0].employee_no").isNotEmpty())
                .andExpect(jsonPath("$.items[0].id_number_hash").doesNotExist());

        mvc.perform(get("/objects/employee/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("张三"))
                .andExpect(jsonPath("$.data.hire_date").value("2026-10-01"))
                .andExpect(jsonPath("$.data.id_number_hash").doesNotExist());
    }

    @Test
    @DisplayName("缺少 hr.employee.read：403 EIS-403-001")
    void missingReadPermissionIs403() throws Exception {
        Caller writeOnly = new Caller("018f2e3a-0000-7000-8000-00000000cafe", "writer",
                "default", Set.of(), Set.of("hr.employee.write"), Set.of(),
                Set.of(Caller.DEFAULT_AMR), Caller.DEFAULT_ACR, Map.of());
        var authentication = new UsernamePasswordAuthenticationToken(writeOnly, null,
                List.of(new SimpleGrantedAuthority("PERM_hr.employee.write")));

        mvc.perform(get("/api/hr/employees")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(authentication)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EIS-403-001"));
    }

    @Test
    @DisplayName("同键同载荷重放：同 command_id；同键不同载荷：409")
    void idempotencyReplayAndConflict() throws Exception {
        String body = """
                {"type":"hr.employee.create","payload":{"name":"李四"},
                 "idempotency_key":"http-3"}
                """;
        String first = mvc.perform(post("/commands/hr.employee.create")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String commandId = JsonPath.read(first, "$.command_id");

        mvc.perform(post("/commands/hr.employee.create")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.command_id").value(commandId));

        mvc.perform(post("/commands/hr.employee.create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"hr.employee.create","payload":{"name":"王五"},
                                 "idempotency_key":"http-3"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("非法 hire_date：400 EIS-400-001")
    void invalidHireDateIs400() throws Exception {
        mvc.perform(post("/commands/hr.employee.create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"hr.employee.create",
                                 "payload":{"name":"张三","hire_date":"not-a-date"},
                                 "idempotency_key":"http-4"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EIS-400-001"));
    }

    @Test
    @DisplayName("未知命令类型：400 EIS-400-001")
    void unknownCommandTypeIs400() throws Exception {
        mvc.perform(post("/commands/hr.employee.unknown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"hr.employee.unknown","payload":{},"idempotency_key":"x"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EIS-400-001"));
    }

    @Test
    @DisplayName("更新不存在对象：404 EIS-404-001 契约信封")
    void updateMissingObjectIs404() throws Exception {
        mvc.perform(post("/commands/hr.employee.update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"hr.employee.update",
                                 "payload":{"employee_id":"%s","name":"无人"},
                                 "idempotency_key":"http-5"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EIS-404-001"))
                .andExpect(jsonPath("$.details.reason_code").value("EIS-404-001"));
    }

    @Test
    @DisplayName("as_of 读取创建时的版本")
    void asOfReadReturnsHistoricalVersion() throws Exception {
        String created = mvc.perform(post("/commands/hr.employee.create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"hr.employee.create",
                                 "payload":{"name":"张三","hire_date":"2026-10-01",
                                            "org_unit_id":"D01"},
                                 "idempotency_key":"http-6"}
                                """))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.locator");

        mvc.perform(post("/commands/hr.employee.update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"hr.employee.update",
                                 "payload":{"employee_id":"%s","name":"张三丰"},
                                 "idempotency_key":"http-7"}
                                """.formatted(id)))
                .andExpect(status().isAccepted());

        mvc.perform(get("/objects/employee/" + id)
                        .queryParam("as_of", "2026-10-01T12:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("张三"))
                .andExpect(jsonPath("$.version").value(1));
    }
}
