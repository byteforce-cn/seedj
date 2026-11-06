package cn.byteforce.seedj.iam.identity;

import cn.byteforce.seedj.iam.IamPermission;
import cn.byteforce.seedj.iam.IamRole;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamAuthorizationRepository;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamGroupRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IamAuthorizationServiceTest {

    private final InMemoryIamAuthorizationRepository repository =
            new InMemoryIamAuthorizationRepository();
    private final InMemoryIamGroupRepository groups = new InMemoryIamGroupRepository();
    private final IamAuthorizationService service =
            new IamAuthorizationService(repository, groups, Duration.ofSeconds(30));

    @Test
    void resolvesDirectGroupAndInheritedRolesAndPermissions() {
        IamRole base = repository.createRole(IamRole.create("r-user", "default", "user", "User", "", null, false));
        IamRole manager = repository.createRole(IamRole.create("r-manager", "default", "manager", "Manager",
                "", base.id(), false));
        repository.savePermission(IamPermission.of("default", "order", "read", ""));
        repository.savePermission(IamPermission.of("default", "order", "approve", ""));
        repository.grantPermission(base.id(), "order:read");
        repository.grantPermission(manager.id(), "order:approve");

        var group = groups.create(cn.byteforce.seedj.iam.IamGroup.create("g-sales", "default", "sales", null,
                Set.of("u-1")));
        repository.assignRoleToGroup(group.id(), manager.id());
        repository.assignRoleToUser("u-1", base.id(), java.time.Instant.now(), "test");

        var authorization = service.effective("default", "u-1");

        assertThat(authorization.roles()).containsExactlyInAnyOrder("user", "manager");
        assertThat(authorization.permissions()).containsExactlyInAnyOrder("order:read", "order:approve");
        assertThat(authorization.groups()).containsExactly("sales");
    }

    @Test
    void epochChangeInvalidatesCachedAuthorization() {
        repository.createRole(IamRole.create("r-user", "default", "user", "User", "", null, false));
        repository.assignRoleToUser("u-2", repository.findRoleByCode("default", "user").orElseThrow().id(),
                java.time.Instant.now(), "test");

        assertThat(service.effective("default", "u-2").roles()).contains("user");

        repository.createRole(IamRole.create("r-admin", "default", "admin", "Admin", "", null, false));
        repository.assignRoleToUser("u-2", repository.findRoleByCode("default", "admin").orElseThrow().id(),
                java.time.Instant.now(), "test");
        repository.bumpEpoch("default");

        assertThat(service.effective("default", "u-2").roles()).contains("admin");
    }
}
