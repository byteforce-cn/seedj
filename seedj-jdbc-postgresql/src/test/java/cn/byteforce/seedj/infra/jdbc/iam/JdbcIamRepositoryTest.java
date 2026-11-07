package cn.byteforce.seedj.infra.jdbc.iam;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.IamGroup;
import cn.byteforce.seedj.iam.IamGroupQuery;
import cn.byteforce.seedj.iam.IamMfaChallenge;
import cn.byteforce.seedj.iam.IamPage;
import cn.byteforce.seedj.iam.IamPermission;
import cn.byteforce.seedj.iam.IamRecoveryCode;
import cn.byteforce.seedj.iam.IamRole;
import cn.byteforce.seedj.iam.IamTotpState;
import cn.byteforce.seedj.iam.IamUser;
import cn.byteforce.seedj.iam.IamUserQuery;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repository-level behaviour against a real PostgreSQL schema: conflict mapping,
 * optimistic locking, membership writes, paging/filtering and epoch bumps.
 */
@Testcontainers
class JdbcIamRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("iam").withUsername("iam").withPassword("iam");

    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.postgresql.Driver");
        source.setUrl(POSTGRES.getJdbcUrl());
        source.setUsername(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        DataSource dataSource = source;
        jdbc = new JdbcTemplate(dataSource);
        JdbcMigrationRunner runner = new JdbcMigrationRunner(dataSource);
        new IamSchemaCapability(runner).initialize();
        new OAuth2SchemaCapability(runner).initialize();
    }

    @Test
    void userLifecycleAppliesOptimisticLockingAndCredentials() {
        JdbcIamUserRepository users = new JdbcIamUserRepository(jdbc);
        IamUser alice = users.create(IamUser.create("u-life", "t-user", "Alice", "Alice",
                "alice@example.com", true), "{bcrypt}one");

        assertThat(users.findById(alice.id()).orElseThrow().userName()).isEqualTo("Alice");
        assertThat(users.findByUserName("t-user", "ALICE")).isPresent();
        assertThat(users.findByUserName("t-user", "nobody")).isEmpty();
        assertThat(users.findByUserName("t-user", null)).isEmpty();
        assertThat(users.findPasswordHash(alice.id())).contains("{bcrypt}one");
        assertThat(users.count()).isPositive();

        IamUser updated = users.update(alice.withProfile("Alice Smith", "alice@corp.example",
                Map.of("department", "sales")));
        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.displayName()).isEqualTo("Alice Smith");
        assertThat(updated.attributes()).containsEntry("department", "sales");

        assertThatThrownBy(() -> users.update(alice))
                .isInstanceOf(IamException.class)
                .extracting(error -> ((IamException) error).getCode())
                .isEqualTo(IamErrorCode.CONFLICT);
        assertThatThrownBy(() -> users.update(IamUser.create("u-missing", "t-user",
                "missing", "Missing", null, true)))
                .isInstanceOf(IamException.class)
                .extracting(error -> ((IamException) error).getCode())
                .isEqualTo(IamErrorCode.USER_NOT_FOUND);
        assertThatThrownBy(() -> users.create(IamUser.create("u-dupe", "t-user", "alice",
                "Dupe", null, true), null))
                .isInstanceOf(IamException.class)
                .extracting(error -> ((IamException) error).getCode())
                .isEqualTo(IamErrorCode.USER_CONFLICT);

        users.updatePassword(alice.id(), "{bcrypt}two", Instant.now());
        users.updatePassword(alice.id(), "{bcrypt}three", null);
        assertThat(users.findPasswordHash(alice.id())).contains("{bcrypt}three");

        users.delete(alice.id());
        assertThat(users.findById(alice.id())).isEmpty();
        assertThat(users.findPasswordHash(alice.id())).isEmpty();
    }

    @Test
    void userQueryFiltersByWhitelistedAttributesAndPages() {
        JdbcIamUserRepository users = new JdbcIamUserRepository(jdbc);
        users.create(IamUser.create("q-alice", "t-uquery", "alice", "Alice", "alice@example.com",
                true), null);
        users.create(IamUser.create("q-bob", "t-uquery", "bob", "Bob", "bob@example.com", false),
                null);
        users.create(IamUser.create("q-carol", "t-uquery", "carol", "Carol", null, true), null);

        IamPage<IamUser> all = users.find(IamUserQuery.all("t-uquery"));
        assertThat(all.totalResults()).isEqualTo(3);
        assertThat(all.items()).extracting(IamUser::id)
                .containsExactly("q-alice", "q-bob", "q-carol");

        IamPage<IamUser> active = users.find(new IamUserQuery("t-uquery",
                ScimFilterParser.parse("active eq true"), 1, 10));
        assertThat(active.items()).extracting(IamUser::id).containsExactly("q-alice", "q-carol");

        IamPage<IamUser> page = users.find(new IamUserQuery("t-uquery", null, 2, 1));
        assertThat(page.totalResults()).isEqualTo(3);
        assertThat(page.items()).extracting(IamUser::id).containsExactly("q-bob");
        assertThat(page.startIndex()).isEqualTo(2);
        assertThat(page.itemsPerPage()).isEqualTo(1);
    }

    @Test
    void groupMembershipWritesAreTransactionalAndVersioned() {
        JdbcIamUserRepository users = new JdbcIamUserRepository(jdbc);
        users.create(IamUser.create("m-1", "t-group", "member-one", "Member One", null, true), null);
        users.create(IamUser.create("m-2", "t-group", "member-two", "Member Two", null, true), null);
        users.create(IamUser.create("m-3", "t-group", "member-three", "Member Three", null, true),
                null);
        JdbcIamGroupRepository groups = new JdbcIamGroupRepository(jdbc, dataSource());

        IamGroup group = groups.create(IamGroup.create("g-life", "t-group", "Sales", "ext-sales",
                Set.of("m-1", "m-2")));

        assertThat(groups.findByDisplayName("t-group", "SALES")).isPresent();
        assertThat(groups.findByDisplayName("t-group", null)).isEmpty();
        assertThat(groups.findById(group.id()).orElseThrow().memberIds())
                .containsExactlyInAnyOrder("m-1", "m-2");
        assertThat(groups.findMemberIds(group.id())).containsExactlyInAnyOrder("m-1", "m-2");
        assertThat(groups.findGroupIdsForUser("m-1")).containsExactly("g-life");

        groups.addMember(group.id(), "m-3");
        assertThat(groups.findMemberIds(group.id())).hasSize(3);
        groups.removeMember(group.id(), "m-3");
        assertThat(groups.findMemberIds(group.id())).hasSize(2);
        assertThat(groups.findById(group.id()).orElseThrow().version()).isEqualTo(2);

        IamGroup current = groups.findById(group.id()).orElseThrow();
        IamGroup narrowed = groups.update(current.withMembers(Set.of("m-1")));
        assertThat(narrowed.memberIds()).containsExactly("m-1");
        assertThat(narrowed.version()).isEqualTo(3);

        assertThatThrownBy(() -> groups.update(current))
                .isInstanceOf(IamException.class)
                .extracting(error -> ((IamException) error).getCode())
                .isEqualTo(IamErrorCode.CONFLICT);
        assertThatThrownBy(() -> groups.update(IamGroup.create("g-missing", "t-group",
                "Missing", null, Set.of())))
                .isInstanceOf(IamException.class)
                .extracting(error -> ((IamException) error).getCode())
                .isEqualTo(IamErrorCode.GROUP_NOT_FOUND);
        assertThatThrownBy(() -> groups.create(IamGroup.create("g-dupe", "t-group", "Sales",
                null, Set.of())))
                .isInstanceOf(IamException.class)
                .extracting(error -> ((IamException) error).getCode())
                .isEqualTo(IamErrorCode.GROUP_CONFLICT);

        groups.delete(group.id());
        assertThat(groups.findById(group.id())).isEmpty();
        assertThat(groups.findGroupIdsForUser("m-1")).isEmpty();
    }

    @Test
    void groupQueryFiltersByDisplayNameAndPages() {
        JdbcIamGroupRepository groups = new JdbcIamGroupRepository(jdbc, dataSource());
        groups.create(IamGroup.create("gq-alpha", "t-gquery", "Alpha", null, Set.of()));
        groups.create(IamGroup.create("gq-beta", "t-gquery", "beta", null, Set.of()));
        groups.create(IamGroup.create("gq-gamma", "t-gquery", "Gamma", null, Set.of()));

        IamPage<IamGroup> all = groups.find(IamGroupQuery.all("t-gquery"));
        assertThat(all.totalResults()).isEqualTo(3);
        assertThat(all.items()).extracting(IamGroup::id)
                .containsExactly("gq-alpha", "gq-beta", "gq-gamma");

        IamPage<IamGroup> prefixed = groups.find(new IamGroupQuery("t-gquery",
                ScimFilterParser.parse("displayName sw \"a\""), 1, 10));
        assertThat(prefixed.items()).extracting(IamGroup::id).containsExactly("gq-alpha");

        IamPage<IamGroup> page = groups.find(new IamGroupQuery("t-gquery", null, 2, 1));
        assertThat(page.items()).extracting(IamGroup::id).containsExactly("gq-beta");
        assertThat(page.totalResults()).isEqualTo(3);
    }

    @Test
    void totpAndRecoveryCodesPersistAndDeleteTogether() {
        String userId = new JdbcIamUserRepository(jdbc).create(IamUser.create("totp-user", "t-totp",
                "totp-user", "TOTP User", null, true), null).id();
        JdbcIamTotpRepository totp = new JdbcIamTotpRepository(jdbc);

        assertThat(totp.findTotp(userId)).isEmpty();

        totp.saveTotp(new IamTotpState(userId, "t-totp", "kid-1", "cipher-1", false, -1,
                Instant.now(), null));
        IamTotpState stored = totp.findTotp(userId).orElseThrow();
        assertThat(stored.confirmed()).isFalse();
        assertThat(stored.lastUsedStep()).isEqualTo(-1);

        assertThat(totp.advanceLastUsedStep(userId, 5)).isTrue();
        assertThat(totp.advanceLastUsedStep(userId, 5)).isFalse();
        assertThat(totp.advanceLastUsedStep(userId, 4)).isFalse();

        totp.saveTotp(stored.confirmed(Instant.now()).withLastUsedStep(5));
        IamTotpState confirmed = totp.findTotp(userId).orElseThrow();
        assertThat(confirmed.confirmed()).isTrue();
        assertThat(confirmed.lastUsedStep()).isEqualTo(5);
        assertThat(confirmed.confirmedAt()).isNotNull();

        Instant first = Instant.parse("2026-01-01T00:00:00Z");
        totp.replaceRecoveryCodes(userId, List.of(
                new IamRecoveryCode(userId, "hash-1", false, first),
                new IamRecoveryCode(userId, "hash-2", false, first.plusSeconds(1))));
        assertThat(totp.findRecoveryCodes(userId)).extracting(IamRecoveryCode::codeHash)
                .containsExactly("hash-1", "hash-2");

        totp.markRecoveryCodeUsed(userId, "hash-1");
        assertThat(totp.findRecoveryCodes(userId)).extracting(IamRecoveryCode::codeHash)
                .containsExactly("hash-2");

        totp.replaceRecoveryCodes(userId, List.of(
                new IamRecoveryCode(userId, "hash-3", false, first)));
        assertThat(totp.findRecoveryCodes(userId)).extracting(IamRecoveryCode::codeHash)
                .containsExactly("hash-3");

        totp.deleteTotp(userId);
        assertThat(totp.findTotp(userId)).isEmpty();
        assertThat(totp.findRecoveryCodes(userId)).isEmpty();
    }

    @Test
    void mfaChallengesUpsertAndUpdate() {
        JdbcIamTotpRepository totp = new JdbcIamTotpRepository(jdbc);
        IamMfaChallenge challenge = new IamMfaChallenge("ch-1", "u-mfa", "client-1", "device-1",
                "token-hash-1", Instant.now().plusSeconds(300), false, 0, 5);

        assertThat(totp.findChallengeByTokenHash("token-hash-1")).isEmpty();

        totp.saveChallenge(challenge);
        IamMfaChallenge stored = totp.findChallengeByTokenHash("token-hash-1").orElseThrow();
        assertThat(stored.attempts()).isZero();
        assertThat(stored.consumed()).isFalse();
        assertThat(stored.deviceId()).isEqualTo("device-1");

        totp.saveChallenge(challenge.withAttempt().withAttempt());
        IamMfaChallenge retried = totp.findChallengeByTokenHash("token-hash-1").orElseThrow();
        assertThat(retried.attempts()).isEqualTo(2);
        assertThat(retried.maxAttempts()).isEqualTo(5);

        totp.updateChallenge(retried.markConsumed());
        IamMfaChallenge consumed = totp.findChallengeByTokenHash("token-hash-1").orElseThrow();
        assertThat(consumed.consumed()).isTrue();
        assertThat(consumed.attempts()).isEqualTo(2);

        assertThat(totp.findChallengeByTokenHash("token-hash-missing")).isEmpty();
    }

    @Test
    void authorizationRolesPermissionsAndBindings() {
        JdbcIamUserRepository users = new JdbcIamUserRepository(jdbc);
        users.create(IamUser.create("authz-user", "t-authz", "authz-user", "Authz User", null, true),
                null);
        JdbcIamAuthorizationRepository repository = new JdbcIamAuthorizationRepository(jdbc);

        IamRole base = repository.createRole(IamRole.create("ar-base", "t-authz", "base", "Base",
                "", null, false));
        IamRole manager = repository.createRole(IamRole.create("ar-manager", "t-authz", "manager",
                "Manager", "", base.id(), false));

        assertThat(repository.findRole(manager.id()).orElseThrow().parentId())
                .isEqualTo(base.id());
        assertThat(repository.findRoleByCode("t-authz", "manager")).isPresent();
        assertThat(repository.findRoleByCode("t-authz", "missing")).isEmpty();
        assertThat(repository.findRoles("t-authz")).extracting(IamRole::code)
                .containsExactly("base", "manager");
        assertThat(repository.findRoles(null)).extracting(IamRole::code)
                .doesNotContain("base");

        IamRole renamed = new IamRole(manager.id(), "t-authz", "manager", "Manager v2",
                "line manager", base.id(), false, manager.version());
        assertThat(repository.updateRole(renamed).name()).isEqualTo("Manager v2");
        assertThat(repository.findRole(manager.id()).orElseThrow().version()).isEqualTo(1);

        assertThatThrownBy(() -> repository.updateRole(renamed))
                .isInstanceOf(IamException.class)
                .extracting(error -> ((IamException) error).getCode())
                .isEqualTo(IamErrorCode.CONFLICT);
        assertThatThrownBy(() -> repository.updateRole(IamRole.create("ar-missing", "t-authz",
                "missing", "Missing", "", null, false)))
                .isInstanceOf(IamException.class)
                .extracting(error -> ((IamException) error).getCode())
                .isEqualTo(IamErrorCode.ROLE_NOT_FOUND);
        assertThatThrownBy(() -> repository.createRole(IamRole.create("ar-dupe", "t-authz",
                "base", "Dupe", "", null, false)))
                .isInstanceOf(IamException.class)
                .extracting(error -> ((IamException) error).getCode())
                .isEqualTo(IamErrorCode.ROLE_CONFLICT);
    }

    @Test
    void authorizationPermissionsAndUserRoleBindings() {
        JdbcIamUserRepository users = new JdbcIamUserRepository(jdbc);
        users.create(IamUser.create("bind-user", "t-binding", "bind-user", "Bind User", null, true),
                null);
        JdbcIamAuthorizationRepository repository = new JdbcIamAuthorizationRepository(jdbc);
        IamRole parent = repository.createRole(IamRole.create("ab-parent", "t-binding", "parent",
                "Parent", "", null, false));
        IamRole child = repository.createRole(IamRole.create("ab-child", "t-binding", "child",
                "Child", "", parent.id(), false));

        repository.savePermission(IamPermission.of("t-binding", "order", "read", ""));
        repository.savePermission(new IamPermission("t-binding", "order:read", "order", "read",
                "Read orders"));
        assertThat(repository.findPermissions("t-binding")).hasSize(1);
        assertThat(repository.findPermissions("t-binding").get(0).description())
                .isEqualTo("Read orders");

        repository.grantPermission(child.id(), "order:read");
        assertThat(repository.findPermissionCodes(Set.of(child.id())))
                .containsExactly("order:read");
        assertThat(repository.findPermissionCodes(Set.of())).isEmpty();
        assertThat(repository.findPermissionCodes(null)).isEmpty();

        repository.revokePermission(child.id(), "order:read");
        repository.revokePermission(child.id(), "order:read");
        assertThat(repository.findPermissionCodes(Set.of(child.id()))).isEmpty();

        repository.assignRoleToUser("bind-user", child.id(), null, "tester");
        repository.assignRoleToUser("bind-user", child.id(), Instant.now(), "tester-2");
        assertThat(repository.findRoleIdsForUser("bind-user")).containsExactly(child.id());
        repository.unassignRoleFromUser("bind-user", child.id());
        repository.unassignRoleFromUser("bind-user", child.id());
        assertThat(repository.findRoleIdsForUser("bind-user")).isEmpty();

        assertThat(repository.findRoleIdsWithAncestors(Set.of(child.id())))
                .containsExactlyInAnyOrder(child.id(), parent.id());
        assertThat(repository.findRoleIdsWithAncestors(Set.of())).isEmpty();
        assertThat(repository.findRoleIdsWithAncestors(null)).isEmpty();
    }

    @Test
    void authorizationGroupBindingsEpochAndRoleDeletion() {
        JdbcIamGroupRepository groups = new JdbcIamGroupRepository(jdbc, dataSource());
        groups.create(IamGroup.create("ag-1", "t-groupbind", "Bound Group", null, Set.of()));
        JdbcIamAuthorizationRepository repository = new JdbcIamAuthorizationRepository(jdbc);
        IamRole role = repository.createRole(IamRole.create("agr-1", "t-groupbind", "role",
                "Role", "", null, false));

        repository.assignRoleToGroup("ag-1", role.id());
        repository.assignRoleToGroup("ag-1", role.id());
        assertThat(repository.findRoleIdsForGroup("ag-1")).containsExactly(role.id());
        repository.unassignRoleFromGroup("ag-1", role.id());
        repository.unassignRoleFromGroup("ag-1", role.id());
        assertThat(repository.findRoleIdsForGroup("ag-1")).isEmpty();

        long epoch = repository.currentEpoch("t-groupbind");
        assertThat(epoch).isPositive();
        repository.bumpEpoch("t-groupbind");
        repository.bumpEpoch("t-groupbind");
        assertThat(repository.currentEpoch("t-groupbind")).isEqualTo(epoch + 2);
        assertThat(repository.currentEpoch("t-unknown-tenant")).isZero();

        repository.deleteRole(role.id());
        assertThat(repository.findRole(role.id())).isEmpty();
        assertThat(repository.currentEpoch("t-groupbind")).isEqualTo(epoch + 3);
        assertThatCode(() -> repository.deleteRole("role-that-does-not-exist"))
                .doesNotThrowAnyException();
    }

    private static DataSource dataSource() {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.postgresql.Driver");
        source.setUrl(POSTGRES.getJdbcUrl());
        source.setUsername(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }
}
