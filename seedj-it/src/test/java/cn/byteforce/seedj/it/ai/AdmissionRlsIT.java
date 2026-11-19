package cn.byteforce.seedj.it.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import cn.byteforce.seedj.infra.jdbc.ai.AiSchemaCapability;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * RLS 兜底：没有合法 admission + GUC 的直连写入必须被数据库拒绝。
 *
 * <p>角色是测试内建的：{@code app_rw} 业务读写（受 RLS 约束），{@code guard_rw} 只写 admission。表 owner 是容器主用户，
 * {@code app_rw} 不是 owner，{@code FORCE ROW LEVEL SECURITY} 才能对 owner 之外的普通角色生效。
 */
@Testcontainers
class AdmissionRlsIT {

    private static final String DECISION_ID = "018f2e3a-1234-7000-8000-00000000a001";
    private static final String TENANT = "018f2e3a-0000-7000-8000-0000000b0007";
    private static final String ACTOR = "018f2e3a-0000-7000-8000-0000000a9e01";
    private static final String COMMAND = "ticket.refund";
    private static final String ARGS_HASH = "sha256:abc";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("seedj")
                    .withUsername("seedj")
                    .withPassword("seedj");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void initialize() {
        DataSource dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        new AiSchemaCapability(new JdbcMigrationRunner(dataSource)).initialize();
        jdbc.execute("create role app_rw login password 'app_rw'");
        jdbc.execute("create role guard_rw login password 'guard_rw'");
        jdbc.execute("grant select on agent_decision_admission to app_rw");
        jdbc.execute("grant insert, select, delete on agent_decision_admission to guard_rw");
        jdbc.execute("grant execute on function agent_admission_ok(text, text) to app_rw");
        jdbc.execute(
                """
                create table agent_irreversible_probe (
                  id uuid primary key,
                  command_type text not null,
                  args_hash text not null,
                  amount bigint not null)
                """);
        jdbc.execute("alter table agent_irreversible_probe enable row level security");
        jdbc.execute("alter table agent_irreversible_probe force row level security");
        jdbc.execute(
                """
                create policy agent_irreversible_probe_policy on agent_irreversible_probe
                  for all
                  using (agent_admission_ok(command_type, args_hash))
                  with check (agent_admission_ok(command_type, args_hash))
                """);
        jdbc.execute("grant select, insert, update, delete on agent_irreversible_probe to app_rw");
    }

    @BeforeEach
    void clean() {
        // owner 默认绕过 RLS；FORCE 后连 owner 也受策略约束，清理时先临时关掉。
        jdbc.execute("alter table agent_irreversible_probe no force row level security");
        jdbc.execute("delete from agent_irreversible_probe");
        jdbc.execute("alter table agent_irreversible_probe force row level security");
        jdbc.execute("delete from agent_decision_admission");
    }

    @Test
    @DisplayName("没有 admission/GUC：app_rw 直连写入被 RLS 拒绝")
    void directInsertWithoutAdmissionIsRejected() {
        assertThatThrownBy(() -> insertAsApp("sha256:abc", Instant.now().plusSeconds(60)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("row-level security");
    }

    @Test
    @DisplayName("有合法 admission + GUC：app_rw 写入成功")
    void admissionAndGucAllowInsert() throws Exception {
        admit(Instant.now().plusSeconds(60));

        assertThat(insertAsApp("sha256:abc", Instant.now().plusSeconds(60))).isEqualTo(1);
    }

    @Test
    @DisplayName("过期 admission：仍然被拒")
    void expiredAdmissionIsRejected() throws Exception {
        admit(Instant.now().minusSeconds(1));

        assertThatThrownBy(() -> insertAsApp("sha256:abc", Instant.now().plusSeconds(60)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("row-level security");
    }

    @Test
    @DisplayName("args_hash 不匹配：被拒")
    void mismatchedArgsHashIsRejected() throws Exception {
        admit(Instant.now().plusSeconds(60));

        assertThatThrownBy(() -> insertAsApp("sha256:other", Instant.now().plusSeconds(60)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("row-level security");
    }

    private static void admit(Instant expiresAt) throws Exception {
        try (Connection guard = connect("guard_rw");
                PreparedStatement statement =
                        guard.prepareStatement(
                                """
                                insert into agent_decision_admission
                                  (decision_id, tenant_id, command_type, targets, args_hash,
                                   actor, expires_at, admitted_at)
                                values (?, ?, ?, '[]'::jsonb, ?, ?, ?, ?)
                                """)) {
            statement.setObject(1, UUID.fromString(DECISION_ID));
            statement.setObject(2, UUID.fromString(TENANT));
            statement.setString(3, COMMAND);
            statement.setString(4, ARGS_HASH);
            statement.setObject(5, UUID.fromString(ACTOR));
            statement.setTimestamp(6, Timestamp.from(expiresAt));
            statement.setTimestamp(7, Timestamp.from(Instant.now().minusSeconds(5)));
            statement.executeUpdate();
        }
    }

    private static int insertAsApp(String argsHash, Instant now) throws Exception {
        try (Connection app = connect("app_rw")) {
            try (PreparedStatement guc =
                    app.prepareStatement(
                            """
                            select set_config('agent.decision_id', ?, false),
                                   set_config('agent.command_type', ?, false),
                                   set_config('agent.args_hash', ?, false)
                            """)) {
                guc.setString(1, DECISION_ID);
                guc.setString(2, COMMAND);
                guc.setString(3, argsHash);
                guc.execute();
            }
            try (PreparedStatement insert =
                    app.prepareStatement(
                            """
                            insert into agent_irreversible_probe
                              (id, command_type, args_hash, amount)
                            values (?, ?, ?, ?)
                            """)) {
                insert.setObject(1, UUID.randomUUID());
                insert.setString(2, COMMAND);
                insert.setString(3, argsHash);
                insert.setLong(4, 12800);
                return insert.executeUpdate();
            }
        }
    }

    private static Connection connect(String role) throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), role, role);
    }
}
