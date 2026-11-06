package cn.byteforce.seedj.biz.security;

import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.CallerProvider;
import cn.byteforce.seedj.core.security.SecurityAuditEvent;
import cn.byteforce.seedj.core.security.SecurityErrorCode;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.security.memory.InMemorySecurityAuditSink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 401/403 响应形状与拒绝审计：HTTP 语义在这里定死，前端只写一套解析。
 */
class JsonSecurityResponseTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final InMemorySecurityAuditSink sink = new InMemorySecurityAuditSink();
    private final AtomicInteger sequence = new AtomicInteger();

    private final BizSecurityProperties properties = new BizSecurityProperties(
            true, null, null, null, "t-1", true);

    private final CallerProvider caller = () -> Optional.of(new Caller("u-1", "alice", "t-1",
            java.util.Set.of("ADMIN"), java.util.Set.of(), java.util.Set.of(), java.util.Set.of(),
            Caller.DEFAULT_ACR, Map.of()));

    private SecurityDenialAudit audit() {
        IdGenerator<String> ids = () -> "evt-" + sequence.incrementAndGet();
        return new SecurityDenialAudit(provider(sink), provider(ids), caller, properties);
    }

    /** 最小 ObjectProvider：单值为空/非空两种状态都覆盖。 */
    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }
        };
    }

    @Test
    @DisplayName("缺少凭证：401 + WWW-Authenticate + 固定 JSON 形状")
    void missingCredentialsProducesJson401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new JsonAuthenticationEntryPoint(json, audit())
                .commence(request, response, new InsufficientAuthenticationException("no token"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).startsWith("Bearer");
        assertThat(body(response))
                .containsEntry("code", "EIS-401-001")
                .containsEntry("message", "authentication required");
        assertThat(auditEvents()).singleElement()
                .satisfies(event -> {
                    assertThat(event.outcome()).isEqualTo(SecurityAuditEvent.OUTCOME_DENIED);
                    assertThat(event.reason())
                            .isEqualTo(SecurityErrorCode.AUTHENTICATION_REQUIRED.name());
                    assertThat(event.resourceId()).isEqualTo("/api/orders");
                });
    }

    @Test
    @DisplayName("凭证无效：401 + AUTHENTICATION_FAILED")
    void invalidCredentialsProducesFailedCode() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new JsonAuthenticationEntryPoint(json, audit())
                .commence(request, response, new BadCredentialsException("bad token"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(body(response).get("code"))
                .isEqualTo("EIS-401-001");
    }

    @Test
    @DisplayName("权限不足：403 + ACCESS_DENIED，与 401 同形")
    void accessDeniedProducesJson403() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/orders/1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new JsonAccessDeniedHandler(json, audit())
                .handle(request, response, new AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(body(response))
                .containsEntry("code", "EIS-403-001")
                .containsEntry("message", "access denied");
        assertThat(body(response).get("details")).isInstanceOf(Map.class);
        assertThat(auditEvents()).singleElement()
                .satisfies(event -> assertThat(event.action()).isEqualTo("http.delete"));
    }

    @Test
    @DisplayName("关闭审计时不写事件")
    void auditCanBeDisabled() throws Exception {
        BizSecurityProperties noAudit = new BizSecurityProperties(
                true, null, null, null, "t-1", false);
        SecurityDenialAudit denialAudit = new SecurityDenialAudit(
                provider(sink), provider((IdGenerator<String>) () -> "x"), caller, noAudit);

        denialAudit.recordDenial(new MockHttpServletRequest("GET", "/api/x"),
                SecurityErrorCode.ACCESS_DENIED);

        assertThat(sink.events()).isEmpty();
    }

    private List<SecurityAuditEvent> auditEvents() {
        return sink.events();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(MockHttpServletResponse response) {
        return json.readValue(
                new String(response.getContentAsByteArray(), StandardCharsets.UTF_8),
                Map.class);
    }
}
