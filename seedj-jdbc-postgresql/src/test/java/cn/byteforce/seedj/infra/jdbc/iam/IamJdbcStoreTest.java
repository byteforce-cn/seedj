package cn.byteforce.seedj.infra.jdbc.iam;

import cn.byteforce.seedj.iam.IamGroup;
import cn.byteforce.seedj.iam.IamPermission;
import cn.byteforce.seedj.iam.IamPolicy;
import cn.byteforce.seedj.iam.IamPolicyEffect;
import cn.byteforce.seedj.iam.IamRole;
import cn.byteforce.seedj.iam.IamTotpState;
import cn.byteforce.seedj.iam.IamUser;
import cn.byteforce.seedj.iam.IamUserQuery;
import cn.byteforce.seedj.iam.PolicyCondition;
import cn.byteforce.seedj.iam.PolicyComparisonOperator;
import cn.byteforce.seedj.iam.PolicyOperand;
import cn.byteforce.seedj.iam.scim.ScimFilterParser;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class IamJdbcStoreTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("iam").withUsername("iam").withPassword("iam");

    static DataSource dataSource;
    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.postgresql.Driver");
        source.setUrl(POSTGRES.getJdbcUrl());
        source.setUsername(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        dataSource = source;
        jdbc = new JdbcTemplate(source);
        JdbcMigrationRunner runner = new JdbcMigrationRunner(source);
        new IamSchemaCapability(runner).initialize();
        new OAuth2SchemaCapability(runner).initialize();
    }

    @Test
    void userAndGroupStoreRoundTripAndFilter() {
        JdbcIamUserRepository users = new JdbcIamUserRepository(jdbc);
        IamUser alice = users.create(IamUser.create("u-alice", "default", "alice", "Alice",
                "alice@example.com", true), "{bcrypt}hash");
        assertThat(users.findByUserName("default", "ALICE")).isPresent();
        assertThat(users.findPasswordHash(alice.id())).contains("{bcrypt}hash");
        assertThat(users.find(new IamUserQuery("default",
                ScimFilterParser.parse("userName eq \"alice\""), 1, 10)).items())
                .extracting(IamUser::id).containsExactly(alice.id());

        IamGroup group = new JdbcIamGroupRepository(jdbc, dataSource).create(
                IamGroup.create("g-sales", "default", "sales", null, Set.of(alice.id())));
        JdbcIamGroupRepository groups = new JdbcIamGroupRepository(jdbc, dataSource);
        assertThat(groups.findById(group.id()).orElseThrow().memberIds())
                .containsExactly(alice.id());
        assertThat(groups.findGroupIdsForUser(alice.id())).containsExactly(group.id());
    }

    @Test
    void rbacResolvesInheritanceAndEpoch() {
        new JdbcIamUserRepository(jdbc).create(IamUser.create("u-role", "default", "role-user", "Role User",
                null, true), "{bcrypt}hash");
        JdbcIamAuthorizationRepository repository = new JdbcIamAuthorizationRepository(jdbc);
        IamRole base = repository.createRole(IamRole.create("r-user", "default", "user", "User", "", null, false));
        IamRole manager = repository.createRole(IamRole.create("r-manager", "default", "manager", "Manager",
                "", base.id(), false));
        repository.savePermission(IamPermission.of("default", "order", "read", ""));
        repository.grantPermission(manager.id(), "order:read");
        repository.assignRoleToUser(new JdbcIamUserRepository(jdbc)
                .findByUserName("default", "role-user").orElseThrow().id(),
                manager.id(), Instant.now(), "test");

        assertThat(repository.findRoleIdsWithAncestors(Set.of(manager.id())))
                .containsExactlyInAnyOrder(manager.id(), base.id());
        assertThat(repository.findPermissionCodes(Set.of(manager.id(), base.id())))
                .containsExactly("order:read");
        long epoch = repository.currentEpoch("default");
        repository.bumpEpoch("default");
        assertThat(repository.currentEpoch("default")).isEqualTo(epoch + 1);
    }

    @Test
    void scimFilterValuesStayBoundParameters() {
        IamFilterSql.Sql sql = IamFilterSql.user(ScimFilterParser.parse(
                "userName eq \"x' OR 1=1 --\""));
        assertThat(sql.where()).doesNotContain("OR 1=1");
        assertThat(sql.where()).contains("user_name_normalized = ?");
        assertThat(sql.arguments()).containsExactly("x' or 1=1 --");
    }

    @Test
    void policyAndTotpPersist() {
        String totpUserId = new JdbcIamUserRepository(jdbc).create(IamUser.create("u-totp", "default",
                "totp-user", "TOTP User", null, true), "{bcrypt}hash").id();
        JdbcIamPolicyRepository policies = new JdbcIamPolicyRepository(jdbc);
        IamPolicy policy = policies.save(new IamPolicy("p-own", "default", "own", IamPolicyEffect.ALLOW,
                10, Set.of("order:read"), Set.of("order"), new PolicyCondition.Compare(
                PolicyOperand.resource("ownerId"), PolicyComparisonOperator.EQ,
                PolicyOperand.subject("userId")), true, 0));
        assertThat(policies.findById(policy.id()).orElseThrow().condition())
                .isInstanceOf(PolicyCondition.Compare.class);

        JdbcIamTotpRepository totp = new JdbcIamTotpRepository(jdbc);
        IamTotpState state = new IamTotpState(totpUserId, "default", "test", "cipher",
                true, -1, Instant.now(), Instant.now());
        totp.saveTotp(state);
        assertThat(totp.advanceLastUsedStep(totpUserId, 5)).isTrue();
        assertThat(totp.advanceLastUsedStep(totpUserId, 5)).isFalse();
        assertThat(totp.findTotp(totpUserId).orElseThrow().lastUsedStep()).isEqualTo(5);
    }

    @Test
    void signingKeysRotateAndStayReadable() {
        cn.byteforce.seedj.iam.spi.IamSecretProtector protector =
                new cn.byteforce.seedj.iam.spi.IamSecretProtector() {
                    @Override
                    public String protect(String plaintext, String context) {
                        return "test:" + plaintext;
                    }

                    @Override
                    public String reveal(String ciphertext, String context) {
                        return ciphertext.substring("test:".length());
                    }

                    @Override
                    public String keyName() {
                        return "test";
                    }
                };
        java.util.concurrent.atomic.AtomicLong kidSeq = new java.util.concurrent.atomic.AtomicLong();
        JdbcIamSigningKeyStore keys = new JdbcIamSigningKeyStore(jdbc, dataSource, protector,
                () -> "kid-" + kidSeq.incrementAndGet());
        var first = keys.activeKey();
        assertThat(first.kid()).isNotBlank();
        var second = keys.rotate();
        assertThat(second.kid()).isNotEqualTo(first.kid());
        assertThat(keys.keys()).hasSize(2);
        assertThat(keys.keys()).anyMatch(key -> !key.active() && key.privateJwk().isBlank());
    }
}
