package cn.byteforce.seedj.iam.jdbc;

import cn.byteforce.seedj.iam.authz.IamBinding;
import cn.byteforce.seedj.iam.authz.IamProxy;
import cn.byteforce.seedj.jdbc.JdbcMigrationRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL 绑定/代理存储的时点契约。
 */
@Testcontainers
class JdbcIamBindingStoreTest {

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("seedj")
                    .withUsername("seedj")
                    .withPassword("seedj");

    private static JdbcTemplate jdbc;

    private static JdbcIamBindingStore store;

    @BeforeAll
    static void initialize() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        new IamSchemaCapability(new JdbcMigrationRunner(dataSource)).initialize();
        store = new JdbcIamBindingStore(jdbc);
    }

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE iam_binding, iam_proxy");
    }

    @Test
    @DisplayName("保存并按业务时点回读绑定")
    void savesAndReadsBindingAtAsOf() {
        store.save(binding("b-1", T0, T0.plusSeconds(3600)));

        assertThat(store.effectiveBindings("p", T0, null)).hasSize(1);
        assertThat(store.effectiveBindings("p", T0, null).get(0).scope())
                .containsEntry("department", "D001");
    }

    @Test
    @DisplayName("valid_to 不含")
    void validToIsExclusive() {
        store.save(binding("b-2", T0, T0.plusSeconds(3600)));

        assertThat(store.effectiveBindings("p", T0.plusSeconds(3600), null)).isEmpty();
    }

    @Test
    @DisplayName("recorded_as_of 隐藏已封口版本")
    void recordedAsOfHidesSupersededBinding() {
        store.save(new IamBinding("b-3", "p", "r1", Map.of("department", "D001"),
                T0, null, T0, T0.plusSeconds(600)));

        assertThat(store.effectiveBindings("p", T0.plusSeconds(120), T0.plusSeconds(300)))
                .hasSize(1);
        assertThat(store.effectiveBindings("p", T0.plusSeconds(120), T0.plusSeconds(600)))
                .isEmpty();
    }

    @Test
    @DisplayName("代理窗口按业务时点过滤")
    void proxyWindowIsRespected() {
        store.save(new IamProxy("x-1", "away", "agent", Map.of("department", "D001"),
                T0, T0.plusSeconds(86400), T0, null));

        assertThat(store.effectiveProxies("agent", T0.plusSeconds(60), null)).hasSize(1);
        assertThat(store.effectiveProxies("agent", T0.plusSeconds(86400), null)).isEmpty();
    }

    private static IamBinding binding(String id, Instant from, Instant to) {
        return new IamBinding(id, "p", "r1", Map.of("department", "D001"), from, to, T0, null);
    }
}
