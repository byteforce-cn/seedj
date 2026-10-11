package cn.byteforce.seedj.starter.resource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 资源服务端到端契约测试：401/403 统一输出、permit 路径、JWT 身份进入请求上下文、
 * 方法级授权（含 MVC 侧异常处理路径）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@SpringBootTest(classes = ResourceTestApplication.class,
        properties = "byteforce.security.resource.permit-paths[0]=/public")
@AutoConfigureMockMvc
class ResourceServerAutoConfigurationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anonymousRequestIsRejectedWithUnifiedBody() throws Exception {
        mockMvc.perform(get("/me").header("X-Trace-Id", "sec-trace-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("Bearer")))
                .andExpect(header().string("X-Trace-Id", "sec-trace-1"))
                .andExpect(jsonPath("$.code").value(10002))
                .andExpect(jsonPath("$.message").value("未认证或登录已过期"))
                .andExpect(jsonPath("$.traceId").value("sec-trace-1"));
    }

    @Test
    void permitPathAllowsAnonymous() throws Exception {
        mockMvc.perform(get("/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value("public-ok"));
    }

    @Test
    void jwtClaimsPopulateRequestContext() throws Exception {
        mockMvc.perform(get("/me")
                        .with(jwt().jwt(jwt -> jwt
                                .claim("userId", "42")
                                .claim("tenantId", "7")
                                .claim("preferred_username", "alice")))
                        .header("X-Trace-Id", "sec-trace-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value("sec-trace-2"))
                // Long 全局字符串化（starter-json 契约）
                .andExpect(jsonPath("$.userId").value("42"))
                .andExpect(jsonPath("$.tenantId").value("7"))
                .andExpect(jsonPath("$.username").value("alice"));
    }

    @Test
    void authenticatedWithoutAuthorityIsForbiddenWithUnifiedBody() throws Exception {
        mockMvc.perform(get("/admin").with(jwt()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10003))
                .andExpect(jsonPath("$.message").value("无权限访问"));
    }

    @Test
    void authorityGrantsAccess() throws Exception {
        mockMvc.perform(get("/admin")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value("admin-ok"));
    }

    @Test
    void authoritiesClaimIsConsumedByDefaultChain() throws Exception {
        mockMvc.perform(get("/admin").header(HttpHeaders.AUTHORIZATION, "Bearer authorities-claim"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value("admin-ok"));
    }

    @Test
    void bearerTokenWithoutAuthoritiesClaimIsForbidden() throws Exception {
        mockMvc.perform(get("/admin").header(HttpHeaders.AUTHORIZATION, "Bearer plain-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10003));
    }

    @Test
    void anonymousAdminStillMapsTo401Not403() throws Exception {
        mockMvc.perform(get("/admin"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10002));
    }

    @Test
    void identityAbsentWhenClaimsMissing() throws Exception {
        mockMvc.perform(get("/me").with(jwt().jwt(jwt -> jwt.subject("plain-sub"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(nullValue()))
                .andExpect(jsonPath("$.tenantId").value(nullValue()))
                .andExpect(jsonPath("$.username").value("plain-sub"));
    }
}
