package cn.byteforce.seedj.starter.iam.scim;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.byteforce.seedj.iam.spi.IamPasswordHasher;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamAuditSink;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamAuthorizationRepository;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamGroupRepository;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamTokenRevoker;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamUserRepository;
import cn.byteforce.seedj.starter.iam.IamProperties;
import cn.byteforce.seedj.starter.iam.TestIdGenerators;
import cn.byteforce.seedj.starter.iam.annotation.EnableIamServer;
import cn.byteforce.seedj.starter.iam.autoconfigure.IamAutoConfiguration;
import cn.byteforce.seedj.starter.iam.identity.IamIdentityService;
import cn.byteforce.seedj.starter.iam.identity.IamPasswordHasherAdapter;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class ScimControllerTest {

    private final WebApplicationContextRunner runner =
            new WebApplicationContextRunner()
                    .withUserConfiguration(TestApplication.class)
                    .withConfiguration(AutoConfigurations.of(IamAutoConfiguration.class))
                    .withPropertyValues(
                            "seedj.iam.issuer=https://iam.test",
                            "seedj.iam.store=memory",
                            "seedj.iam.demo.enabled=false",
                            "seedj.iam.native-auth.enabled=false",
                            "seedj.iam.mfa.totp.enabled=false",
                            "seedj.iam.rbac.enabled=false",
                            "seedj.iam.abac.enabled=false",
                            "seedj.iam.secret.key="
                                    + java.util.Base64.getEncoder().encodeToString(new byte[32]),
                            "seedj.pki.enabled=false",
                            "seedj.transit.enabled=false",
                            "seedj.eventbus.enabled=false",
                            "seedj.id.enabled=false",
                            "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration");

    @Test
    void servesScimDiscoveryUserCrudAndPatch() throws Exception {
        runner.run(
                context -> {
                    MockMvc mvc =
                            MockMvcBuilders.webAppContextSetup(
                                            (WebApplicationContext)
                                                    context.getSourceApplicationContext())
                                    .build();

                    mvc.perform(get("/scim/v2/ServiceProviderConfig"))
                            .andExpect(status().isOk())
                            .andExpect(
                                    content().contentTypeCompatibleWith(ScimSupport.CONTENT_TYPE))
                            .andExpect(jsonPath("$.patch.supported").value(true));

                    String created =
                            mvc.perform(
                                            post("/scim/v2/Users")
                                                    .contentType(ScimSupport.CONTENT_TYPE)
                                                    .content(
                                                            """
                                    {"userName":"alice","name":{"givenName":"Alice"},
                                     "emails":[{"value":"alice@example.com"}],
                                     "password":"secret-password"}
                                    """))
                                    .andExpect(status().isCreated())
                                    .andExpect(jsonPath("$.userName").value("alice"))
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsString();

                    String id = com.jayway.jsonpath.JsonPath.read(created, "$.id");
                    String etag = com.jayway.jsonpath.JsonPath.read(created, "$.meta.version");

                    mvc.perform(get("/scim/v2/Users/" + id))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.displayName").value("Alice"));

                    mvc.perform(
                                    patch("/scim/v2/Users/" + id)
                                            .contentType(ScimSupport.CONTENT_TYPE)
                                            .header("If-Match", etag)
                                            .content(
                                                    """
                                    {"Operations":[{"op":"replace","path":"active","value":false}]}
                                    """))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.active").value(false));
                });
    }

    @EnableIamServer
    @EnableWebMvc
    static class TestApplication {

        @Bean
        IdGenerator<String> idGenerator() {
            return TestIdGenerators.sequential();
        }
    }

    // ---------------------------------------------------------------------
    // Standalone controller coverage: every endpoint plus the SCIM error map.
    // ---------------------------------------------------------------------

    private final InMemoryIamUserRepository standaloneUsers = new InMemoryIamUserRepository();
    private final InMemoryIamGroupRepository standaloneGroups = new InMemoryIamGroupRepository();
    private final IamPasswordHasher standaloneHasher =
            new IamPasswordHasherAdapter(
                    PasswordEncoderFactories.createDelegatingPasswordEncoder());
    private final AtomicLong standaloneSeq = new AtomicLong();
    private final IdGenerator<String> standaloneIds = () -> "s-" + standaloneSeq.incrementAndGet();
    private final IamIdentityService standaloneIdentities =
            new IamIdentityService(
                    standaloneUsers,
                    standaloneHasher,
                    new InMemoryIamTokenRevoker(),
                    new InMemoryIamAuditSink(),
                    standaloneIds);

    private MockMvc standaloneMvc() {
        IamProperties properties = IamProperties.defaults();
        return MockMvcBuilders.standaloneSetup(
                        new ScimController(
                                new ScimUserService(
                                        standaloneUsers,
                                        standaloneGroups,
                                        standaloneHasher,
                                        standaloneIdentities,
                                        properties,
                                        standaloneIds),
                                new ScimGroupService(
                                        standaloneGroups,
                                        standaloneUsers,
                                        properties,
                                        new InMemoryIamAuthorizationRepository(),
                                        standaloneIds),
                                properties))
                .setControllerAdvice(new ScimExceptionHandler())
                .build();
    }

    @Test
    void servesDiscoveryDocuments() throws Exception {
        MockMvc mvc = standaloneMvc();

        mvc.perform(get("/scim/v2/ServiceProviderConfig"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patch.supported").value(true))
                .andExpect(jsonPath("$.bulk.supported").value(false))
                .andExpect(jsonPath("$.filter.maxResults").value(1000))
                .andExpect(jsonPath("$.changePassword.supported").value(true))
                .andExpect(jsonPath("$.etag.supported").value(true))
                .andExpect(jsonPath("$.authenticationSchemes[0].type").value("oauthbearertoken"));

        mvc.perform(get("/scim/v2/Schemas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalResults").value(2))
                .andExpect(jsonPath("$.Resources[0].id").value(ScimSupport.USER_SCHEMA))
                .andExpect(jsonPath("$.Resources[0].attributes[0].name").value("userName"))
                .andExpect(jsonPath("$.Resources[0].attributes[0].required").value(true))
                .andExpect(jsonPath("$.Resources[1].id").value(ScimSupport.GROUP_SCHEMA));

        mvc.perform(get("/scim/v2/ResourceTypes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalResults").value(2))
                .andExpect(jsonPath("$.Resources[0].endpoint").value("/Users"))
                .andExpect(jsonPath("$.Resources[1].schema").value(ScimSupport.GROUP_SCHEMA));
    }

    @Test
    void servesUserCrudLifecycle() throws Exception {
        MockMvc mvc = standaloneMvc();

        String created =
                mvc.perform(
                                post("/scim/v2/Users")
                                        .contentType(ScimSupport.CONTENT_TYPE)
                                        .content(
                                                """
                                {"userName":"alice","displayName":"Alice",
                                 "emails":[{"value":"alice@example.com"}],
                                 "password":"alice-password"}
                                """))
                        .andExpect(status().isCreated())
                        .andExpect(header().string(HttpHeaders.ETAG, "W/\"0\""))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(created, "$.id");
        mvc.perform(
                        post("/scim/v2/Users")
                                .contentType(ScimSupport.CONTENT_TYPE)
                                .content("{\"userName\":\"bob\"}"))
                .andExpect(status().isCreated())
                .andExpect(
                        header().string(
                                        HttpHeaders.LOCATION,
                                        org.hamcrest.Matchers.startsWith("/scim/v2/Users/")));

        mvc.perform(
                        get("/scim/v2/Users")
                                .param("filter", "userName eq \"alice\"")
                                .param("startIndex", "1")
                                .param("count", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalResults").value(1))
                .andExpect(jsonPath("$.Resources[0].userName").value("alice"));

        mvc.perform(get("/scim/v2/Users/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emails[0].value").value("alice@example.com"));

        mvc.perform(
                        put("/scim/v2/Users/" + id)
                                .contentType(ScimSupport.CONTENT_TYPE)
                                .header(HttpHeaders.IF_MATCH, "W/\"0\"")
                                .content("{\"userName\":\"alice\",\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "W/\"1\""))
                .andExpect(jsonPath("$.active").value(false));

        mvc.perform(
                        patch("/scim/v2/Users/" + id)
                                .contentType(ScimSupport.CONTENT_TYPE)
                                .header(HttpHeaders.IF_MATCH, "W/\"1\"")
                                .content(
                                        """
                                {"Operations":[{"op":"replace","path":"displayName",
                                                 "value":"Alice Ng"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Alice Ng"));

        mvc.perform(delete("/scim/v2/Users/" + id).header(HttpHeaders.IF_MATCH, "W/\"2\""))
                .andExpect(status().isNoContent());

        mvc.perform(get("/scim/v2/Users/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("404"))
                .andExpect(jsonPath("$.detail").value("user not found: " + id))
                .andExpect(jsonPath("$.scimType").doesNotExist());
    }

    @Test
    void servesGroupCrudLifecycle() throws Exception {
        MockMvc mvc = standaloneMvc();
        String userId =
                standaloneUsers
                        .create(
                                cn.byteforce.seedj.iam.IamUser.create(
                                        "u-1", "default", "alice", "Alice", null, true),
                                standaloneHasher.encode("alice-password"))
                        .id();

        String created =
                mvc.perform(
                                post("/scim/v2/Groups")
                                        .contentType(ScimSupport.CONTENT_TYPE)
                                        .content(
                                                "{\"displayName\":\"sales\",\"members\":[{\"value\":\""
                                                        + userId
                                                        + "\"}]}"))
                        .andExpect(status().isCreated())
                        .andExpect(
                                header().string(
                                                HttpHeaders.LOCATION,
                                                org.hamcrest.Matchers.startsWith(
                                                        "/scim/v2/Groups/")))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String groupId = com.jayway.jsonpath.JsonPath.read(created, "$.id");

        mvc.perform(get("/scim/v2/Groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalResults").value(1))
                .andExpect(jsonPath("$.Resources[0].displayName").value("sales"));

        mvc.perform(get("/scim/v2/Groups/" + groupId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[0].display").value("alice"));

        mvc.perform(
                        put("/scim/v2/Groups/" + groupId)
                                .contentType(ScimSupport.CONTENT_TYPE)
                                .header(HttpHeaders.IF_MATCH, "W/\"0\"")
                                .content("{\"displayName\":\"sales-eu\",\"members\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("sales-eu"))
                .andExpect(jsonPath("$.members").doesNotExist());

        mvc.perform(
                        patch("/scim/v2/Groups/" + groupId)
                                .contentType(ScimSupport.CONTENT_TYPE)
                                .header(HttpHeaders.IF_MATCH, "W/\"1\"")
                                .content(
                                        """
                                {"Operations":[{"op":"add","path":"members",
                                                 "value":[{"value":"%s"}]}]}
                                """
                                                .formatted(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[0].value").value(userId));

        mvc.perform(delete("/scim/v2/Groups/" + groupId).header(HttpHeaders.IF_MATCH, "W/\"2\""))
                .andExpect(status().isNoContent());

        mvc.perform(get("/scim/v2/Groups/" + groupId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("404"))
                .andExpect(jsonPath("$.detail").value("group not found: " + groupId));
    }

    @Test
    void returnsScimErrorEnvelopesForBadRequests() throws Exception {
        MockMvc mvc = standaloneMvc();

        mvc.perform(
                        post("/scim/v2/Users")
                                .contentType(ScimSupport.CONTENT_TYPE)
                                .content("{\"displayName\":\"Alice\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(ScimSupport.CONTENT_TYPE))
                .andExpect(jsonPath("$.scimType").value("invalidValue"))
                .andExpect(jsonPath("$.status").value("400"));

        mvc.perform(post("/scim/v2/Groups").contentType(ScimSupport.CONTENT_TYPE).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("displayName is required"));

        String created =
                mvc.perform(
                                post("/scim/v2/Users")
                                        .contentType(ScimSupport.CONTENT_TYPE)
                                        .content("{\"userName\":\"alice\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(created, "$.id");

        mvc.perform(
                        put("/scim/v2/Users/" + id)
                                .contentType(ScimSupport.CONTENT_TYPE)
                                .header(HttpHeaders.IF_MATCH, "W/\"9\"")
                                .content("{\"userName\":\"alice\"}"))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.scimType").value("versionMismatch"));

        mvc.perform(
                        post("/scim/v2/Users")
                                .contentType(ScimSupport.CONTENT_TYPE)
                                .content("{\"userName\":\"alice\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.scimType").value("uniqueness"));
    }
}
