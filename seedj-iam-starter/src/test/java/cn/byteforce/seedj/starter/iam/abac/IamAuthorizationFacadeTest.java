package cn.byteforce.seedj.starter.iam.abac;

import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.iam.IamAuthorizationDecision;
import cn.byteforce.seedj.iam.IamAuthorizationRequest;
import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.IamRole;
import cn.byteforce.seedj.iam.IamUser;
import cn.byteforce.seedj.iam.spi.IamPolicyDecisionPoint;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamAuthorizationRepository;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamGroupRepository;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamUserRepository;
import cn.byteforce.seedj.starter.iam.IamProperties;
import cn.byteforce.seedj.starter.iam.identity.IamAuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SpEL 门面：调用方主体解析（用户库 / authorities / JWT 声明）与拒绝语义。 */
class IamAuthorizationFacadeTest {

    private final InMemoryIamUserRepository users = new InMemoryIamUserRepository();
    private final InMemoryIamAuthorizationRepository roles = new InMemoryIamAuthorizationRepository();
    private final InMemoryIamGroupRepository groups = new InMemoryIamGroupRepository();
    private final RecordingPdp pdp = new RecordingPdp();

    private IamAuthorizationFacade facade;

    @BeforeEach
    void setUp() {
        roles.createRole(IamRole.create("r-ops", "default", "ops", "运维", "", null, false));
        roles.grantPermission("r-ops", "order.read");
        facade = new IamAuthorizationFacade(pdp, users,
                new IamAuthorizationService(roles, groups, null), IamProperties.defaults());
    }

    @Test
    @DisplayName("未认证（null authentication）直接 FORBIDDEN")
    void nullAuthenticationIsForbidden() {
        assertThatThrownBy(() -> facade.authorize(null, "order", "o-1", "read"))
                .isInstanceOfSatisfying(IamException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(IamErrorCode.FORBIDDEN);
                    assertThat(exception.getMessage()).isEqualTo("authentication required");
                });
    }

    @Test
    @DisplayName("已知用户：主体取库内 id/角色/权限，属性来自用户档案")
    void knownUserUsesRepositoryAuthorization() {
        IamUser user = users.create(IamUser.create("u-1", "default", "alice", "Alice", null, true)
                .withProfile("Alice", "alice@example.com", Map.of("department", "D01")), "hash");
        roles.assignRoleToUser(user.id(), "r-ops", Instant.now(), "test");
        pdp.decision = IamAuthorizationDecision.allow("allow-ops");

        boolean allowed = facade.authorize(authentication("alice", "n/a"), "order", "o-1", "read");

        assertThat(allowed).isTrue();
        IamAuthorizationRequest request = pdp.only();
        assertThat(request.subject().userId()).isEqualTo("u-1");
        assertThat(request.subject().username()).isEqualTo("alice");
        assertThat(request.subject().tenantId()).isEqualTo("default");
        assertThat(request.subject().roles()).containsExactly("ops");
        assertThat(request.subject().permissions()).containsExactly("order.read");
        assertThat(request.subject().amr()).containsExactly("pwd");
        assertThat(request.subject().acr()).isEqualTo(Caller.DEFAULT_ACR);
        assertThat(request.subject().attributes()).containsEntry("department", "D01");
        assertThat(request.action()).isEqualTo("read");
        assertThat(request.resourceType()).isEqualTo("order");
        assertThat(request.resourceId()).isEqualTo("o-1");
        assertThat(request.resourceAttributes()).isEmpty();
        assertThat(request.environment()).isEmpty();
    }

    @Test
    @DisplayName("登录名查不到时按主体 id 回落用户库")
    void fallsBackToFindById() {
        users.create(IamUser.create("u-2", "default", "bob", "Bob", null, true), "hash");
        pdp.decision = IamAuthorizationDecision.allow("allow-any");

        facade.authorize(authentication("u-2", "n/a"), "order", "o-2", "read");

        assertThat(pdp.only().subject().userId()).isEqualTo("u-2");
        assertThat(pdp.only().subject().username()).isEqualTo("bob");
    }

    @Test
    @DisplayName("未知用户按 GrantedAuthority 前缀构造主体")
    void unknownUserFallsBackToAuthorities() {
        pdp.decision = IamAuthorizationDecision.allow("allow-any");

        facade.authorize(new UsernamePasswordAuthenticationToken("svc", "n/a", List.of(
                new SimpleGrantedAuthority("ROLE_integration"),
                new SimpleGrantedAuthority("PERM_order.read"),
                new SimpleGrantedAuthority("GROUP_finance"),
                new SimpleGrantedAuthority("AMR_totp"),
                new SimpleGrantedAuthority("ACR_urn:seedj:acr:totp"))),
                "order", "o-3", "read");

        IamAuthorizationRequest request = pdp.only();
        assertThat(request.subject().userId()).isEqualTo("svc");
        assertThat(request.subject().username()).isEqualTo("svc");
        assertThat(request.subject().tenantId()).isEqualTo("default");
        assertThat(request.subject().roles()).containsExactly("integration");
        assertThat(request.subject().permissions()).containsExactly("order.read");
        assertThat(request.subject().groups()).containsExactly("finance");
        assertThat(request.subject().amr()).containsExactly("totp");
        assertThat(request.subject().acr()).isEqualTo("urn:seedj:acr:totp");
        assertThat(request.subject().attributes()).isEmpty();
    }

    @Test
    @DisplayName("未知用户 + JWT：user_id 覆盖主体，声明并入角色/权限/组/属性")
    void jwtClaimsExtendSubject() {
        pdp.decision = IamAuthorizationDecision.allow("allow-any");
        Jwt jwt = Jwt.withTokenValue("jwt-value")
                .header("alg", "none")
                .subject("bob")
                .claim("user_id", "u-9")
                .claim("roles", List.of("auditor"))
                .claim("permissions", List.of("audit.read"))
                .claim("groups", List.of("compliance"))
                .claim("amr", List.of("pwd", "totp"))
                .claim("acr", "urn:seedj:acr:totp")
                .claim("tenant", "acme")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        facade.authorize(new JwtAuthenticationToken(jwt, List.of(
                new SimpleGrantedAuthority("ROLE_reader"))), "order", "o-4", "read");

        IamAuthorizationRequest request = pdp.only();
        assertThat(request.subject().userId()).isEqualTo("u-9");
        assertThat(request.subject().username()).isEqualTo("bob");
        assertThat(request.subject().roles()).containsExactlyInAnyOrder("reader", "auditor");
        assertThat(request.subject().permissions()).containsExactly("audit.read");
        assertThat(request.subject().groups()).containsExactly("compliance");
        assertThat(request.subject().amr()).containsExactlyInAnyOrder("pwd", "totp");
        assertThat(request.subject().acr()).isEqualTo("urn:seedj:acr:totp");
        assertThat(request.subject().attributes())
                .containsEntry("tenant", "acme")
                .containsEntry("user_id", "u-9");
    }

    @Test
    @DisplayName("已知用户 + JWT：令牌声明合并进用户属性")
    void knownUserMergesJwtClaims() {
        users.create(IamUser.create("u-3", "default", "carol", "Carol", null, true)
                .withProfile("Carol", null, Map.of("department", "D02")), "hash");
        pdp.decision = IamAuthorizationDecision.allow("allow-any");
        Jwt jwt = Jwt.withTokenValue("jwt-value")
                .header("alg", "none")
                .subject("carol")
                .claim("scope", "openid")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        facade.authorize(new JwtAuthenticationToken(jwt), "order", "o-5", "read");

        assertThat(pdp.only().subject().userId()).isEqualTo("u-3");
        assertThat(pdp.only().subject().attributes())
                .containsEntry("department", "D02")
                .containsEntry("scope", "openid");
    }

    @Test
    @DisplayName("require 拒绝时抛 FORBIDDEN，允许时静默")
    void requireThrowsOnlyWhenDenied() {
        pdp.decision = IamAuthorizationDecision.deny("no matching allow policy");

        assertThatThrownBy(() -> facade.require(authentication("svc", "n/a"),
                "invoice", "inv-1", "approve"))
                .isInstanceOfSatisfying(IamException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(IamErrorCode.FORBIDDEN);
                    assertThat(exception.getMessage())
                            .isEqualTo("not authorized for approve on invoice/inv-1");
                });

        pdp.decision = IamAuthorizationDecision.allow("allow-invoice");
        facade.require(authentication("svc", "n/a"), "invoice", "inv-1", "approve");
        assertThat(pdp.requests).hasSize(2);
    }

    private static Authentication authentication(String name, String credentials) {
        return new UsernamePasswordAuthenticationToken(name, credentials, List.of());
    }

    private static final class RecordingPdp implements IamPolicyDecisionPoint {

        private final List<IamAuthorizationRequest> requests = new ArrayList<>();

        private IamAuthorizationDecision decision =
                IamAuthorizationDecision.deny("no matching allow policy");

        @Override
        public IamAuthorizationDecision decide(IamAuthorizationRequest request) {
            requests.add(request);
            return decision;
        }

        private IamAuthorizationRequest only() {
            assertThat(requests).hasSize(1);
            return requests.get(0);
        }
    }

}
