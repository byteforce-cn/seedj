package cn.byteforce.seedj.starter.iam.identity;

import cn.byteforce.seedj.iam.IamGroup;
import cn.byteforce.seedj.iam.IamPermission;
import cn.byteforce.seedj.iam.IamRole;
import cn.byteforce.seedj.iam.IamUser;
import cn.byteforce.seedj.iam.spi.IamPasswordHasher;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamAuthorizationRepository;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamGroupRepository;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IamUserDetailsServiceTest {

    private final InMemoryIamUserRepository users = new InMemoryIamUserRepository();
    private final InMemoryIamGroupRepository groups = new InMemoryIamGroupRepository();
    private final InMemoryIamAuthorizationRepository authorization =
            new InMemoryIamAuthorizationRepository();
    private final IamPasswordHasher hasher = new IamPasswordHasherAdapter(
            PasswordEncoderFactories.createDelegatingPasswordEncoder());
    private final IamAuthorizationService authorizationService =
            new IamAuthorizationService(authorization, groups, Duration.ofSeconds(5));

    @Test
    void loadsAuthoritiesFromRolesPermissionsAndGroups() {
        IamUser user = users.create(IamUser.create("u-1", "default", "alice", "Alice", null, true),
                hasher.encode("alice-password"));
        IamGroup group = groups.create(IamGroup.create("g-1", "default", "sales", null, Set.of()));
        groups.addMember(group.id(), user.id());
        IamRole role = authorization.createRole(
                IamRole.create("r-1", "default", "reader", "Reader", "", null, false));
        authorization.savePermission(IamPermission.of("default", "order", "read", "Read orders"));
        authorization.grantPermission(role.id(), "order:read");
        authorization.assignRoleToUser(user.id(), role.id(), Instant.now(), "test");

        UserDetails details = service("default").loadUserByUsername("alice");

        assertThat(details.getUsername()).isEqualTo("alice");
        assertThat(details.getPassword()).startsWith("{");
        assertThat(details.isEnabled()).isTrue();
        assertThat(details.getAuthorities()).extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_reader", "PERM_order:read", "GROUP_sales");
    }

    @Test
    void disablesInactiveUser() {
        users.create(IamUser.create("u-1", "default", "alice", "Alice", null, false),
                hasher.encode("alice-password"));

        assertThat(service("default").loadUserByUsername("alice").isEnabled()).isFalse();
    }

    @Test
    void failsForUnknownUser() {
        assertThatThrownBy(() -> service("default").loadUserByUsername("ghost"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining("user not found");
    }

    @Test
    void failsWhenUserHasNoCredential() {
        users.create(IamUser.create("u-1", "default", "alice", "Alice", null, true), null);

        assertThatThrownBy(() -> service("default").loadUserByUsername("alice"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessage("user has no credential");
    }

    @Test
    void fallsBackToDefaultTenantForBlankTenantId() {
        users.create(IamUser.create("u-1", IamUser.DEFAULT_TENANT, "alice", "Alice", null, true),
                hasher.encode("alice-password"));

        assertThat(service("  ").loadUserByUsername("alice").getUsername()).isEqualTo("alice");
        assertThat(service(null).loadUserByUsername("alice").getUsername()).isEqualTo("alice");
    }

    private IamUserDetailsService service(String tenantId) {
        return new IamUserDetailsService(users, authorizationService, tenantId);
    }
}
