package cn.byteforce.seedj.starter.iam.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.CallerProvider;
import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.IamRole;
import cn.byteforce.seedj.iam.authz.AttributeLookup;
import cn.byteforce.seedj.iam.authz.AttributeSnapshot;
import cn.byteforce.seedj.iam.authz.AttributeState;
import cn.byteforce.seedj.iam.authz.IamBinding;
import cn.byteforce.seedj.iam.authz.IamCondition;
import cn.byteforce.seedj.iam.authz.IamConditionOperator;
import cn.byteforce.seedj.iam.authz.IamConditionTerm;
import cn.byteforce.seedj.iam.authz.IamPermissionCondition;
import cn.byteforce.seedj.iam.authz.SodRuleSet;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamAttributeSource;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamAuthorizationRepository;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamBindingStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 授权 HTTP 面：snake_case 字段、原因码与属性端点状态映射。 */
class IamAuthorizationControllerTest {

    private static final Instant JAN = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant OCT15 = Instant.parse("2026-10-15T12:00:00Z");

    private final InMemoryIamBindingStore bindings = new InMemoryIamBindingStore();
    private final InMemoryIamAuthorizationRepository roles =
            new InMemoryIamAuthorizationRepository();
    private final InMemoryIamAttributeSource attributes = new InMemoryIamAttributeSource();

    private IamContractAuthorizationService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        bindings.clear();
        attributes.clear();
        roles.createRole(
                IamRole.create("r-approver", "default", "approver", "审批人", "", null, false));
        roles.createRole(
                IamRole.create("r-requester", "default", "requester", "申请人", "", null, false));
        roles.grantPermission("r-approver", "purchase.request.approve");
        roles.grantPermission("r-requester", "purchase.request.create");
        bindings.save(
                new IamBinding(
                        "b-away",
                        "away",
                        "r-approver",
                        Map.of("department", "D001"),
                        JAN,
                        null,
                        JAN,
                        null));
        bindings.save(
                new IamBinding(
                        "b-requester", "requester", "r-requester", Map.of(), JAN, null, JAN, null));
        attributes.save(
                new AttributeSnapshot(
                        "worker",
                        OCT15,
                        OCT15,
                        Instant.parse("2026-12-01T00:00:00Z"),
                        "hr",
                        "1.0.0",
                        Map.of("primary_position", Map.of("grade", "P5"))));

        service =
                new IamContractAuthorizationService(
                        bindings, roles, attributes, List.of(), SodRuleSet.defaults());
        Caller serviceCaller =
                new Caller(
                        "svc",
                        "svc",
                        "default",
                        Set.of(),
                        Set.of("hr.attributes.read"),
                        Set.of(),
                        Set.of("pwd"),
                        Caller.DEFAULT_ACR,
                        Map.of());
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new IamAuthorizationController(
                                        service, callerProvider(serviceCaller)))
                        .build();
    }

    @Test
    @DisplayName("/resolve 返回 roles / permissions / sod_warnings")
    void resolveReturnsSnakeCaseFields() throws Exception {
        mvc.perform(
                        post("/resolve")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
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
        mvc.perform(
                        post("/check")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
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
        mvc.perform(
                        post("/check")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
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
        mvc.perform(get("/principals/missing/attributes").param("as_of", "2026-10-15T12:00:00Z"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason_code").value("ATTRIBUTE_MISSING"));
    }

    @Test
    @DisplayName("属性属主不可达返回 503 + ATTRIBUTE_SOURCE_UNAVAILABLE")
    void attributesUnavailableIs503() throws Exception {
        attributes.markSourceUnavailable("outage");

        mvc.perform(get("/principals/outage/attributes").param("as_of", "2026-10-15T12:00:00Z"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.reason_code").value("ATTRIBUTE_SOURCE_UNAVAILABLE"));
    }

    @Test
    @DisplayName("属性端点要求服务权限：无调用方 403")
    void attributesRequireServicePermission() throws Exception {
        MockMvc denied =
                MockMvcBuilders.standaloneSetup(
                                new IamAuthorizationController(service, callerProvider(null)))
                        .build();

        denied.perform(get("/principals/worker/attributes").param("as_of", "2026-10-15T12:00:00Z"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason_code").value("ATTRIBUTE_CALLER_VIOLATION"));
    }

    @Test
    @DisplayName("服务调用方 + 数据权限可读属性快照")
    void serviceCallerReadsAttributes() throws Exception {
        mvc.perform(get("/principals/worker/attributes").param("as_of", "2026-10-15T12:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attribute_state").value("fresh"))
                .andExpect(jsonPath("$.snapshot.primary_position.grade").value("P5"));
    }

    @Test
    @DisplayName("/resolve 缺 principal 或 as_of 报 SCIM_BAD_REQUEST")
    void resolveRequiresPrincipalAndAsOf() {
        IamAuthorizationController controller =
                new IamAuthorizationController(service, callerProvider(serviceCaller()));

        assertThatThrownBy(
                        () ->
                                controller.resolve(
                                        new IamAuthorizationDtos.ResolveRequest(
                                                " ", OCT15, null, null)))
                .isInstanceOfSatisfying(
                        IamException.class,
                        exception ->
                                assertThat(exception.getCode())
                                        .isEqualTo(IamErrorCode.SCIM_BAD_REQUEST));
        assertThatThrownBy(
                        () ->
                                controller.resolve(
                                        new IamAuthorizationDtos.ResolveRequest(
                                                "away", null, null, null)))
                .isInstanceOfSatisfying(
                        IamException.class,
                        exception ->
                                assertThat(exception.getCode())
                                        .isEqualTo(IamErrorCode.SCIM_BAD_REQUEST));
    }

    @Test
    @DisplayName("/resolve 未给 scope 时按空作用域解析")
    void resolveWithoutScopeUsesEmptyScope() {
        IamAuthorizationController controller =
                new IamAuthorizationController(service, callerProvider(serviceCaller()));

        IamAuthorizationDtos.ResolveResponse response =
                controller.resolve(
                        new IamAuthorizationDtos.ResolveRequest("away", OCT15, null, null));

        assertThat(response.roles()).containsExactly("approver");
        assertThat(response.sodWarnings()).isEmpty();
    }

    @Test
    @DisplayName("/check 缺 principal/permission/as_of 报 SCIM_BAD_REQUEST")
    void checkRequiresPrincipalPermissionAndAsOf() {
        IamAuthorizationController controller =
                new IamAuthorizationController(service, callerProvider(serviceCaller()));

        assertThatThrownBy(
                        () ->
                                controller.check(
                                        new IamAuthorizationDtos.CheckRequest(
                                                "away", " ", OCT15, null)))
                .isInstanceOfSatisfying(
                        IamException.class,
                        exception ->
                                assertThat(exception.getCode())
                                        .isEqualTo(IamErrorCode.SCIM_BAD_REQUEST));
        assertThatThrownBy(
                        () ->
                                controller.check(
                                        new IamAuthorizationDtos.CheckRequest(
                                                "away", "purchase.request.approve", null, null)))
                .isInstanceOfSatisfying(
                        IamException.class,
                        exception ->
                                assertThat(exception.getCode())
                                        .isEqualTo(IamErrorCode.SCIM_BAD_REQUEST));
    }

    @Test
    @DisplayName("/check 使用客户端提供的快照与状态")
    void checkUsesProvidedSnapshotAndState() {
        IamAuthorizationController controller =
                new IamAuthorizationController(
                        new IamContractAuthorizationService(
                                bindings,
                                roles,
                                attributes,
                                List.of(
                                        new IamPermissionCondition(
                                                "purchase.request.approve",
                                                new IamCondition(
                                                        "all",
                                                        List.of(
                                                                new IamConditionTerm(
                                                                        "primary_position.grade",
                                                                        IamConditionOperator.IN,
                                                                        List.of("P5")))))),
                                SodRuleSet.defaults()),
                        callerProvider(serviceCaller()));

        IamAuthorizationDtos.CheckResponse matched =
                controller.check(
                        new IamAuthorizationDtos.CheckRequest(
                                "away",
                                "purchase.request.approve",
                                OCT15,
                                new IamAuthorizationDtos.AttributeContext(
                                        OCT15,
                                        "caller",
                                        "fresh",
                                        Map.of("primary_position", Map.of("grade", "P5")))));
        IamAuthorizationDtos.CheckResponse stale =
                controller.check(
                        new IamAuthorizationDtos.CheckRequest(
                                "away",
                                "purchase.request.approve",
                                OCT15,
                                new IamAuthorizationDtos.AttributeContext(
                                        OCT15, "caller", "stale", Map.of())));

        assertThat(matched.allowed()).isTrue();
        assertThat(matched.attributeState()).isEqualTo("fresh");
        assertThat(stale.allowed()).isFalse();
        assertThat(stale.reasonCode()).isEqualTo("ATTRIBUTE_STALE");
        assertThat(stale.attributeState()).isEqualTo("stale");
    }

    @Test
    @DisplayName("/check 未知 attribute_state 按 MISSING 失败关闭")
    void checkUnknownAttributeStateIsMissing() {
        IamAuthorizationController controller =
                new IamAuthorizationController(
                        new IamContractAuthorizationService(
                                bindings,
                                roles,
                                attributes,
                                List.of(
                                        new IamPermissionCondition(
                                                "purchase.request.approve",
                                                new IamCondition(
                                                        "all",
                                                        List.of(
                                                                new IamConditionTerm(
                                                                        "primary_position.grade",
                                                                        IamConditionOperator.IN,
                                                                        List.of("P5")))))),
                                SodRuleSet.defaults()),
                        callerProvider(serviceCaller()));

        IamAuthorizationDtos.CheckResponse response =
                controller.check(
                        new IamAuthorizationDtos.CheckRequest(
                                "away",
                                "purchase.request.approve",
                                OCT15,
                                new IamAuthorizationDtos.AttributeContext(
                                        OCT15, "caller", "not-a-state", Map.of())));

        assertThat(response.allowed()).isFalse();
        assertThat(response.reasonCode()).isEqualTo("ATTRIBUTE_MISSING");
        assertThat(response.attributeState()).isEqualTo("missing");
    }

    @Test
    @DisplayName("属性状态 fresh 但快照为空时返回 409")
    void attributesWithoutSnapshotIs409() {
        IamContractAuthorizationService blank =
                new IamContractAuthorizationService(
                        bindings,
                        roles,
                        (principal, asOf, recordedAsOf) ->
                                new AttributeLookup(AttributeState.FRESH, null),
                        List.of(),
                        SodRuleSet.defaults());
        MockMvc blankMvc =
                MockMvcBuilders.standaloneSetup(
                                new IamAuthorizationController(
                                        blank, callerProvider(serviceCaller())))
                        .build();

        try {
            blankMvc.perform(
                            get("/principals/worker/attributes")
                                    .param("as_of", "2026-10-15T12:00:00Z"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.reason_code").value("ATTRIBUTE_MISSING"));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    @Test
    @DisplayName("iam.attributes.read 同样可读属性，缺省 attribute_state 视为 fresh")
    void iamAttributesPermissionAndDefaultState() {
        Caller iamService =
                new Caller(
                        "svc",
                        "svc",
                        "default",
                        Set.of(),
                        Set.of("iam.attributes.read"),
                        Set.of(),
                        Set.of("pwd"),
                        Caller.DEFAULT_ACR,
                        Map.of());
        IamContractAuthorizationService service =
                new IamContractAuthorizationService(
                        bindings,
                        roles,
                        attributes,
                        List.of(
                                new IamPermissionCondition(
                                        "purchase.request.approve",
                                        new IamCondition(
                                                "all",
                                                List.of(
                                                        new IamConditionTerm(
                                                                "primary_position.grade",
                                                                IamConditionOperator.IN,
                                                                List.of("P5")))))),
                        SodRuleSet.defaults());
        IamAuthorizationController controller =
                new IamAuthorizationController(service, callerProvider(iamService));

        org.springframework.http.ResponseEntity<?> response =
                controller.attributes("worker", OCT15, null);
        IamAuthorizationDtos.CheckResponse check =
                controller.check(
                        new IamAuthorizationDtos.CheckRequest(
                                "away",
                                "purchase.request.approve",
                                OCT15,
                                new IamAuthorizationDtos.AttributeContext(
                                        OCT15,
                                        "caller",
                                        null,
                                        Map.of("primary_position", Map.of("grade", "P5")))));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(check.allowed()).isTrue();
        assertThat(check.attributeState()).isEqualTo("fresh");
    }

    private static Caller serviceCaller() {
        return new Caller(
                "svc",
                "svc",
                "default",
                Set.of(),
                Set.of("hr.attributes.read"),
                Set.of(),
                Set.of("pwd"),
                Caller.DEFAULT_ACR,
                Map.of());
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<CallerProvider> callerProvider(Caller caller) {
        ObjectProvider<CallerProvider> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable())
                .thenReturn(caller == null ? null : () -> java.util.Optional.of(caller));
        return provider;
    }
}
