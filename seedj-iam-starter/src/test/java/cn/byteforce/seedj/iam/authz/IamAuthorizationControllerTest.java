package cn.byteforce.seedj.iam.authz;

import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.CallerProvider;
import cn.byteforce.seedj.iam.IamRole;
import cn.byteforce.seedj.iam.memory.InMemoryIamAttributeSource;
import cn.byteforce.seedj.iam.memory.InMemoryIamAuthorizationRepository;
import cn.byteforce.seedj.iam.memory.InMemoryIamBindingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 授权 HTTP 面：snake_case 字段、原因码与属性端点状态映射。
 */
class IamAuthorizationControllerTest {

    private static final Instant JAN = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant OCT15 = Instant.parse("2026-10-15T12:00:00Z");

    private final InMemoryIamBindingStore bindings = new InMemoryIamBindingStore();
    private final InMemoryIamAuthorizationRepository roles = new InMemoryIamAuthorizationRepository();
    private final InMemoryIamAttributeSource attributes = new InMemoryIamAttributeSource();

    private IamContractAuthorizationService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        bindings.clear();
        attributes.clear();
        roles.createRole(IamRole.create("r-approver", "default", "approver", "审批人", "",
                null, false));
        roles.createRole(IamRole.create("r-requester", "default", "requester", "申请人", "",
                null, false));
        roles.grantPermission("r-approver", "purchase.request.approve");
        roles.grantPermission("r-requester", "purchase.request.create");
        bindings.save(new IamBinding("b-away", "away", "r-approver",
                Map.of("department", "D001"), JAN, null, JAN, null));
        bindings.save(new IamBinding("b-requester", "requester", "r-requester",
                Map.of(), JAN, null, JAN, null));
        attributes.save(new AttributeSnapshot("worker", OCT15, OCT15,
                Instant.parse("2026-12-01T00:00:00Z"), "hr", "1.0.0",
                Map.of("primary_position", Map.of("grade", "P5"))));

        service = new IamContractAuthorizationService(bindings, roles, attributes, List.of(),
                SodRuleSet.defaults());
        Caller serviceCaller = new Caller("svc", "svc", "default", Set.of(),
                Set.of("hr.attributes.read"), Set.of(), Set.of("pwd"), Caller.DEFAULT_ACR,
                Map.of());
        mvc = MockMvcBuilders.standaloneSetup(new IamAuthorizationController(service,
                callerProvider(serviceCaller))).build();
    }

    @Test
    @DisplayName("/resolve 返回 roles / permissions / sod_warnings")
    void resolveReturnsSnakeCaseFields() throws Exception {
        mvc.perform(post("/resolve").contentType(MediaType.APPLICATION_JSON).content("""
                        {"principal":"away","as_of":"2026-10-15T12:00:00Z"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("approver"))
                .andExpect(jsonPath("$.permissions[0]").value("purchase.request.approve"))
                .andExpect(jsonPath("$.sod_warnings").isArray());
    }

    @Test
    @DisplayName("/check 返回 allowed / reason / reason_code / attribute_state")
    void checkReturnsReasonCodeAndAttributeState() throws Exception {
        mvc.perform(post("/check").contentType(MediaType.APPLICATION_JSON).content("""
                        {"principal":"requester","permission":"purchase.request.create",
                         "as_of":"2026-10-15T12:00:00Z"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.reason").value("permission granted"))
                .andExpect(jsonPath("$.reason_code").value("ALLOWED"))
                .andExpect(jsonPath("$.attribute_state").value("not_required"));
    }

    @Test
    @DisplayName("/check 拒绝时 reason_code=NO_PERMISSION")
    void checkDeniedReturnsNoPermission() throws Exception {
        mvc.perform(post("/check").contentType(MediaType.APPLICATION_JSON).content("""
                        {"principal":"requester","permission":"purchase.request.approve",
                         "as_of":"2026-10-15T12:00:00Z"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.reason_code").value("NO_PERMISSION"));
    }

    @Test
    @DisplayName("属性缺失返回 409 + ATTRIBUTE_MISSING")
    void attributesMissingIs409() throws Exception {
        mvc.perform(get("/principals/missing/attributes")
                        .param("as_of", "2026-10-15T12:00:00Z"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason_code").value("ATTRIBUTE_MISSING"));
    }

    @Test
    @DisplayName("属性属主不可达返回 503 + ATTRIBUTE_SOURCE_UNAVAILABLE")
    void attributesUnavailableIs503() throws Exception {
        attributes.markSourceUnavailable("outage");

        mvc.perform(get("/principals/outage/attributes")
                        .param("as_of", "2026-10-15T12:00:00Z"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.reason_code").value("ATTRIBUTE_SOURCE_UNAVAILABLE"));
    }

    @Test
    @DisplayName("属性端点要求服务权限：无调用方 403")
    void attributesRequireServicePermission() throws Exception {
        MockMvc denied = MockMvcBuilders.standaloneSetup(
                new IamAuthorizationController(service, callerProvider(null))).build();

        denied.perform(get("/principals/worker/attributes")
                        .param("as_of", "2026-10-15T12:00:00Z"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason_code").value("ATTRIBUTE_CALLER_VIOLATION"));
    }

    @Test
    @DisplayName("服务调用方 + 数据权限可读属性快照")
    void serviceCallerReadsAttributes() throws Exception {
        mvc.perform(get("/principals/worker/attributes")
                        .param("as_of", "2026-10-15T12:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attribute_state").value("fresh"))
                .andExpect(jsonPath("$.snapshot.primary_position.grade").value("P5"));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<CallerProvider> callerProvider(Caller caller) {
        ObjectProvider<CallerProvider> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(caller == null ? null : () -> java.util.Optional.of(caller));
        return provider;
    }
}
