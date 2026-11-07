package cn.byteforce.seedj.starter.iam.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.iam.IamPermission;
import cn.byteforce.seedj.iam.IamRole;
import cn.byteforce.seedj.iam.IamUser;
import cn.byteforce.seedj.iam.spi.IamPasswordHasher;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamAuditSink;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamAuthorizationRepository;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamTokenRevoker;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamUserRepository;
import cn.byteforce.seedj.starter.iam.IamProperties;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

class IamBootstrapRunnerTest {

    private final InMemoryIamUserRepository users = new InMemoryIamUserRepository();
    private final InMemoryIamAuthorizationRepository authorization =
            new InMemoryIamAuthorizationRepository();
    private final IamPasswordHasher hasher =
            new IamPasswordHasherAdapter(
                    PasswordEncoderFactories.createDelegatingPasswordEncoder());
    private final AtomicLong seq = new AtomicLong();
    private final IdGenerator<String> idGenerator = () -> "id-" + seq.incrementAndGet();
    private final IamIdentityService identities =
            new IamIdentityService(
                    users,
                    hasher,
                    new InMemoryIamTokenRevoker(),
                    new InMemoryIamAuditSink(),
                    idGenerator);

    @Test
    void createsAdministratorRoleAndUserOnce() {
        IamBootstrapRunner runner =
                runner(new IamProperties.Admin("admin", "admin-password", "default"));

        runner.afterSingletonsInstantiated();
        runner.afterSingletonsInstantiated();

        IamUser admin = users.findByUserName("default", "admin").orElseThrow();
        IamRole role = authorization.findRoleByCode("default", "iam-admin").orElseThrow();
        assertThat(authorization.findRoleIdsForUser(admin.id())).containsExactly(role.id());
        assertThat(authorization.findPermissionCodes(java.util.Set.of(role.id())))
                .containsExactly("iam:admin");
        assertThat(authorization.findPermissions("default"))
                .extracting(IamPermission::code)
                .containsExactly("iam:admin");
    }

    @Test
    void reusesExistingRoleAndUser() {
        IamRole existing =
                authorization.createRole(
                        IamRole.create(
                                "r-existing", "default", "iam-admin", "Existing", "", null, true));
        IamUser existingUser =
                identities.createUser("default", "admin", "admin-password", null, null);
        IamBootstrapRunner runner =
                runner(new IamProperties.Admin("admin", "admin-password", "default"));

        runner.afterSingletonsInstantiated();

        assertThat(authorization.findRoles("default")).containsExactly(existing);
        assertThat(users.findById(existingUser.id())).isPresent();
        assertThat(authorization.findRoleIdsForUser(existingUser.id()))
                .containsExactly("r-existing");
    }

    @Test
    void skipsWhenUsernameIsMissing() {
        runner(new IamProperties.Admin("  ", "admin-password", "default"))
                .afterSingletonsInstantiated();

        assertThat(users.findByUserName("default", "admin")).isEmpty();
        assertThat(authorization.findRoles("default")).isEmpty();
    }

    @Test
    void failsFastWhenPasswordIsMissing() {
        IamBootstrapRunner runner = runner(new IamProperties.Admin("admin", " ", "default"));

        assertThatThrownBy(runner::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bootstrap-password is empty");
    }

    private IamBootstrapRunner runner(IamProperties.Admin properties) {
        return new IamBootstrapRunner(properties, users, authorization, identities, idGenerator);
    }
}
