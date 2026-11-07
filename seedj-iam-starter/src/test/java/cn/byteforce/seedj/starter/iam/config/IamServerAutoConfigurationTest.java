package cn.byteforce.seedj.starter.iam.config;

import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.starter.iam.TestIdGenerators;
import cn.byteforce.seedj.starter.iam.annotation.EnableIamServer;
import cn.byteforce.seedj.starter.iam.autoconfigure.IamAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IamServerAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(TestApplication.class)
            .withConfiguration(AutoConfigurations.of(IamAutoConfiguration.class))
            .withPropertyValues(
                    "seedj.iam.issuer=http://localhost",
                    "seedj.iam.store=memory",
                    "seedj.iam.demo.enabled=true",
                    "seedj.iam.native-auth.enabled=true",
                    "seedj.iam.mfa.totp.enabled=false",
                    "seedj.iam.rbac.enabled=false",
                    "seedj.iam.abac.enabled=false",
                    "seedj.iam.secret.key=" + Base64.getEncoder().encodeToString(new byte[32]),
                    "seedj.pki.enabled=false",
                    "seedj.transit.enabled=false",
                    "seedj.eventbus.enabled=false",
                    "seedj.id.enabled=false",
                    "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration");

    @Test
    void wiresProtocolCollaborators() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(IamIssuerResolver.class);
            assertThat(context.getBean(IamIssuerResolver.class).issuer())
                    .isEqualTo("http://localhost");
            assertThat(context).hasSingleBean(org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings.class);
            assertThat(context).hasSingleBean(org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository.class);
            assertThat(context).hasSingleBean(org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService.class);
            assertThat(context).hasSingleBean(org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService.class);
            assertThat(context).hasSingleBean(org.springframework.security.oauth2.jwt.JwtDecoder.class);
            assertThat(context).hasSingleBean(org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator.class);
            assertThat(context).hasSingleBean(com.nimbusds.jose.jwk.source.JWKSource.class);
            assertThat(context).hasSingleBean(
                    cn.byteforce.seedj.iam.spi.IamSigningKeyStore.class);
        });
    }

    @Test
    void publishesOidcAndAuthorizationServerMetadata() {
        runner.run(context -> {
            MockMvc mvc = mvc(context);

            mvc.perform(get("/.well-known/openid-configuration"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.issuer").value("http://localhost"))
                    .andExpect(jsonPath("$.scopes_supported").value(
                            org.hamcrest.Matchers.hasItem("scim.users.read")))
                    .andExpect(jsonPath("$.claims_supported").value(
                            org.hamcrest.Matchers.hasItem("tenant_id")))
                    .andExpect(jsonPath("$.grant_types_supported").value(
                            org.hamcrest.Matchers.hasItem(
                                    "urn:seedj:params:oauth:grant-type:native-auth")))
                    .andExpect(jsonPath("$.userinfo_endpoint")
                            .value("http://localhost/userinfo"));

            mvc.perform(get("/.well-known/oauth-authorization-server"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.response_modes_supported").value(
                            org.hamcrest.Matchers.contains("query")))
                    .andExpect(jsonPath("$.introspection_endpoint_auth_signing_alg_values_supported")
                            .value(org.hamcrest.Matchers.hasItem("RS256")));
        });
    }

    @Test
    void publishesPublicJwksFromTheSigningKeyStore() {
        runner.run(context -> {
            mvc(context).perform(get("/oauth2/jwks"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                    .andExpect(jsonPath("$.keys[0].kid").isNotEmpty())
                    .andExpect(jsonPath("$.keys[0].d").doesNotExist());
        });
    }

    @Test
    void respondsWithBasicChallengeForInvalidClientAuthentication() {
        runner.run(context -> {
            String basic = "Basic " + Base64.getEncoder()
                    .encodeToString("unknown-client:wrong".getBytes());

            mvc(context).perform(post("/oauth2/token")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .header(HttpHeaders.AUTHORIZATION, basic)
                            .param("grant_type", "client_credentials"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                            containsString("Basic realm=\"oauth2/client\"")))
                    .andExpect(jsonPath("$.error").value("invalid_client"));
        });
    }

    @Test
    void requiresBearerTokenForUserInfoAndIamEndpoints() {
        runner.run(context -> {
            MockMvc mvc = mvc(context);

            mvc.perform(get("/userinfo").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("Bearer")));

            // No bearer header: the /iam chain keeps CSRF protection in place.
            mvc.perform(post("/iam/admin/jwk/rotate"))
                    .andExpect(status().isForbidden());

            // A bearer header disables CSRF for /iam/**, so the request reaches JWT validation.
            mvc.perform(post("/iam/admin/jwk/rotate")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                    .andExpect(status().isUnauthorized());
        });
    }

    /** MockMvc with the Spring Security filter chain, which the runner does not register. */
    private static MockMvc mvc(org.springframework.context.ApplicationContext context) {
        jakarta.servlet.Filter securityFilterChain =
                (jakarta.servlet.Filter) context.getBean("springSecurityFilterChain");
        return MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                .addFilters(securityFilterChain)
                .build();
    }

    @EnableIamServer
    @EnableWebMvc
    static class TestApplication {

        @Bean
        IdGenerator<String> idGenerator() {
            return TestIdGenerators.sequential();
        }
    }
}
