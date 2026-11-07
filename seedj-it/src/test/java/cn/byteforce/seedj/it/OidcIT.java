package cn.byteforce.seedj.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.it.extension.ClusterClientSslContext;
import cn.byteforce.seedj.it.extension.ClusterExtension;
import cn.byteforce.seedj.it.extension.ClusterPki;
import cn.byteforce.seedj.it.extension.ClusterService;
import cn.byteforce.seedj.it.extension.ClusterUntrustedClient;
import cn.byteforce.seedj.it.fixture.iam.IamTestUsers;
import cn.byteforce.seedj.it.support.IamTestSession;
import cn.byteforce.seedj.it.support.IamTestSession.AuthorizationResult;
import cn.byteforce.seedj.it.support.IamTestSession.HttpResult;
import cn.byteforce.seedj.it.support.IamTestSession.TokenResponse;
import cn.byteforce.seedj.pki.PkiProvider;
import cn.byteforce.seedj.pki.PkiProvider.CertificateRequest;
import cn.byteforce.seedj.pki.PkiProvider.IssuedCertificate;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * IAM 的 RFC 一致性集成测试。
 *
 * <p>覆盖范围：
 *
 * <ul>
 *   <li>RFC 8414 / OIDC Discovery 1.0：元数据、issuer 一致性、JWKS；
 *   <li>RFC 6749 §3.1/§4.1.2.1：授权端点错误、state、redirect_uri 精确匹配；
 *   <li>RFC 6749 §5.1/§5.2：token 端点成功/错误语义、缓存头、WWW-Authenticate；
 *   <li>RFC 7636：PKCE S256；
 *   <li>OIDC Core：授权码流程、ID Token、UserInfo、prompt/max_age/nonce；
 *   <li>RFC 6750：Bearer 语义；
 *   <li>RFC 7662：introspection；RFC 7009：revocation；OIDC RP-Initiated Logout；
 *   <li>资源服务器侧：JWT 校验、scope → authority、ID Token 不可作为 Access Token。
 * </ul>
 */
@ExtendWith(ClusterExtension.class)
public class OidcIT {

    private static final String LOGOUT_REDIRECT_URI = "http://127.0.0.1:8080/logout/callback";

    private static String issuer;
    private static SSLContext testClientSslContext;

    @BeforeAll
    static void discoverIssuer(
            @ClusterService("iam") RestTestClient iam,
            @ClusterClientSslContext SSLContext sslContext) {
        issuer = IamTestSession.fetchIssuer(iam);
        testClientSslContext = sslContext;
    }

    // ==================================================================
    // Discovery / JWKS
    // ==================================================================

    @Test
    @DisplayName("RFC 8414 / OIDC Discovery：必需元数据、HTTPS URI、issuer 与 base URL 一致")
    void openidConfigurationMustExposeRequiredMetadata() {
        IamTestSession session = session();
        HttpResult result =
                session.get("/.well-known/openid-configuration", MediaType.APPLICATION_JSON);

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.contentType()).isNotNull();
        assertThat(MediaType.APPLICATION_JSON.isCompatibleWith(result.contentType())).isTrue();

        Map<String, Object> metadata = json(result.body());
        assertThat(metadata.get("issuer")).isEqualTo(issuer);
        URI issuerUri = assertHttpsUri(metadata.get("issuer"), "issuer");
        assertThat(issuerUri.getQuery()).as("RFC 8414 issuer MUST NOT contain a query").isNull();
        assertThat(issuerUri.getFragment())
                .as("RFC 8414 issuer MUST NOT contain a fragment")
                .isNull();

        assertHttpsUri(metadata.get("authorization_endpoint"), "authorization_endpoint");
        assertHttpsUri(metadata.get("token_endpoint"), "token_endpoint");
        assertHttpsUri(metadata.get("userinfo_endpoint"), "userinfo_endpoint");
        assertHttpsUri(metadata.get("jwks_uri"), "jwks_uri");
        assertHttpsUri(metadata.get("revocation_endpoint"), "revocation_endpoint");
        assertHttpsUri(metadata.get("introspection_endpoint"), "introspection_endpoint");
        assertHttpsUri(metadata.get("end_session_endpoint"), "end_session_endpoint");

        assertThat(strings(metadata.get("response_types_supported"))).contains("code");
        assertThat(strings(metadata.get("grant_types_supported")))
                .contains("authorization_code", "refresh_token");
        assertThat(strings(metadata.get("subject_types_supported"))).contains("public");
        assertThat(strings(metadata.get("id_token_signing_alg_values_supported")))
                .contains("RS256");
        assertThat(strings(metadata.get("code_challenge_methods_supported"))).contains("S256");
        assertThat(strings(metadata.get("token_endpoint_auth_methods_supported")))
                .contains("client_secret_basic");
        assertThat(strings(metadata.get("scopes_supported"))).contains("openid", "profile", "read");
        assertThat(strings(metadata.get("response_modes_supported"))).contains("query");
        assertThat(strings(metadata.get("claims_supported"))).contains("sub", "iss", "auth_time");

        IamTestSession issuerSession = new IamTestSession(issuer, testClientSslContext);
        HttpResult viaIssuer =
                issuerSession.get(
                        URI.create(issuer + "/.well-known/openid-configuration"),
                        MediaType.APPLICATION_JSON);
        assertThat(json(viaIssuer.body()).get("issuer")).isEqualTo(issuer);
    }

    @Test
    @DisplayName("RFC 8414 §5：oauth-authorization-server 与 openid-configuration 的公共字段一致")
    void authorizationServerMetadataMustBeConsistentWithOidcDiscovery() {
        IamTestSession session = session();
        Map<String, Object> oidc =
                json(
                        session.get("/.well-known/openid-configuration", MediaType.APPLICATION_JSON)
                                .body());
        Map<String, Object> as =
                json(
                        session.get(
                                        "/.well-known/oauth-authorization-server",
                                        MediaType.APPLICATION_JSON)
                                .body());

        for (String field :
                List.of(
                        "issuer",
                        "authorization_endpoint",
                        "token_endpoint",
                        "jwks_uri",
                        "revocation_endpoint",
                        "introspection_endpoint",
                        "response_types_supported",
                        "grant_types_supported",
                        "token_endpoint_auth_methods_supported",
                        "revocation_endpoint_auth_methods_supported",
                        "introspection_endpoint_auth_methods_supported",
                        "code_challenge_methods_supported",
                        "scopes_supported",
                        "response_modes_supported")) {
            assertThat(as.get(field)).as(field).isEqualTo(oidc.get(field));
        }
    }

    @Test
    @DisplayName("RFC 8414 §2：声明 JWT client auth 时必须发布签名算法")
    void metadataMustAdvertiseJwtClientAuthenticationSigningAlgorithms() {
        IamTestSession session = session();
        Map<String, Object> oidc =
                json(
                        session.get("/.well-known/openid-configuration", MediaType.APPLICATION_JSON)
                                .body());
        Map<String, Object> as =
                json(
                        session.get(
                                        "/.well-known/oauth-authorization-server",
                                        MediaType.APPLICATION_JSON)
                                .body());

        for (Map<String, Object> metadata : List.of(oidc, as)) {
            assertThat(strings(metadata.get("token_endpoint_auth_methods_supported")))
                    .contains("client_secret_jwt", "private_key_jwt");
            assertThat(strings(metadata.get("token_endpoint_auth_signing_alg_values_supported")))
                    .contains("RS256");
            assertThat(
                            strings(
                                    metadata.get(
                                            "revocation_endpoint_auth_signing_alg_values_supported")))
                    .contains("RS256");
            assertThat(
                            strings(
                                    metadata.get(
                                            "introspection_endpoint_auth_signing_alg_values_supported")))
                    .contains("RS256");
        }
    }

    @Test
    @DisplayName("RFC 7517：JWKS 只暴露 RSA 公钥成员")
    void jwksMustExposePublicRsaKey() {
        IamTestSession session = session();
        HttpResult result = session.get("/oauth2/jwks", MediaType.APPLICATION_JSON);
        assertThat(result.status()).isEqualTo(200);

        List<Map<String, Object>> keys = JsonPath.read(result.body(), "$.keys");
        assertThat(keys).isNotEmpty();
        List<Map<String, Object>> rsaKeys =
                keys.stream().filter(key -> "RSA".equals(key.get("kty"))).toList();
        assertThat(rsaKeys)
                .isNotEmpty()
                .allSatisfy(
                        key -> {
                            assertThat(key).containsKeys("kid", "n", "e");
                            assertThat(key)
                                    .doesNotContainKeys("d", "p", "q", "dp", "dq", "qi", "oth");
                        });
    }

    @Test
    @DisplayName("JWKS 的 kid 必须等于 IAM 签名身份的 serialNumber")
    void jwksKidMustMatchSigningIdentitySerial(@ClusterPki PkiProvider pki) {
        IamTestSession session = session();
        HttpResult result = session.get("/oauth2/jwks", MediaType.APPLICATION_JSON);
        assertThat(result.status()).isEqualTo(200);

        List<Map<String, Object>> keys = JsonPath.read(result.body(), "$.keys");
        assertThat(keys).hasSize(1);
        assertThat(keys.get(0).get("kid"))
                .isEqualTo(pki.signingIdentity("iam-jwt-signer").serialNumber());
    }

    @Test
    @DisplayName("OIDC Core：ID Token 的 kid 必须能在 JWKS 中找到并验签")
    void idTokenMustBeVerifiableWithPublishedJwks() throws Exception {
        IamTestSession session = session();
        TokenResponse token = openidToken(session, "nonce-jwks");

        SignedJWT jwt = SignedJWT.parse(token.idToken());
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(jwt.getHeader().getKeyID()).isNotBlank();

        JWKSet jwkSet = JWKSet.parse(jwks(session));
        RSAKey key = (RSAKey) jwkSet.getKeyByKeyId(jwt.getHeader().getKeyID());
        assertThat(key).as("JWKS must contain the ID token kid").isNotNull();
        assertThat(jwt.verify(new RSASSAVerifier(key.toRSAPublicKey()))).isTrue();
    }

    // ==================================================================
    // Authorization endpoint
    // ==================================================================

    @Test
    @DisplayName("RFC 6749 §3.1：HTML 用户的匿名授权请求进入登录流程")
    void anonymousAuthorizationRequestMustRedirectToLogin() {
        IamTestSession session = session();
        HttpResult result =
                session.authorizationRequest(
                        session.baseAuthorizationParams("openid", "state-anon", "nonce-anon"),
                        MediaType.TEXT_HTML);

        assertThat(result.status()).isEqualTo(302);
        assertThat(session.isLoginRedirect(result.location())).isTrue();
    }

    @Test
    @DisplayName("RFC 6749 §4.1.2.1：缺少 client_id 时 400 且绝不重定向")
    void authorizationRequestWithoutClientIdMustNotRedirect() {
        IamTestSession session = session();
        IamTestSession.HttpResult result =
                session.authorizationRequest(
                        withoutParam(
                                session.baseAuthorizationParams(
                                        "openid", "state-nocid", "nonce-nocid"),
                                "client_id"),
                        MediaType.TEXT_HTML);

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.location()).isNull();
    }

    @Test
    @DisplayName("RFC 6749 §4.1.2.1：未知 client_id 时 400 且绝不重定向")
    void authorizationRequestWithUnknownClientIdMustNotRedirect() {
        IamTestSession session = session();
        HttpResult result =
                session.authorizationRequest(
                        withParam(
                                session.baseAuthorizationParams(
                                        "openid", "state-badcid", "nonce-badcid"),
                                "client_id",
                                "unknown-client"),
                        MediaType.TEXT_HTML);

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.location()).isNull();
    }

    @Test
    @DisplayName("RFC 6749 §4.1.2.1：未注册 redirect_uri 时 400 且绝不重定向")
    void authorizationRequestWithUnregisteredRedirectUriMustNotRedirect() {
        IamTestSession session = session();
        HttpResult result =
                session.authorizationRequest(
                        withParam(
                                session.baseAuthorizationParams(
                                        "openid", "state-badru", "nonce-badru"),
                                "redirect_uri",
                                "https://evil.example/cb"),
                        MediaType.TEXT_HTML);

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.location()).isNull();
    }

    @Test
    @DisplayName("RFC 6749 §3.1.2：redirect_uri 含 fragment 时 400 且绝不重定向")
    void authorizationRequestWithRedirectUriFragmentMustNotRedirect() {
        IamTestSession session = session();
        HttpResult result =
                session.authorizationRequest(
                        withParam(
                                session.baseAuthorizationParams(
                                        "openid", "state-frag", "nonce-frag"),
                                "redirect_uri",
                                IamTestSession.REDIRECT_URI + "#fragment"),
                        MediaType.TEXT_HTML);

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.location()).isNull();
    }

    @Test
    @DisplayName("RFC 6749 §3.1.2.3：redirect_uri 必须精确匹配（尾斜杠也不允许）")
    void authorizationRequestWithNonExactRedirectUriMustNotRedirect() {
        IamTestSession session = session();
        HttpResult result =
                session.authorizationRequest(
                        withParam(
                                session.baseAuthorizationParams(
                                        "openid", "state-slash", "nonce-slash"),
                                "redirect_uri",
                                IamTestSession.REDIRECT_URI + "/"),
                        MediaType.TEXT_HTML);

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.location()).isNull();
    }

    @Test
    @DisplayName("RFC 6749 §4.1.2.1：非法 scope 以 invalid_scope 重定向并回传 state")
    void authorizationRequestWithInvalidScopeMustRedirectInvalidScope() {
        IamTestSession session = session();
        HttpResult result =
                session.authorizationRequest(
                        withParam(
                                session.baseAuthorizationParams(
                                        "admin", "state-scope", "nonce-scope"),
                                "scope",
                                "admin"),
                        MediaType.TEXT_HTML);

        assertOAuthErrorRedirect(result, "invalid_scope", "state-scope");
    }

    @Test
    @DisplayName("RFC 6749 §3.3：scope 大小写敏感")
    void authorizationRequestWithWrongCaseScopeMustRedirectInvalidScope() {
        IamTestSession session = session();
        HttpResult result =
                session.authorizationRequest(
                        withParam(
                                session.baseAuthorizationParams(
                                        "OpenID", "state-case", "nonce-case"),
                                "scope",
                                "OpenID"),
                        MediaType.TEXT_HTML);

        assertOAuthErrorRedirect(result, "invalid_scope", "state-case");
    }

    @Test
    @DisplayName("RFC 6749 §3.1：重复 state 参数返回 invalid_request")
    void authorizationRequestWithDuplicateStateMustRedirectInvalidRequest() {
        IamTestSession session = session();
        List<String[]> params =
                session.baseAuthorizationParams("openid", "state-dupstate", "nonce-dupstate");
        params.add(new String[] {"state", "another-state"});

        HttpResult result = session.authorizationRequest(params, MediaType.TEXT_HTML);

        assertOAuthErrorRedirect(result, "invalid_request", "state-dupstate");
    }

    @Test
    @DisplayName("RFC 6749 §3.3：重复 scope 值以 invalid_scope 拒绝")
    void authorizationRequestWithDuplicateScopeValueMustRedirectInvalidScope() {
        IamTestSession session = session();
        HttpResult result =
                session.authorizationRequest(
                        withParam(
                                session.baseAuthorizationParams(
                                        "openid openid", "state-dup", "nonce-dup"),
                                "scope",
                                "openid openid"),
                        MediaType.TEXT_HTML);

        assertOAuthErrorRedirect(result, "invalid_scope", "state-dup");
    }

    @Test
    @DisplayName("RFC 6749 §3.1：重复 scope 参数以 invalid_request 拒绝")
    void authorizationRequestWithDuplicateScopeParameterMustRedirectInvalidRequest() {
        IamTestSession session = session();
        List<String[]> params =
                session.baseAuthorizationParams("openid", "state-dup2", "nonce-dup2");
        params.add(new String[] {"scope", "read"});

        HttpResult result = session.authorizationRequest(params, MediaType.TEXT_HTML);

        assertOAuthErrorRedirect(result, "invalid_request", "state-dup2");
    }

    @Test
    @DisplayName("RFC 6749 §3.1.2.4 / §4.1.2.1：不支持的 response_type 重定向 unsupported_response_type")
    void authorizationRequestWithUnsupportedResponseTypeMustRedirectError() {
        IamTestSession session = session();

        for (String responseType : List.of("token", "id_token", "code id_token", "none")) {
            HttpResult result =
                    session.authorizationRequest(
                            withParam(
                                    session.baseAuthorizationParams(
                                            "openid", "state-rt", "nonce-rt"),
                                    "response_type",
                                    responseType),
                            MediaType.TEXT_HTML);
            assertOAuthErrorRedirect(result, "unsupported_response_type", "state-rt");
        }
    }

    @Test
    @DisplayName("RFC 7636 §4.4.1：client 要求 PKCE 时缺少 code_challenge 必须拒绝")
    void authorizationRequestWithoutPkceMustRedirectInvalidRequest() {
        IamTestSession session = session();
        List<String[]> params =
                withoutParam(
                        session.baseAuthorizationParams("openid", "state-nopkce", "nonce-nopkce"),
                        "code_challenge");
        params = withoutParam(params, "code_challenge_method");

        HttpResult result = session.authorizationRequest(params, MediaType.TEXT_HTML);

        assertOAuthErrorRedirect(result, "invalid_request", "state-nopkce");
    }

    @Test
    @DisplayName("RFC 7636 §4.3：code_challenge_method 只支持 S256")
    void authorizationRequestWithPlainPkceMustRedirectInvalidRequest() {
        IamTestSession session = session();
        HttpResult result =
                session.authorizationRequest(
                        withParam(
                                session.baseAuthorizationParams(
                                        "openid", "state-plain", "nonce-plain"),
                                "code_challenge_method",
                                "plain"),
                        MediaType.TEXT_HTML);

        assertOAuthErrorRedirect(result, "invalid_request", "state-plain");
    }

    @Test
    @DisplayName("RFC 7636 §4.2：S256 code_challenge 必须为 43 字符")
    void authorizationRequestWithShortCodeChallengeMustRedirectInvalidRequest() {
        IamTestSession session = session();
        HttpResult result =
                session.authorizationRequest(
                        withParam(
                                session.baseAuthorizationParams(
                                        "openid", "state-shortcc", "nonce-shortcc"),
                                "code_challenge",
                                "abc"),
                        MediaType.TEXT_HTML);

        assertOAuthErrorRedirect(result, "invalid_request", "state-shortcc");
    }

    @Test
    @DisplayName("OIDC Core §3.1.2.7：未声明的 response_mode 必须拒绝（仅支持 query）")
    void authorizationRequestWithUnsupportedResponseModeMustRedirectInvalidRequest() {
        IamTestSession session = session();
        for (String mode : List.of("form_post", "fragment", "bogus")) {
            HttpResult result =
                    session.authorizationRequest(
                            withParam(
                                    session.baseAuthorizationParams(
                                            "openid", "state-mode", "nonce-mode"),
                                    "response_mode",
                                    mode),
                            MediaType.TEXT_HTML);
            assertOAuthErrorRedirect(result, "invalid_request", "state-mode");
        }
    }

    @Test
    @DisplayName("OIDC Core §3.1.2.7：response_mode=query 正常返回授权码")
    void authorizationRequestWithQueryResponseModeMustSucceed() {
        IamTestSession session = session();
        AuthorizationResult result =
                session.authorize(
                        "openid",
                        "state-query",
                        "nonce-query",
                        Map.of("response_mode", "query"),
                        IamTestUsers.USERNAME,
                        IamTestUsers.PASSWORD);

        assertThat(result.code()).isNotBlank();
        assertThat(result.state()).isEqualTo("state-query");
    }

    @Test
    @DisplayName("OIDC Core §3.1.2.1：max_age 必须是非负整数")
    void authorizationRequestWithInvalidMaxAgeMustRedirectInvalidRequest() {
        IamTestSession session = session();
        for (String maxAge : List.of("abc", "-1", "1.5")) {
            HttpResult result =
                    session.authorizationRequest(
                            withParam(
                                    session.baseAuthorizationParams(
                                            "openid", "state-age", "nonce-age"),
                                    "max_age",
                                    maxAge),
                            MediaType.TEXT_HTML);
            assertOAuthErrorRedirect(result, "invalid_request", "state-age");
        }
    }

    @Test
    @DisplayName("OIDC Core §3.1.2.6：prompt=none 且未登录返回 login_required")
    void authorizationRequestWithPromptNoneAndNoSessionMustReturnLoginRequired() {
        IamTestSession session = session();
        List<String[]> params =
                session.baseAuthorizationParams("openid", "state-pnone", "nonce-pnone");
        params.add(new String[] {"prompt", "none"});

        HttpResult result = session.authorizationRequest(params, MediaType.TEXT_HTML);

        assertOAuthErrorRedirect(result, "login_required", "state-pnone");
        assertThat(session.isLoginRedirect(result.location())).isFalse();
    }

    @Test
    @DisplayName("OIDC Core §3.1.2.6：prompt=none 且已登录返回授权码")
    void authorizationRequestWithPromptNoneAndSessionMustReturnCode() {
        IamTestSession session = session();
        session.authorize(
                "openid",
                "state-pnone-login",
                "nonce-pnone-login",
                null,
                IamTestUsers.USERNAME,
                IamTestUsers.PASSWORD);

        List<String[]> params =
                session.baseAuthorizationParams("openid", "state-pnone2", "nonce-pnone2");
        params.add(new String[] {"prompt", "none"});
        HttpResult result = session.authorizationRequest(params, MediaType.TEXT_HTML);

        assertThat(result.status()).isEqualTo(302);
        assertThat(IamTestSession.queryParam(result.location(), "error")).isNull();
        assertThat(IamTestSession.queryParam(result.location(), "code")).isNotBlank();
        assertThat(IamTestSession.queryParam(result.location(), "state")).isEqualTo("state-pnone2");
    }

    @Test
    @DisplayName("OIDC Core §3.1.2.6：prompt=none 但缺少同意时返回 consent_required")
    void authorizationRequestWithPromptNoneAndMissingConsentMustReturnConsentRequired() {
        IamTestSession session = session();
        session.authorize(
                "openid",
                "state-consent-login",
                "nonce-consent-login",
                null,
                IamTestUsers.DENY_USERNAME,
                IamTestUsers.DENY_PASSWORD);

        List<String[]> params =
                session.baseAuthorizationParams("read", "state-pnone-consent", "nonce-x");
        params.add(new String[] {"prompt", "none"});
        HttpResult result = session.authorizationRequest(params, MediaType.TEXT_HTML);

        assertOAuthErrorRedirect(result, "consent_required", "state-pnone-consent");
    }

    @Test
    @DisplayName("OIDC Core §3.1.2.6：prompt=none 与 max_age=0 冲突时返回 login_required")
    void authorizationRequestWithPromptNoneAndExpiredMaxAgeMustReturnLoginRequired() {
        IamTestSession session = session();
        session.authorize(
                "openid",
                "state-none-age-login",
                "nonce-none-age-login",
                null,
                IamTestUsers.USERNAME,
                IamTestUsers.PASSWORD);

        List<String[]> params =
                session.baseAuthorizationParams("openid", "state-pnone-age", "nonce-pnone-age");
        params.add(new String[] {"prompt", "none"});
        params.add(new String[] {"max_age", "0"});
        HttpResult result = session.authorizationRequest(params, MediaType.TEXT_HTML);

        assertOAuthErrorRedirect(result, "login_required", "state-pnone-age");
    }

    @Test
    @DisplayName("OIDC Core §3.1.2.6：prompt=none 不得与 login/consent 组合")
    void authorizationRequestWithPromptNoneCombinedMustBeInvalidRequest() {
        IamTestSession session = session();
        for (String prompt : List.of("none login", "none consent")) {
            List<String[]> params =
                    session.baseAuthorizationParams("openid", "state-pcomb", "nonce-pcomb");
            params.add(new String[] {"prompt", prompt});
            HttpResult result = session.authorizationRequest(params, MediaType.TEXT_HTML);
            assertOAuthErrorRedirect(result, "invalid_request", "state-pcomb");
        }
    }

    @Test
    @DisplayName("OIDC Core §3.1.2.6：prompt=none 且 max_age 未超时返回授权码")
    void authorizationRequestWithPromptNoneAndFreshMaxAgeMustReturnCode() {
        IamTestSession session = session();
        session.authorize(
                "openid",
                "state-pnone-fresh-login",
                "nonce-pnone-fresh-login",
                null,
                IamTestUsers.USERNAME,
                IamTestUsers.PASSWORD);

        List<String[]> params =
                session.baseAuthorizationParams("openid", "state-pnone-fresh", "nonce-pnone-fresh");
        params.add(new String[] {"prompt", "none"});
        params.add(new String[] {"max_age", "3600"});
        HttpResult result = session.authorizationRequest(params, MediaType.TEXT_HTML);

        assertThat(result.status()).isEqualTo(302);
        assertThat(IamTestSession.queryParam(result.location(), "error")).isNull();
        assertThat(IamTestSession.queryParam(result.location(), "code")).isNotBlank();
    }

    @Test
    @DisplayName("OIDC Core §3.1.2.6：未知 prompt 值被忽略")
    void authorizationRequestWithUnknownPromptMustStillSucceed() {
        IamTestSession session = session();
        AuthorizationResult result =
                session.authorize(
                        "openid",
                        "state-unknown-prompt",
                        "nonce-unknown-prompt",
                        Map.of("prompt", "select_account"),
                        IamTestUsers.USERNAME,
                        IamTestUsers.PASSWORD);

        assertThat(result.code()).isNotBlank();
    }

    @Test
    @DisplayName("OIDC Core §3.1.2.6：prompt=login 必须触发重新认证（auth_time 前进）")
    void authorizationRequestWithPromptLoginMustReauthenticate() throws Exception {
        IamTestSession session = session();
        TokenResponse first = openidToken(session, "nonce-login-1");
        long firstAuthTime = epochSeconds(claims(first.idToken()).getDateClaim("auth_time"));

        Thread.sleep(1100);
        AuthorizationResult reauthenticated =
                session.authorize(
                        "openid",
                        "state-login",
                        "nonce-login-2",
                        Map.of("prompt", "login"),
                        IamTestUsers.USERNAME,
                        IamTestUsers.PASSWORD);
        TokenResponse second = session.exchangeCode(reauthenticated.code());
        long secondAuthTime = epochSeconds(claims(second.idToken()).getDateClaim("auth_time"));

        assertThat(secondAuthTime).isGreaterThan(firstAuthTime);
    }

    @Test
    @DisplayName("OIDC Core §3.1.2.1：max_age=0 必须触发重新认证")
    void authorizationRequestWithMaxAgeZeroMustReauthenticate() throws Exception {
        IamTestSession session = session();
        TokenResponse first = openidToken(session, "nonce-age-1");
        long firstAuthTime = epochSeconds(claims(first.idToken()).getDateClaim("auth_time"));

        Thread.sleep(1100);
        AuthorizationResult reauthenticated =
                session.authorize(
                        "openid",
                        "state-age",
                        "nonce-age-2",
                        Map.of("max_age", "0"),
                        IamTestUsers.USERNAME,
                        IamTestUsers.PASSWORD);
        TokenResponse second = session.exchangeCode(reauthenticated.code());
        long secondAuthTime = epochSeconds(claims(second.idToken()).getDateClaim("auth_time"));

        assertThat(secondAuthTime).isGreaterThan(firstAuthTime);
    }

    @Test
    @DisplayName("OIDC Core §3.1.2.1：max_age 未超时不应重新认证")
    void authorizationRequestWithFreshSessionAndMaxAgeMustNotReauthenticate() {
        IamTestSession session = session();
        session.authorize(
                "openid",
                "state-age-ok-login",
                "nonce-age-ok-login",
                null,
                IamTestUsers.USERNAME,
                IamTestUsers.PASSWORD);

        List<String[]> params =
                session.baseAuthorizationParams("openid", "state-age-ok", "nonce-age-ok");
        params.add(new String[] {"max_age", "3600"});
        HttpResult result = session.authorizationRequest(params, MediaType.TEXT_HTML);

        assertThat(result.status()).isEqualTo(302);
        assertThat(session.isLoginRedirect(result.location())).isFalse();
        assertThat(IamTestSession.queryParam(result.location(), "code")).isNotBlank();
    }

    @Test
    @DisplayName("RFC 6749 §4.1.2.1：成功响应必须回传 state")
    void authorizationSuccessMustEchoState() {
        IamTestSession session = session();
        AuthorizationResult result =
                session.authorize(
                        "openid",
                        "state-echo",
                        "nonce-echo",
                        null,
                        IamTestUsers.USERNAME,
                        IamTestUsers.PASSWORD);

        assertThat(result.state()).isEqualTo("state-echo");
        assertThat(result.code()).isNotBlank();
    }

    @Test
    @DisplayName("RFC 6749 §4.1.2.1：拒绝同意必须返回 access_denied 并回传 state")
    void authorizationConsentDeniedMustRedirectAccessDenied() {
        IamTestSession session = session();
        List<String[]> params = session.baseAuthorizationParams("read", "state-deny", "nonce-deny");
        HttpResult first = session.authorizationRequest(params, MediaType.TEXT_HTML);
        assertThat(session.isLoginRedirect(first.location())).isTrue();

        HttpResult login = session.login(IamTestUsers.DENY_USERNAME, IamTestUsers.DENY_PASSWORD);
        HttpResult consentPage = session.get(URI.create(login.location()), MediaType.TEXT_HTML);
        assertThat(consentPage.status()).isEqualTo(200);

        HttpResult denied = session.submitConsent(consentPage, List.of());

        assertOAuthErrorRedirect(denied, "access_denied", "state-deny");
    }

    @Test
    @DisplayName("RFC 6749 §4.1.2.1：同意页只能授予请求 scope 的子集")
    void authorizationConsentMustNarrowGrantedScope() {
        IamTestSession session = session();
        AuthorizationResult result =
                session.authorize(
                        "openid profile read",
                        "state-narrow",
                        "nonce-narrow",
                        null,
                        IamTestUsers.NARROW_USERNAME,
                        IamTestUsers.NARROW_PASSWORD,
                        List.of("profile"));
        TokenResponse token = session.exchangeCode(result.code());

        assertThat(scopes(token.accessToken()))
                .contains("openid", "profile")
                .doesNotContain("read");
        assertThat(token.scope()).contains("openid", "profile").doesNotContain("read");
    }

    @Test
    @DisplayName("OIDC Core §3.1.2.1：scope 不含 openid 时不签发 ID Token")
    void authorizationWithoutOpenidMustNotIssueIdToken() {
        IamTestSession session = session();
        TokenResponse token =
                token(session, "read", IamTestUsers.READ_USERNAME, IamTestUsers.READ_PASSWORD);

        assertThat(token.idToken()).isNull();
        assertThat(token.scope()).contains("read");
        assertThat(scopes(token.accessToken())).contains("read").doesNotContain("openid");
    }

    @Test
    @DisplayName("OIDC Core §3.1.3：scope 含 openid 时必须签发 ID Token")
    void authorizationWithOpenidMustIssueIdToken() {
        IamTestSession session = session();
        TokenResponse token = openidToken(session);

        assertThat(token.idToken()).isNotBlank();
        assertThat(token.scope()).contains("openid");
    }

    // ==================================================================
    // Token endpoint
    // ==================================================================

    @Test
    @DisplayName("RFC 6749 §5.2：HTTP Basic 客户端认证失败返回 401 + Basic challenge")
    void tokenEndpointMustRejectInvalidBasicClient() {
        IamTestSession session = session();
        HttpResult result =
                session.tokenRequestWithBasic(
                        IamTestSession.CLIENT_ID,
                        "wrong-secret",
                        IamTestSession.form(
                                "grant_type",
                                "authorization_code",
                                "code",
                                "bogus",
                                "redirect_uri",
                                IamTestSession.REDIRECT_URI,
                                "code_verifier",
                                IamTestSession.CODE_VERIFIER));

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.header(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Basic");
        assertThat(JsonPath.<String>read(result.body(), "$.error")).isEqualTo("invalid_client");
        assertNoStore(result.headers());
    }

    @Test
    @DisplayName("RFC 6749 §5.2：非 Basic 客户端认证失败返回 400（不能误用 401）")
    void tokenEndpointMustRejectInvalidPostClientWith400() {
        IamTestSession session = session();
        HttpResult result =
                session.tokenRequestWithPostClient(
                        IamTestSession.form(
                                "grant_type",
                                "authorization_code",
                                "code",
                                "bogus",
                                "redirect_uri",
                                IamTestSession.REDIRECT_URI,
                                "code_verifier",
                                IamTestSession.CODE_VERIFIER),
                        IamTestSession.CLIENT_ID,
                        "wrong-secret");

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.header(HttpHeaders.WWW_AUTHENTICATE)).isNull();
        assertThat(JsonPath.<String>read(result.body(), "$.error")).isEqualTo("invalid_client");
    }

    @Test
    @DisplayName("RFC 6749 §5.2：缺少客户端认证返回 invalid_client")
    void tokenEndpointMustRejectMissingClient() {
        IamTestSession session = session();
        HttpResult result =
                session.tokenRequestWithoutClient(
                        IamTestSession.form(
                                "grant_type",
                                "authorization_code",
                                "code",
                                "bogus",
                                "redirect_uri",
                                IamTestSession.REDIRECT_URI,
                                "code_verifier",
                                IamTestSession.CODE_VERIFIER));

        assertThat(result.status()).isEqualTo(400);
        assertThat(JsonPath.<String>read(result.body(), "$.error")).isEqualTo("invalid_client");
    }

    @Test
    @DisplayName("RFC 8705 §2.1.1：tls_client_auth 客户端可用证书完成授权码交换与刷新")
    void tokenEndpointMustAcceptTlsClientAuth(@ClusterPki PkiProvider pki) {
        IamTestSession session = tlsClientSession(pki);
        AuthorizationResult result =
                session.authorize(
                        "openid",
                        "state-tls",
                        "nonce-tls",
                        null,
                        IamTestUsers.USERNAME,
                        IamTestUsers.PASSWORD);
        TokenResponse token = session.exchangeCode(result.code());

        assertThat(token.accessToken()).isNotBlank();
        assertThat(token.scope()).contains("openid");

        TokenResponse refreshed = session.refresh(token.refreshToken());
        assertThat(refreshed.accessToken()).isNotBlank();
    }

    @Test
    @DisplayName("RFC 8705：tls_client_auth 客户端缺少证书返回 invalid_client")
    void tokenEndpointMustRejectTlsClientWithoutCertificate(@ClusterPki PkiProvider pki) {
        IamTestSession session =
                new IamTestSession(issuer, pki.trustContext(), IamTestSession.TLS_CLIENT_ID);
        HttpResult result =
                session.tokenRequest(
                        IamTestSession.form(
                                "grant_type",
                                "authorization_code",
                                "code",
                                "bogus",
                                "redirect_uri",
                                IamTestSession.TLS_REDIRECT_URI,
                                "code_verifier",
                                IamTestSession.CODE_VERIFIER));

        assertThat(result.status()).isEqualTo(400);
        assertThat(JsonPath.<String>read(result.body(), "$.error")).isEqualTo("invalid_client");
    }

    @Test
    @DisplayName("RFC 8705 §2.1.1：证书 Subject DN 不匹配返回 invalid_client")
    void tokenEndpointMustRejectTlsClientWithWrongSubject(@ClusterPki PkiProvider pki) {
        IssuedCertificate wrong = pki.issue(CertificateRequest.client("CN=wrong-client"));
        IamTestSession session =
                new IamTestSession(issuer, pki.sslContext(wrong), IamTestSession.TLS_CLIENT_ID);
        HttpResult result =
                session.tokenRequest(
                        IamTestSession.form(
                                "grant_type",
                                "authorization_code",
                                "code",
                                "bogus",
                                "redirect_uri",
                                IamTestSession.TLS_REDIRECT_URI,
                                "code_verifier",
                                IamTestSession.CODE_VERIFIER));

        assertThat(result.status()).isEqualTo(400);
        assertThat(JsonPath.<String>read(result.body(), "$.error")).isEqualTo("invalid_client");
    }

    @Test
    @DisplayName("mTLS：client_secret_basic 客户端不要求客户端证书")
    void tokenEndpointMustAllowSecretClientWithoutCertificate(@ClusterPki PkiProvider pki) {
        IamTestSession session = new IamTestSession(issuer, pki.trustContext());
        AuthorizationResult result =
                session.authorize(
                        "openid",
                        "state-no-cert",
                        "nonce-no-cert",
                        null,
                        IamTestUsers.USERNAME,
                        IamTestUsers.PASSWORD);
        TokenResponse token = session.exchangeCode(result.code());

        assertThat(token.accessToken()).isNotBlank();
    }

    @Test
    @DisplayName("mTLS：discovery 不要求客户端证书")
    void discoveryMustAllowAnonymousClient(@ClusterPki PkiProvider pki) {
        IamTestSession session = new IamTestSession(issuer, pki.trustContext());
        HttpResult result =
                session.get("/.well-known/openid-configuration", MediaType.APPLICATION_JSON);

        assertThat(result.status()).isEqualTo(200);
        assertThat(json(result.body()).get("issuer")).isEqualTo(issuer);
    }

    @Test
    @DisplayName("mTLS：非受信 CA 的客户端证书在 TLS 握手阶段被拒绝")
    void mtlsMustRejectUntrustedClientCertificate(
            @ClusterUntrustedClient SSLContext untrustedClientSslContext) {
        IamTestSession session = new IamTestSession(issuer, untrustedClientSslContext);

        assertThatThrownBy(
                        () ->
                                session.get(
                                        "/.well-known/openid-configuration",
                                        MediaType.APPLICATION_JSON))
                .hasRootCauseInstanceOf(SSLException.class);
    }

    @Test
    @DisplayName("RFC 6749 §5.2：客户端未被授权使用 grant_type 时返回 unauthorized_client")
    void tokenEndpointMustRejectUnsupportedGrantType() {
        IamTestSession session = session();
        HttpResult result = session.tokenRequest("grant_type=client_credentials");

        assertThat(result.status()).isEqualTo(400);
        assertThat(JsonPath.<String>read(result.body(), "$.error"))
                .isEqualTo("unauthorized_client");
    }

    @Test
    @DisplayName("RFC 6749 §5.2：缺少 grant_type 返回 invalid_request")
    void tokenEndpointMustRejectMissingGrantType() {
        IamTestSession session = session();
        HttpResult result = session.tokenRequest("");

        assertThat(result.status()).isEqualTo(400);
        assertThat(JsonPath.<String>read(result.body(), "$.error")).isEqualTo("invalid_request");
    }

    @Test
    @DisplayName("RFC 6749 §3.2：重复 grant_type 参数返回 invalid_request")
    void tokenEndpointMustRejectDuplicateGrantType() {
        IamTestSession session = session();
        HttpResult result =
                session.tokenRequest(
                        "grant_type=authorization_code&grant_type=refresh_token&code=bogus");

        assertThat(result.status()).isEqualTo(400);
        assertThat(JsonPath.<String>read(result.body(), "$.error")).isEqualTo("invalid_request");
    }

    @Test
    @DisplayName("RFC 6749 §5.2：invalid_grant 必须是 400，不能带 Basic challenge")
    void tokenEndpointMustRejectInvalidCodeWith400() {
        IamTestSession session = session();
        HttpResult result =
                session.tokenRequest(
                        IamTestSession.form(
                                "grant_type",
                                "authorization_code",
                                "code",
                                "bogus-code",
                                "redirect_uri",
                                IamTestSession.REDIRECT_URI,
                                "code_verifier",
                                IamTestSession.CODE_VERIFIER));

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.header(HttpHeaders.WWW_AUTHENTICATE)).isNull();
        assertThat(JsonPath.<String>read(result.body(), "$.error")).isEqualTo("invalid_grant");
    }

    @Test
    @DisplayName("RFC 7636 §4.6：错误 code_verifier 返回 400 invalid_grant")
    void tokenEndpointMustRejectWrongCodeVerifier() {
        IamTestSession session = session();
        String code =
                session.authorize(
                                "openid",
                                "state-cv",
                                "nonce-cv",
                                null,
                                IamTestUsers.USERNAME,
                                IamTestUsers.PASSWORD)
                        .code();

        HttpResult result =
                session.tokenRequest(
                        IamTestSession.form(
                                "grant_type",
                                "authorization_code",
                                "code",
                                code,
                                "redirect_uri",
                                IamTestSession.REDIRECT_URI,
                                "code_verifier",
                                "A".repeat(43)));

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.header(HttpHeaders.WWW_AUTHENTICATE)).isNull();
        assertThat(JsonPath.<String>read(result.body(), "$.error")).isEqualTo("invalid_grant");
    }

    @Test
    @DisplayName("RFC 7636 §4.6：缺少 code_verifier 返回 400 invalid_grant")
    void tokenEndpointMustRejectMissingCodeVerifier() {
        IamTestSession session = session();
        String code =
                session.authorize(
                                "openid",
                                "state-nocv",
                                "nonce-nocv",
                                null,
                                IamTestUsers.USERNAME,
                                IamTestUsers.PASSWORD)
                        .code();

        HttpResult result =
                session.tokenRequest(
                        IamTestSession.form(
                                "grant_type",
                                "authorization_code",
                                "code",
                                code,
                                "redirect_uri",
                                IamTestSession.REDIRECT_URI));

        assertThat(result.status()).isEqualTo(400);
        assertThat(JsonPath.<String>read(result.body(), "$.error")).isEqualTo("invalid_grant");
    }

    @Test
    @DisplayName("RFC 6749 §4.1.3：token 请求的 redirect_uri 必须与授权请求一致")
    void tokenEndpointMustRejectWrongRedirectUri() {
        IamTestSession session = session();
        String code =
                session.authorize(
                                "openid",
                                "state-ru",
                                "nonce-ru",
                                null,
                                IamTestUsers.USERNAME,
                                IamTestUsers.PASSWORD)
                        .code();

        HttpResult result =
                session.tokenRequest(
                        IamTestSession.form(
                                "grant_type",
                                "authorization_code",
                                "code",
                                code,
                                "redirect_uri",
                                "http://127.0.0.1:9999/callback",
                                "code_verifier",
                                IamTestSession.CODE_VERIFIER));

        assertThat(result.status()).isEqualTo(400);
        assertThat(JsonPath.<String>read(result.body(), "$.error")).isEqualTo("invalid_grant");
    }

    @Test
    @DisplayName("RFC 6749 §4.1.2：授权码只能使用一次")
    void authorizationCodeMustBeSingleUse() {
        IamTestSession session = session();
        String code =
                session.authorize(
                                "openid",
                                "state-reuse",
                                "nonce-reuse",
                                null,
                                IamTestUsers.USERNAME,
                                IamTestUsers.PASSWORD)
                        .code();
        session.exchangeCode(code);

        HttpResult reused =
                session.tokenRequest(
                        IamTestSession.form(
                                "grant_type",
                                "authorization_code",
                                "code",
                                code,
                                "redirect_uri",
                                IamTestSession.REDIRECT_URI,
                                "code_verifier",
                                IamTestSession.CODE_VERIFIER));

        assertThat(reused.status()).isEqualTo(400);
        assertThat(JsonPath.<String>read(reused.body(), "$.error")).isEqualTo("invalid_grant");
    }

    @Test
    @DisplayName("RFC 6749 §5.1：token 响应必须 no-store / no-cache")
    void tokenResponseMustBeNonCacheable() {
        IamTestSession session = session();
        String code =
                session.authorize(
                                "openid",
                                "state-cache",
                                "nonce-cache",
                                null,
                                IamTestUsers.USERNAME,
                                IamTestUsers.PASSWORD)
                        .code();

        HttpResult result =
                session.tokenRequest(
                        IamTestSession.form(
                                "grant_type",
                                "authorization_code",
                                "code",
                                code,
                                "redirect_uri",
                                IamTestSession.REDIRECT_URI,
                                "code_verifier",
                                IamTestSession.CODE_VERIFIER));

        assertThat(result.status()).isEqualTo(200);
        assertNoStore(result.headers());
        assertThat(MediaType.APPLICATION_JSON.isCompatibleWith(result.contentType())).isTrue();
    }

    @Test
    @DisplayName("RFC 6749 §5.1：成功响应包含 Bearer token 与 scope")
    void tokenResponseMustContainBearerTokens() {
        IamTestSession session = session();
        TokenResponse token = openidToken(session);

        assertThat(token.tokenType()).isEqualTo("Bearer");
        assertThat(token.expiresIn()).isPositive();
        assertThat(token.accessToken()).isNotBlank();
        assertThat(token.refreshToken()).isNotBlank();
        assertThat(token.idToken()).isNotBlank();
        assertThat(token.scope()).contains("openid");
    }

    @Test
    @DisplayName("RFC 6749 §6：refresh token 轮换且旧 refresh token 失效")
    void refreshTokenMustRotate() {
        IamTestSession session = session();
        TokenResponse original = openidToken(session);

        TokenResponse refreshed = session.refresh(original.refreshToken());
        assertThat(refreshed.accessToken()).isNotEqualTo(original.accessToken());
        assertThat(refreshed.refreshToken()).isNotEqualTo(original.refreshToken());

        HttpResult reused = session.refreshRequest(original.refreshToken());
        assertThat(reused.status()).isEqualTo(400);
        assertThat(JsonPath.<String>read(reused.body(), "$.error")).isEqualTo("invalid_grant");
    }

    @Test
    @DisplayName("RFC 6749 §3.2：重复参数返回 invalid_request")
    void tokenEndpointMustRejectDuplicateParameters() {
        IamTestSession session = session();
        HttpResult result =
                session.tokenRequest(
                        "grant_type=authorization_code&code=one&code=two"
                                + "&redirect_uri="
                                + IamTestSession.REDIRECT_URI
                                + "&code_verifier="
                                + IamTestSession.CODE_VERIFIER);

        assertThat(result.status()).isEqualTo(400);
        assertThat(JsonPath.<String>read(result.body(), "$.error")).isEqualTo("invalid_request");
    }

    @Test
    @DisplayName("RFC 6749 §5.2：畸形 Basic 头按 malformed request 返回 400")
    void tokenEndpointMustRejectMalformedBasicHeader() {
        IamTestSession session = session();
        var spec =
                session.client()
                        .post()
                        .uri("/oauth2/token")
                        .headers(headers -> headers.set(HttpHeaders.AUTHORIZATION, "Basic !!!"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .accept(MediaType.APPLICATION_JSON)
                        .body(
                                IamTestSession.form(
                                        "grant_type",
                                        "authorization_code",
                                        "code",
                                        "bogus",
                                        "redirect_uri",
                                        IamTestSession.REDIRECT_URI,
                                        "code_verifier",
                                        IamTestSession.CODE_VERIFIER))
                        .exchange()
                        .returnResult(String.class);

        assertThat(spec.getStatus().value()).isEqualTo(400);
        assertThat(JsonPath.<String>read(spec.getResponseBody(), "$.error"))
                .isEqualTo("invalid_request");
    }

    @Test
    @DisplayName("RFC 7636 §4.1：code_verifier 长度不足时 invalid_grant")
    void tokenEndpointMustRejectShortCodeVerifier() {
        IamTestSession session = session();
        String code =
                session.authorize(
                                "openid",
                                "state-shortcv",
                                "nonce-shortcv",
                                null,
                                IamTestUsers.USERNAME,
                                IamTestUsers.PASSWORD)
                        .code();

        HttpResult result =
                session.tokenRequest(
                        IamTestSession.form(
                                "grant_type",
                                "authorization_code",
                                "code",
                                code,
                                "redirect_uri",
                                IamTestSession.REDIRECT_URI,
                                "code_verifier",
                                "abc"));

        assertThat(result.status()).isEqualTo(400);
        assertThat(JsonPath.<String>read(result.body(), "$.error")).isEqualTo("invalid_grant");
    }

    @Test
    @DisplayName("RFC 6749 §5.2：grant_type 大小写敏感")
    void tokenEndpointMustTreatGrantTypeCaseSensitively() {
        IamTestSession session = session();
        HttpResult result = session.tokenRequest("grant_type=Authorization_Code&code=bogus");

        assertThat(result.status()).isEqualTo(400);
        assertThat(JsonPath.<String>read(result.body(), "$.error"))
                .isEqualTo("unsupported_grant_type");
    }

    @Test
    @DisplayName("OIDC Core §12.2：refresh 响应包含新的 ID Token 与 scope")
    void refreshResponseMustContainIdToken() {
        IamTestSession session = session();
        TokenResponse original = openidToken(session);

        TokenResponse refreshed = session.refresh(original.refreshToken());

        assertThat(refreshed.idToken()).isNotBlank();
        assertThat(refreshed.scope()).contains("openid");
        assertThat(refreshed.tokenType()).isEqualTo("Bearer");
    }

    // ==================================================================
    // ID Token / Access Token
    // ==================================================================

    @Test
    @DisplayName("OIDC Core §2：ID Token 必需 claims（iss/sub/aud/exp/iat/auth_time/nonce）")
    void idTokenMustContainRequiredClaims() throws Exception {
        IamTestSession session = session();
        String nonce = "nonce-claims-" + UUID.randomUUID();
        AuthorizationResult authorization =
                session.authorize(
                        "openid",
                        "state-claims",
                        nonce,
                        null,
                        IamTestUsers.USERNAME,
                        IamTestUsers.PASSWORD);
        TokenResponse token = session.exchangeCode(authorization.code());

        SignedJWT jwt = SignedJWT.parse(token.idToken());
        JWTClaimsSet claims = jwt.getJWTClaimsSet();

        assertThat(claims.getIssuer()).isEqualTo(issuer);
        assertThat(claims.getSubject()).isEqualTo(IamTestUsers.USERNAME);
        assertThat(claims.getAudience()).contains(IamTestSession.CLIENT_ID);
        assertThat(claims.getStringClaim("azp")).isEqualTo(IamTestSession.CLIENT_ID);
        assertThat(claims.getExpirationTime()).isAfter(claims.getIssueTime());
        assertThat(claims.getIssueTime()).isBeforeOrEqualTo(new Date());
        assertThat(claims.getJWTID()).isNotBlank();
        assertThat(claims.getStringClaim("nonce")).isEqualTo(nonce);
        assertThat(claims.getDateClaim("auth_time")).isNotNull();
    }

    @Test
    @DisplayName("OIDC Core §3.1.2.1：未请求 nonce 时 ID Token 不应包含 nonce")
    void idTokenWithoutNonceMustNotContainNonce() throws Exception {
        IamTestSession session = session();
        AuthorizationResult authorization =
                session.authorize(
                        "openid",
                        "state-nononce",
                        null,
                        null,
                        IamTestUsers.USERNAME,
                        IamTestUsers.PASSWORD);
        TokenResponse token = session.exchangeCode(authorization.code());

        assertThat(claims(token.idToken()).getClaim("nonce")).isNull();
    }

    @Test
    @DisplayName("RFC 6749 §3.3 / OIDC Core：Access Token 必需 claims 与 scope")
    void accessTokenMustContainScopeAndIdentity() throws Exception {
        IamTestSession session = session();
        TokenResponse token = openidToken(session);

        SignedJWT jwt = SignedJWT.parse(token.accessToken());
        JWTClaimsSet claims = jwt.getJWTClaimsSet();

        assertThat(claims.getIssuer()).isEqualTo(issuer);
        assertThat(claims.getSubject()).isEqualTo(IamTestUsers.USERNAME);
        assertThat(claims.getAudience()).contains(IamTestSession.CLIENT_ID);
        assertThat(claims.getExpirationTime()).isAfter(claims.getIssueTime());
        assertThat(claims.getJWTID()).isNotBlank();
        assertThat(scopes(token.accessToken())).contains("openid");
        assertThat(claims.getClaim("nonce")).isNull();
    }

    @Test
    @DisplayName("OIDC Core §5.3：ID Token 不能作为 UserInfo 的 Bearer token")
    void idTokenMustNotBeAcceptedByUserInfo() {
        IamTestSession session = session();
        TokenResponse token = openidToken(session);

        HttpResult result = session.userInfo(token.idToken());

        assertThat(result.status()).isEqualTo(401);
        assertThat(JsonPath.<String>read(result.body(), "$.error")).isEqualTo("invalid_token");
    }

    @Test
    @DisplayName("RFC 7662：ID Token 不能被 introspection 报告为 active")
    void idTokenMustNotBeIntrospectableAsAccessToken() {
        IamTestSession session = session();
        TokenResponse token = openidToken(session);

        HttpResult result = session.introspect(token.idToken());

        assertThat(result.status()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(result.body(), "$.active")).isFalse();
    }

    @Test
    @DisplayName("RFC 6750：资源服务器不得接受 ID Token 作为 Access Token")
    void idTokenMustNotBeAcceptedByResourceServer(@ClusterService("im") RestTestClient im) {
        IamTestSession session = session();
        TokenResponse token = openidToken(session);

        var result =
                im.get()
                        .uri("/api/me")
                        .headers(headers -> headers.setBearerAuth(token.idToken()))
                        .exchange()
                        .returnResult(String.class);

        assertThat(result.getStatus().value()).isEqualTo(401);
    }

    // ==================================================================
    // UserInfo
    // ==================================================================

    @Test
    @DisplayName("RFC 6750 §3：无凭证时 401 + Bearer challenge（不带 error code）")
    void userInfoMustRequireBearerToken() {
        IamTestSession session = session();
        HttpResult result = session.get("/userinfo", MediaType.APPLICATION_JSON);

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.header(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        assertThat(result.header(HttpHeaders.WWW_AUTHENTICATE)).doesNotContain("error=");
    }

    @Test
    @DisplayName("RFC 6750 §3.1：无效 token 返回 401 + invalid_token challenge")
    void userInfoMustRejectInvalidToken() {
        IamTestSession session = session();
        HttpResult result = session.userInfo("not-a-token");

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.header(HttpHeaders.WWW_AUTHENTICATE)).contains("error=\"invalid_token\"");
        assertThat(result.body()).isNullOrEmpty();
    }

    @Test
    @DisplayName("OIDC Core §5.3.2：UserInfo 返回与 ID Token 一致的 sub")
    void userInfoMustReturnSubject() throws Exception {
        IamTestSession session = session();
        TokenResponse token = openidToken(session);

        HttpResult result = session.userInfo(token.accessToken());

        assertThat(result.status()).isEqualTo(200);
        assertThat(MediaType.APPLICATION_JSON.isCompatibleWith(result.contentType())).isTrue();
        assertThat(JsonPath.<String>read(result.body(), "$.sub"))
                .isEqualTo(claims(token.idToken()).getSubject());
        assertNoStore(result.headers());
    }

    @Test
    @DisplayName("OIDC Core §5.3.1：UserInfo 必须同时支持 GET 与 POST")
    void userInfoMustSupportGetAndPost() {
        IamTestSession session = session();
        TokenResponse token = openidToken(session);

        assertThat(session.userInfo(token.accessToken()).status()).isEqualTo(200);
        assertThat(session.userInfoPost(token.accessToken()).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("RFC 6750 §3.1：缺少 openid scope 时 UserInfo 返回 insufficient_scope")
    void userInfoMustRejectTokenWithoutOpenidScope() {
        IamTestSession session = session();
        TokenResponse token =
                token(session, "read", IamTestUsers.READ_USERNAME, IamTestUsers.READ_PASSWORD);

        HttpResult result = session.userInfo(token.accessToken());

        assertThat(result.status()).isEqualTo(403);
        assertThat(JsonPath.<String>read(result.body(), "$.error")).isEqualTo("insufficient_scope");
    }

    @Test
    @DisplayName("RFC 7009：被撤销的 Access Token 不能再访问 UserInfo")
    void userInfoMustRejectRevokedAccessToken() {
        IamTestSession session = session();
        TokenResponse token = openidToken(session);
        assertThat(session.revoke(token.accessToken(), "access_token").status()).isEqualTo(200);

        HttpResult result = session.userInfo(token.accessToken());

        assertThat(result.status()).isEqualTo(401);
        assertThat(JsonPath.<String>read(result.body(), "$.error")).isEqualTo("invalid_token");
    }

    // ==================================================================
    // Introspection (RFC 7662)
    // ==================================================================

    @Test
    @DisplayName("RFC 7662 §2.2：有效 Access Token 返回 active=true 与元数据")
    void introspectionMustReportActiveAccessToken() {
        IamTestSession session = session();
        TokenResponse token = openidToken(session);

        HttpResult result = session.introspect(token.accessToken());

        assertThat(result.status()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(result.body(), "$.active")).isTrue();
        assertThat(JsonPath.<String>read(result.body(), "$.client_id"))
                .isEqualTo(IamTestSession.CLIENT_ID);
        assertThat(JsonPath.<String>read(result.body(), "$.sub")).isEqualTo(IamTestUsers.USERNAME);
        assertThat(JsonPath.<String>read(result.body(), "$.token_type")).isEqualTo("Bearer");
        assertThat(JsonPath.<String>read(result.body(), "$.iss")).isEqualTo(issuer);
        assertThat(JsonPath.<String>read(result.body(), "$.scope")).contains("openid");
        assertThat(JsonPath.<Number>read(result.body(), "$.exp")).isNotNull();
        assertThat(JsonPath.<Number>read(result.body(), "$.iat")).isNotNull();
        assertNoStore(result.headers());
    }

    @Test
    @DisplayName("RFC 7662 §2.2：未知 token 返回 active=false")
    void introspectionMustReportInactiveUnknownToken() {
        IamTestSession session = session();
        HttpResult result = session.introspect("unknown-token");

        assertThat(result.status()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(result.body(), "$.active")).isFalse();
    }

    @Test
    @DisplayName("RFC 7662：被撤销 token 返回 active=false")
    void introspectionMustReportInactiveRevokedToken() {
        IamTestSession session = session();
        TokenResponse token = openidToken(session);
        session.revoke(token.accessToken(), "access_token");

        HttpResult result = session.introspect(token.accessToken());

        assertThat(result.status()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(result.body(), "$.active")).isFalse();
    }

    @Test
    @DisplayName("RFC 7662 §2.2：refresh token 返回 active=true 与 client_id")
    void introspectionMustReportActiveRefreshToken() {
        IamTestSession session = session();
        TokenResponse token = openidToken(session);

        HttpResult result = session.introspect(token.refreshToken());

        assertThat(result.status()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(result.body(), "$.active")).isTrue();
        assertThat(JsonPath.<String>read(result.body(), "$.client_id"))
                .isEqualTo(IamTestSession.CLIENT_ID);
    }

    @Test
    @DisplayName("RFC 7662 §2.3：无效客户端认证返回 401 invalid_client")
    void introspectionMustRejectInvalidClient() {
        IamTestSession session = session();
        HttpResult result =
                session.introspectWithBasic("any-token", IamTestSession.CLIENT_ID, "wrong-secret");

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.header(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Basic");
        assertThat(JsonPath.<String>read(result.body(), "$.error")).isEqualTo("invalid_client");
    }

    @Test
    @DisplayName("RFC 7662 §2.3：缺少客户端认证返回 invalid_client")
    void introspectionMustRejectMissingClient() {
        IamTestSession session = session();
        HttpResult result =
                session.postForm(
                        "/oauth2/introspect",
                        IamTestSession.form("token", "any-token"),
                        MediaType.APPLICATION_JSON,
                        null,
                        null);

        assertThat(result.status()).isEqualTo(400);
        assertThat(JsonPath.<String>read(result.body(), "$.error")).isEqualTo("invalid_client");
    }

    @Test
    @DisplayName("RFC 7662 §2.1：未知 token_type_hint 可忽略")
    void introspectionMustIgnoreUnknownTokenTypeHint() {
        IamTestSession session = session();
        TokenResponse token = openidToken(session);

        HttpResult result =
                session.postForm(
                        "/oauth2/introspect",
                        IamTestSession.form(
                                List.of(
                                        new String[] {"token", token.accessToken()},
                                        new String[] {"token_type_hint", "unsupported_hint"})),
                        MediaType.APPLICATION_JSON,
                        IamTestSession.CLIENT_ID,
                        IamTestSession.CLIENT_SECRET);

        assertThat(result.status()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(result.body(), "$.active")).isTrue();
    }

    // ==================================================================
    // Revocation (RFC 7009)
    // ==================================================================

    @Test
    @DisplayName("RFC 7009 §2.2：未知 token 返回 200")
    void revocationMustReturn200ForUnknownToken() {
        IamTestSession session = session();
        HttpResult result = session.revoke("unknown-token", null);

        assertThat(result.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("RFC 7009 §2.2：撤销 Access Token 后 introspection 为 inactive")
    void revocationMustRevokeAccessToken() {
        IamTestSession session = session();
        TokenResponse token = openidToken(session);

        assertThat(session.revoke(token.accessToken(), "access_token").status()).isEqualTo(200);
        assertThat(
                        JsonPath.<Boolean>read(
                                session.introspect(token.accessToken()).body(), "$.active"))
                .isFalse();
    }

    @Test
    @DisplayName("RFC 7009 §2.2：撤销 Refresh Token 后不能再刷新")
    void revocationMustRevokeRefreshToken() {
        IamTestSession session = session();
        TokenResponse token = openidToken(session);

        assertThat(session.revoke(token.refreshToken(), "refresh_token").status()).isEqualTo(200);

        HttpResult refreshed = session.refreshRequest(token.refreshToken());
        assertThat(refreshed.status()).isEqualTo(400);
        assertThat(JsonPath.<String>read(refreshed.body(), "$.error")).isEqualTo("invalid_grant");
    }

    @Test
    @DisplayName("RFC 7009 §2.2.1：无效客户端返回 401 invalid_client")
    void revocationMustRejectInvalidClient() {
        IamTestSession session = session();
        HttpResult result =
                session.revokeWithBasic(
                        "any-token", "access_token", IamTestSession.CLIENT_ID, "wrong-secret");

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.header(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Basic");
        assertThat(JsonPath.<String>read(result.body(), "$.error")).isEqualTo("invalid_client");
    }

    @Test
    @DisplayName("RFC 7009 §2.1：缺少客户端认证返回 invalid_client")
    void revocationMustRejectMissingClient() {
        IamTestSession session = session();
        HttpResult result =
                session.postForm(
                        "/oauth2/revoke",
                        IamTestSession.form("token", "any-token"),
                        MediaType.APPLICATION_JSON,
                        null,
                        null);

        assertThat(result.status()).isEqualTo(400);
        assertThat(JsonPath.<String>read(result.body(), "$.error")).isEqualTo("invalid_client");
    }

    @Test
    @DisplayName("RFC 7009 §2.1：缺少 token 参数返回 400 invalid_request")
    void revocationMustRequireTokenParameter() {
        IamTestSession session = session();
        HttpResult result =
                session.postForm(
                        "/oauth2/revoke",
                        "",
                        MediaType.APPLICATION_JSON,
                        IamTestSession.CLIENT_ID,
                        IamTestSession.CLIENT_SECRET);

        assertThat(result.status()).isEqualTo(400);
        assertThat(JsonPath.<String>read(result.body(), "$.error")).isEqualTo("invalid_request");
    }

    // ==================================================================
    // RP-Initiated Logout
    // ==================================================================

    @Test
    @DisplayName("OIDC RP-Initiated Logout：未注册的 post_logout_redirect_uri 必须拒绝")
    void logoutMustRejectUnregisteredPostLogoutRedirectUri() {
        IamTestSession session = session();
        TokenResponse token = openidToken(session);

        HttpResult result =
                session.logout(
                        Map.of(
                                "id_token_hint",
                                token.idToken(),
                                "post_logout_redirect_uri",
                                "https://evil.example/cb"));

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.location()).isNull();
    }

    @Test
    @DisplayName("OIDC RP-Initiated Logout：无效 id_token_hint 必须拒绝")
    void logoutMustRejectInvalidIdTokenHint() {
        IamTestSession session = session();
        HttpResult result =
                session.logout(
                        Map.of(
                                "id_token_hint",
                                "not-a-valid-jwt",
                                "post_logout_redirect_uri",
                                LOGOUT_REDIRECT_URI));

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.location()).isNull();
    }

    @Test
    @DisplayName("OIDC RP-Initiated Logout：有效 id_token_hint 且无 redirect 时回到 OP 默认页")
    void logoutMustEndSessionWithValidIdTokenHint() {
        IamTestSession session = session();
        TokenResponse token = openidToken(session);

        HttpResult result = session.logout(Map.of("id_token_hint", token.idToken()));

        assertThat(result.status()).isEqualTo(302);
        assertThat(result.location()).isNotNull();
        assertThat(URI.create(result.location()).getPath()).isEqualTo("/");
    }

    @Test
    @DisplayName("OIDC RP-Initiated Logout：注册 redirect 时必须回传 state")
    void logoutMustRedirectToRegisteredUriWithState() {
        IamTestSession session = session();
        TokenResponse token = openidToken(session);

        HttpResult result =
                session.logout(
                        Map.of(
                                "id_token_hint",
                                token.idToken(),
                                "post_logout_redirect_uri",
                                LOGOUT_REDIRECT_URI,
                                "state",
                                "logout-state"));

        assertThat(result.status()).isEqualTo(302);
        assertThat(result.location()).startsWith(LOGOUT_REDIRECT_URI);
        assertThat(IamTestSession.queryParam(result.location(), "state")).isEqualTo("logout-state");
    }

    @Test
    @DisplayName("OIDC RP-Initiated Logout：缺少 id_token_hint 时不得重定向到客户端")
    void logoutWithoutParametersMustReturnError() {
        IamTestSession session = session();
        HttpResult result = session.logout(Map.of());

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.location()).isNull();
    }

    // ==================================================================
    // Resource server (IM)
    // ==================================================================

    @Test
    @DisplayName("RFC 6750 §3：资源服务器无 token 返回 401 + Bearer challenge")
    void resourceServerMustRequireBearerToken(@ClusterService("im") RestTestClient im) {
        var result = im.get().uri("/api/me").exchange().returnResult(String.class);

        assertThat(result.getStatus().value()).isEqualTo(401);
        assertThat(result.getResponseHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .startsWith("Bearer");
    }

    @Test
    @DisplayName("RFC 6750 §3.1：资源服务器拒绝无效 token")
    void resourceServerMustRejectInvalidToken(@ClusterService("im") RestTestClient im) {
        var result =
                im.get()
                        .uri("/api/me")
                        .headers(headers -> headers.setBearerAuth("not-a-token"))
                        .exchange()
                        .returnResult(String.class);

        assertThat(result.getStatus().value()).isEqualTo(401);
        assertThat(result.getResponseHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .contains("error=\"invalid_token\"");
    }

    @Test
    @DisplayName("OIDC：资源服务器接受 Access Token 并读取 sub/scope")
    void resourceServerMustAcceptAccessToken(@ClusterService("im") RestTestClient im) {
        IamTestSession session = session();
        TokenResponse token = openidToken(session);

        var result =
                im.get()
                        .uri("/api/me")
                        .headers(headers -> headers.setBearerAuth(token.accessToken()))
                        .exchange()
                        .returnResult(String.class);

        assertThat(result.getStatus().value()).isEqualTo(200);
        assertThat(result.getResponseBody()).contains(IamTestUsers.USERNAME);
        assertThat(result.getResponseBody()).contains("openid");
    }

    @Test
    @DisplayName("RFC 6750 §3.1：资源服务器按 scope 授权（SCOPE_read）")
    void resourceServerMustEnforceScope(@ClusterService("im") RestTestClient im) {
        IamTestSession session = session();
        TokenResponse readToken =
                token(
                        session,
                        "openid profile read",
                        IamTestUsers.CONSENT_USERNAME,
                        IamTestUsers.CONSENT_PASSWORD);
        TokenResponse openidOnly = openidToken(session);

        var allowed =
                im.get()
                        .uri("/api/read")
                        .headers(headers -> headers.setBearerAuth(readToken.accessToken()))
                        .exchange()
                        .returnResult(String.class);
        var forbidden =
                im.get()
                        .uri("/api/read")
                        .headers(headers -> headers.setBearerAuth(openidOnly.accessToken()))
                        .exchange()
                        .returnResult(String.class);

        assertThat(allowed.getStatus().value()).isEqualTo(200);
        assertThat(forbidden.getStatus().value()).isEqualTo(403);
    }

    @Test
    @DisplayName("RFC 6750 §3.1：资源服务器拒绝被篡改的 JWT")
    void resourceServerMustRejectTamperedToken(@ClusterService("im") RestTestClient im) {
        IamTestSession session = session();
        TokenResponse token = openidToken(session);

        String[] parts = token.accessToken().split("\\.");
        String payload =
                new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                        .replace(
                                "\"sub\":\"" + IamTestUsers.USERNAME + "\"", "\"sub\":\"mallory\"");
        String tampered =
                parts[0]
                        + "."
                        + Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                        + "."
                        + parts[2];

        var result =
                im.get()
                        .uri("/api/me")
                        .headers(headers -> headers.setBearerAuth(tampered))
                        .exchange()
                        .returnResult(String.class);

        assertThat(result.getStatus().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("RFC 8725：资源服务器拒绝 alg=none 的 JWT")
    void resourceServerMustRejectAlgNoneToken(@ClusterService("im") RestTestClient im) {
        IamTestSession session = session();
        TokenResponse token = openidToken(session);

        String[] parts = token.accessToken().split("\\.");
        String header =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String unsigned = header + "." + parts[1] + ".";

        var result =
                im.get()
                        .uri("/api/me")
                        .headers(headers -> headers.setBearerAuth(unsigned))
                        .exchange()
                        .returnResult(String.class);

        assertThat(result.getStatus().value()).isEqualTo(401);
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    private static IamTestSession session() {
        return new IamTestSession(issuer, testClientSslContext);
    }

    private static IamTestSession tlsClientSession(PkiProvider pki) {
        IssuedCertificate cert = pki.issue(CertificateRequest.client("CN=tls-client"));
        return new IamTestSession(issuer, pki.sslContext(cert), IamTestSession.TLS_CLIENT_ID);
    }

    private static TokenResponse openidToken(IamTestSession session) {
        return openidToken(session, "nonce-" + UUID.randomUUID());
    }

    private static TokenResponse openidToken(IamTestSession session, String nonce) {
        AuthorizationResult authorization =
                session.authorize(
                        "openid",
                        "state-" + UUID.randomUUID(),
                        nonce,
                        null,
                        IamTestUsers.USERNAME,
                        IamTestUsers.PASSWORD);
        return session.exchangeCode(authorization.code());
    }

    private static TokenResponse token(
            IamTestSession session, String scope, String username, String password) {
        AuthorizationResult authorization =
                session.authorize(
                        scope,
                        "state-" + UUID.randomUUID(),
                        "nonce-" + UUID.randomUUID(),
                        null,
                        username,
                        password);
        return session.exchangeCode(authorization.code());
    }

    private static Map<String, Object> json(String body) {
        return JsonPath.parse(body).json();
    }

    private static List<String> strings(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(String::valueOf).toList();
        }
        return value == null ? List.of() : List.of(String.valueOf(value));
    }

    private static List<String> scopes(String jwt) {
        try {
            Object scope = claims(jwt).getClaim("scope");
            return strings(scope).stream()
                    .flatMap(value -> List.of(value.split(" ")).stream())
                    .filter(value -> !value.isBlank())
                    .toList();
        } catch (Exception ex) {
            throw new IllegalStateException("无法解析 JWT scope", ex);
        }
    }

    private static JWTClaimsSet claims(String jwt) throws Exception {
        return SignedJWT.parse(jwt).getJWTClaimsSet();
    }

    private static long epochSeconds(Date date) {
        return date.toInstant().getEpochSecond();
    }

    private static String jwks(IamTestSession session) {
        return session.get("/oauth2/jwks", MediaType.APPLICATION_JSON).body();
    }

    private static URI assertHttpsUri(Object value, String fieldName) {
        assertThat(value).as(fieldName).isInstanceOf(String.class);
        URI uri = URI.create((String) value);
        assertThat(uri.isAbsolute()).as(fieldName + " must be absolute").isTrue();
        assertThat(uri.getScheme())
                .as(fieldName + " must use HTTPS")
                .isEqualToIgnoringCase("https");
        assertThat(uri.getHost()).as(fieldName + " must have a host").isNotBlank();
        assertThat(uri.getFragment()).as(fieldName + " must not contain a fragment").isNull();
        return uri;
    }

    private static void assertNoStore(HttpHeaders headers) {
        assertThat(headers.getFirst(HttpHeaders.CACHE_CONTROL))
                .as("RFC 6749 §5.1 Cache-Control must contain no-store")
                .contains("no-store");
        assertThat(headers.getFirst(HttpHeaders.PRAGMA))
                .as("RFC 6749 §5.1 Pragma must be no-cache")
                .isEqualTo("no-cache");
    }

    private static void assertOAuthErrorRedirect(HttpResult result, String error, String state) {
        assertThat(result.status()).isEqualTo(302);
        assertThat(result.location()).isNotNull();
        assertThat(result.location()).startsWith(IamTestSession.REDIRECT_URI);
        assertThat(IamTestSession.queryParam(result.location(), "error")).isEqualTo(error);
        assertThat(IamTestSession.queryParam(result.location(), "state")).isEqualTo(state);
        assertThat(IamTestSession.queryParam(result.location(), "code")).isNull();
    }

    private static List<String[]> withParam(List<String[]> params, String name, String value) {
        List<String[]> copy = new ArrayList<>(params);
        copy.removeIf(param -> name.equals(param[0]));
        copy.add(new String[] {name, value});
        return copy;
    }

    private static List<String[]> withoutParam(List<String[]> params, String name) {
        List<String[]> copy = new ArrayList<>(params);
        copy.removeIf(param -> name.equals(param[0]));
        return copy;
    }
}
