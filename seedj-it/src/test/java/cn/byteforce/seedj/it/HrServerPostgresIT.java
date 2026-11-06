package cn.byteforce.seedj.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.AppendResult;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.TemporalQuery;
import cn.byteforce.seedj.domain.TemporalStore;
import cn.byteforce.seedj.server.hr.HrServer;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** HR 的 PostgreSQL 模式：迁移、schema 对齐、HTTP 黄金路径与并发创建。 */
@Testcontainers
@SpringBootTest(classes = HrServer.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles({"postgres", "test"})
@Import(HrServerPostgresIT.JwtConfig.class)
class HrServerPostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("seedj")
                    .withUsername("seedj")
                    .withPassword("seedj");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("seedj.biz.dev-auth.enabled", () -> "true");
        registry.add("seedj.security.derived-field-keys", () -> "test-key");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class JwtConfig {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new JwtException("postgres IT does not accept JWTs; dev auth is on");
            };
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectSchema schema;

    @Autowired
    private TemporalStore store;

    @Test
    @DisplayName("迁移历史包含 hr-employee scope")
    void migrationHistoryHasHrScope() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM seedj_schema_history WHERE scope = 'hr-employee'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("information_schema 列与 ObjectSchema 完全一致")
    void informationSchemaMatchesObjectSchema() {
        List<String> columns = jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns"
                                + " WHERE table_name = 'hr_employee' ORDER BY column_name",
                        String.class).stream()
                .filter(column -> !ObjectSchema.META_FIELDS.contains(column))
                .toList();
        List<String> expected = schema.fields().stream().map(field -> field.name()).sorted()
                .toList();

        assertThat(columns).containsExactlyElementsOf(expected);
        assertThat(columns).containsAll(List.of("employee_no", "name", "status", "hire_date",
                "employment_type", "work_location", "id_number_hash", "org_unit_id"));
    }

    @Test
    @DisplayName("create 202 / get 200 / as_of 读回 v1")
    void createGetAndAsOf() throws Exception {
        String created = mvc.perform(post("/commands/hr.employee.create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"hr.employee.create",
                                 "payload":{"name":"张三","hire_date":"2026-10-01",
                                            "id_number":"110101199001011234"},
                                 "idempotency_key":"pg-1"}
                                """))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.locator");

        mvc.perform(get("/objects/employee/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.employee_no").isNotEmpty())
                .andExpect(jsonPath("$.data.id_number_hash").doesNotExist());

        mvc.perform(post("/commands/hr.employee.update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"hr.employee.update",
                                 "payload":{"employee_id":"%s","name":"张三丰"},
                                 "idempotency_key":"pg-2"}
                                """.formatted(id)))
                .andExpect(status().isAccepted());

        mvc.perform(get("/objects/employee/" + id)
                        .queryParam("as_of", "2026-10-01T12:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("张三"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    @DisplayName("并发创建同一 ID：一个成功，一个因唯一约束失败（不是静默覆盖）")
    void concurrentCreateSameIdHasOneWinner() throws Exception {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-08T09:00:00Z");
        Map<String, Object> fields = Map.of("employee_no", "EMP-CONCURRENT",
                "name", "并发", "status", "pending_onboarding");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var task = (java.util.concurrent.Callable<Boolean>) () -> {
                start.await(5, TimeUnit.SECONDS);
                try {
                    AppendResult result = store.append(AppendRequest.create("employee", id, now,
                            null, UUID.randomUUID(), fields));
                    return result.appended().version().version() == 1;
                } catch (RuntimeException expected) {
                    return false;
                }
            };
            Future<Boolean> first = pool.submit(task);
            Future<Boolean> second = pool.submit(task);
            start.countDown();

            long winners = (first.get(30, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(30, TimeUnit.SECONDS) ? 1 : 0);
            assertThat(winners).isEqualTo(1);
            assertThat(store.find("employee", id, TemporalQuery.current(),
                        cn.byteforce.seedj.domain.ScopePredicate.all())).isPresent();
        } finally {
            pool.shutdownNow();
        }
    }
}
