package cn.byteforce.seedj.starter.web;

import cn.byteforce.seedj.core.context.RequestContext;
import cn.byteforce.seedj.web.core.RequestContextContributor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 请求上下文过滤器契约测试：traceId 解析/回写、身份贡献器调度与默认空身份。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class RequestContextFiltersTest {

    /**
     * 匿名场景：仅 traceId 可用，身份字段为空。
     */
    @SpringBootTest(classes = WebTestApplication.class)
    @AutoConfigureMockMvc
    static class AnonymousContextTest {

        @Autowired
        private MockMvc mockMvc;

        @Test
        void anonymousRequestHasTraceIdOnly() throws Exception {
            mockMvc.perform(get("/ctx").header("X-Trace-Id", "anon-trace"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.traceId").value("anon-trace"))
                    .andExpect(jsonPath("$.userId").value(nullValue()))
                    .andExpect(jsonPath("$.tenantId").value(nullValue()))
                    .andExpect(jsonPath("$.username").value(nullValue()));
        }

        @Test
        void generatedTraceIdWhenHeaderMissing() throws Exception {
            mockMvc.perform(get("/ctx"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.traceId").isNotEmpty());
        }
    }

    /**
     * 身份贡献器场景：贡献器写入的用户 / 租户 / 用户名在控制器可见。
     */
    @SpringBootTest(classes = WebTestApplication.class)
    @AutoConfigureMockMvc
    @Import(FixedContributorTest.ContributorConfiguration.class)
    static class FixedContributorTest {

        @TestConfiguration
        static class ContributorConfiguration {

            @Bean
            RequestContextContributor fixedContributor() {
                return () -> RequestContext.setIdentity(42L, 7L, "alice");
            }
        }

        @Autowired
        private MockMvc mockMvc;

        @Test
        void contributorPopulatesIdentityIntoContext() throws Exception {
            mockMvc.perform(get("/ctx").header("X-Trace-Id", "id-trace"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.traceId").value("id-trace"))
                    // Long 全局字符串化（starter-core 契约）→ 身份 ID 输出为字符串
                    .andExpect(jsonPath("$.userId").value("42"))
                    .andExpect(jsonPath("$.tenantId").value("7"))
                    .andExpect(jsonPath("$.username").value("alice"));
        }
    }
}
