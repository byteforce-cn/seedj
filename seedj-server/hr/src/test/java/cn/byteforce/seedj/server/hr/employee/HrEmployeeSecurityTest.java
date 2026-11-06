package cn.byteforce.seedj.server.hr.employee;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.byteforce.seedj.core.security.Caller;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** local profile + 关闭 dev auth：真实安全链下的 401/403/202。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "seedj.biz.dev-auth.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("local")
class HrEmployeeSecurityTest {

    private static final String BODY = """
            {"type":"hr.employee.create","payload":{"name":"张三"},
             "idempotency_key":"security-1"}
            """;

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("无认证：401 EIS-401-001")
    void unauthenticatedIs401() throws Exception {
        mvc.perform(post("/commands/hr.employee.create")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("EIS-401-001"));
    }

    @Test
    @DisplayName("有认证但无 hr.employee.write：403 EIS-403-001")
    void authenticatedWithoutPermissionIs403() throws Exception {
        mvc.perform(post("/commands/hr.employee.create")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .with(authentication(token(caller(Set.of("hr.employee.read"))))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EIS-403-001"));
    }

    @Test
    @DisplayName("有权限：202")
    void authorizedIs202() throws Exception {
        mvc.perform(post("/commands/hr.employee.create")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .with(authentication(token(caller(Set.of("hr.employee.write"))))))
                .andExpect(status().isAccepted());
    }

    private static UsernamePasswordAuthenticationToken token(Caller caller) {
        List<GrantedAuthority> authorities = caller.permissions().stream()
                .map(permission -> (GrantedAuthority) new SimpleGrantedAuthority(
                        "PERM_" + permission))
                .toList();
        return new UsernamePasswordAuthenticationToken(caller, null, authorities);
    }

    private static Caller caller(Set<String> permissions) {
        return new Caller("018f2e3a-0000-7000-8000-00000000beef", "tester", "default",
                Set.of(), permissions, Set.of(), Set.of(Caller.DEFAULT_AMR),
                Caller.DEFAULT_ACR, Map.of("org_units", List.of("D01")));
    }
}
