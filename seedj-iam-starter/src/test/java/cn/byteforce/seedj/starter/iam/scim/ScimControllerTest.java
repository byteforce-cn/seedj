package cn.byteforce.seedj.starter.iam.scim;

import cn.byteforce.seedj.starter.iam.annotation.EnableIamServer;
import cn.byteforce.seedj.starter.iam.autoconfigure.IamAutoConfiguration;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.starter.iam.TestIdGenerators;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.context.annotation.Bean;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ScimControllerTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
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
                    "seedj.iam.secret.key=" + java.util.Base64.getEncoder().encodeToString(new byte[32]),
                    "seedj.pki.enabled=false",
                    "seedj.transit.enabled=false",
                    "seedj.eventbus.enabled=false",
                    "seedj.id.enabled=false",
                    "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration");

    @Test
    void servesScimDiscoveryUserCrudAndPatch() throws Exception {
        runner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(
                    (WebApplicationContext) context.getSourceApplicationContext()).build();

            mvc.perform(get("/scim/v2/ServiceProviderConfig"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(ScimSupport.CONTENT_TYPE))
                    .andExpect(jsonPath("$.patch.supported").value(true));

            String created = mvc.perform(post("/scim/v2/Users")
                            .contentType(ScimSupport.CONTENT_TYPE)
                            .content("""
                                    {"userName":"alice","name":{"givenName":"Alice"},
                                     "emails":[{"value":"alice@example.com"}],
                                     "password":"secret-password"}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.userName").value("alice"))
                    .andReturn().getResponse().getContentAsString();

            String id = com.jayway.jsonpath.JsonPath.read(created, "$.id");
            String etag = com.jayway.jsonpath.JsonPath.read(created, "$.meta.version");

            mvc.perform(get("/scim/v2/Users/" + id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.displayName").value("Alice"));

            mvc.perform(patch("/scim/v2/Users/" + id)
                            .contentType(ScimSupport.CONTENT_TYPE)
                            .header("If-Match", etag)
                            .content("""
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
}
