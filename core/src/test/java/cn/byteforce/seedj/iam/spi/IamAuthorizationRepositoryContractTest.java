package cn.byteforce.seedj.iam.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.IamPermission;
import cn.byteforce.seedj.iam.IamRole;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** {@link IamAuthorizationRepository} 契约测试套件：角色、权限、分配、继承与授权 epoch。 */
public abstract class IamAuthorizationRepositoryContractTest {

    protected abstract IamAuthorizationRepository repository();

    @Test
    void createsAndFindsRoles() {
        IamRole admin = IamRole.create("r1", "default", "admin", "Admin", "", null, false);
        IamRole viewer = IamRole.create("r2", "default", "viewer", "Viewer", "", null, true);

        repository().createRole(admin);
        repository().createRole(viewer);

        assertThat(repository().findRole("r1")).contains(admin);
        assertThat(repository().findRoleByCode("default", "admin")).contains(admin);
        assertThat(repository().findRoles("default"))
                .extracting(IamRole::code)
                .containsExactly("admin", "viewer");
        assertThat(repository().findRoles("other")).isEmpty();
    }

    @Test
    void rejectsDuplicateRoleCodeAndId() {
        repository().createRole(IamRole.create("r1", "default", "admin", "Admin", "", null, false));

        assertThatThrownBy(
                        () ->
                                repository()
                                        .createRole(
                                                IamRole.create(
                                                        "r2", "default", "admin", "Admin", "", null,
                                                        false)))
                .isInstanceOfSatisfying(
                        IamException.class,
                        e -> assertThat(e.getCode()).isEqualTo(IamErrorCode.ROLE_CONFLICT));
        assertThatThrownBy(
                        () ->
                                repository()
                                        .createRole(
                                                IamRole.create(
                                                        "r1", "default", "other", "Other", "", null,
                                                        false)))
                .isInstanceOfSatisfying(
                        IamException.class,
                        e -> assertThat(e.getCode()).isEqualTo(IamErrorCode.ROLE_CONFLICT));
    }

    @Test
    void allowsSameRoleCodeInAnotherTenant() {
        repository().createRole(IamRole.create("r1", "t1", "admin", "Admin", "", null, false));
        repository().createRole(IamRole.create("r2", "t2", "admin", "Admin", "", null, false));

        assertThat(repository().findRoleByCode("t2", "admin")).isPresent();
    }

    @Test
    void updateIncrementsVersion() {
        IamRole stored =
                repository()
                        .createRole(
                                IamRole.create("r1", "default", "admin", "Admin", "", null, false));

        IamRole updated = repository().updateRole(stored.withParent("r0"));

        assertThat(updated.version()).isEqualTo(stored.version() + 1);
        assertThat(repository().findRole("r1").orElseThrow().parentId()).isEqualTo("r0");
    }

    @Test
    void updateRejectsStaleVersionAndUnknownRole() {
        IamRole stored =
                repository()
                        .createRole(
                                IamRole.create("r1", "default", "admin", "Admin", "", null, false));
        repository().updateRole(stored.withParent("r0"));

        assertThatThrownBy(() -> repository().updateRole(stored.withParent("r1")))
                .isInstanceOfSatisfying(
                        IamException.class,
                        e -> assertThat(e.getCode()).isEqualTo(IamErrorCode.CONFLICT));
        assertThatThrownBy(
                        () ->
                                repository()
                                        .updateRole(
                                                IamRole.create(
                                                        "missing", "default", "ghost", "Ghost", "",
                                                        null, false)))
                .isInstanceOfSatisfying(
                        IamException.class,
                        e -> assertThat(e.getCode()).isEqualTo(IamErrorCode.ROLE_NOT_FOUND));
    }

    @Test
    void permissionsAreStoredPerTenantAndSorted() {
        repository().savePermission(IamPermission.of("default", "employee", "read", ""));
        repository().savePermission(IamPermission.of("default", "audit", "read", ""));
        repository().savePermission(IamPermission.of("other", "employee", "read", ""));

        assertThat(repository().findPermissions("default"))
                .extracting(IamPermission::code)
                .containsExactly("audit:read", "employee:read");
        assertThat(repository().findPermissions("other"))
                .extracting(IamPermission::code)
                .containsExactly("employee:read");
    }

    @Test
    void grantsAndRevokesPermissions() {
        IamRole role =
                repository()
                        .createRole(
                                IamRole.create("r1", "default", "admin", "Admin", "", null, false));

        repository().grantPermission(role.id(), "employee:read");
        repository().grantPermission(role.id(), "employee:write");

        assertThat(repository().findPermissionCodes(java.util.Set.of(role.id())))
                .containsExactlyInAnyOrder("employee:read", "employee:write");

        repository().revokePermission(role.id(), "employee:read");

        assertThat(repository().findPermissionCodes(java.util.Set.of(role.id())))
                .containsExactly("employee:write");
    }

    @Test
    void findsPermissionCodesForSeveralRoles() {
        IamRole a =
                repository().createRole(IamRole.create("r1", "default", "a", "A", "", null, false));
        IamRole b =
                repository().createRole(IamRole.create("r2", "default", "b", "B", "", null, false));
        repository().grantPermission(a.id(), "employee:read");
        repository().grantPermission(b.id(), "employee:write");

        assertThat(repository().findPermissionCodes(java.util.Set.of(a.id(), b.id())))
                .containsExactlyInAnyOrder("employee:read", "employee:write");
    }

    @Test
    void assignsAndUnassignsRolesToUsers() {
        IamRole role =
                repository()
                        .createRole(
                                IamRole.create("r1", "default", "admin", "Admin", "", null, false));

        repository().assignRoleToUser("u1", role.id(), Instant.now(), "admin");
        repository().assignRoleToUser("u1", role.id(), Instant.now(), "admin");

        assertThat(repository().findRoleIdsForUser("u1")).containsExactly("r1");

        repository().unassignRoleFromUser("u1", role.id());

        assertThat(repository().findRoleIdsForUser("u1")).isEmpty();
    }

    @Test
    void assignsAndUnassignsRolesToGroups() {
        IamRole role =
                repository()
                        .createRole(
                                IamRole.create("r1", "default", "admin", "Admin", "", null, false));

        repository().assignRoleToGroup("g1", role.id());
        repository().assignRoleToGroup("g1", role.id());

        assertThat(repository().findRoleIdsForGroup("g1")).containsExactly("r1");

        repository().unassignRoleFromGroup("g1", role.id());

        assertThat(repository().findRoleIdsForGroup("g1")).isEmpty();
    }

    @Test
    void resolvesAncestorsAndSurvivesCycles() {
        repository().createRole(IamRole.create("root", "default", "root", "Root", "", null, false));
        repository()
                .createRole(
                        IamRole.create("child", "default", "child", "Child", "", "root", false));
        repository()
                .createRole(IamRole.create("leaf", "default", "leaf", "Leaf", "", "child", false));

        assertThat(repository().findRoleIdsWithAncestors(java.util.Set.of("leaf")))
                .containsExactlyInAnyOrder("leaf", "child", "root");

        repository().updateRole(repository().findRole("root").orElseThrow().withParent("leaf"));

        assertThat(repository().findRoleIdsWithAncestors(java.util.Set.of("leaf")))
                .containsExactlyInAnyOrder("leaf", "child", "root");
    }

    @Test
    void deleteRoleRemovesAssignmentsAndPermissions() {
        IamRole role =
                repository()
                        .createRole(
                                IamRole.create("r1", "default", "admin", "Admin", "", null, false));
        repository().grantPermission(role.id(), "employee:read");
        repository().assignRoleToUser("u1", role.id(), Instant.now(), "admin");
        repository().assignRoleToGroup("g1", role.id());

        repository().deleteRole(role.id());

        assertThat(repository().findRole("r1")).isEmpty();
        assertThat(repository().findPermissionCodes(java.util.Set.of("r1"))).isEmpty();
        assertThat(repository().findRoleIdsForUser("u1")).isEmpty();
        assertThat(repository().findRoleIdsForGroup("g1")).isEmpty();
    }

    @Test
    void epochAdvancesPerTenant() {
        long before = repository().currentEpoch("default");
        long otherBefore = repository().currentEpoch("other");

        repository().createRole(IamRole.create("r1", "default", "admin", "Admin", "", null, false));

        assertThat(repository().currentEpoch("default")).isGreaterThan(before);
        assertThat(repository().currentEpoch("other")).isEqualTo(otherBefore);
    }

    @Test
    void epochAdvancesOnPermissionAndAssignmentChanges() {
        IamRole role =
                repository()
                        .createRole(
                                IamRole.create("r1", "default", "admin", "Admin", "", null, false));

        long afterCreate = repository().currentEpoch("default");
        repository().grantPermission(role.id(), "employee:read");
        long afterGrant = repository().currentEpoch("default");
        repository().assignRoleToUser("u1", role.id(), Instant.now(), "admin");
        long afterAssign = repository().currentEpoch("default");
        repository().savePermission(IamPermission.of("default", "employee", "read", ""));
        long afterPermission = repository().currentEpoch("default");

        assertThat(afterGrant).isGreaterThan(afterCreate);
        assertThat(afterAssign).isGreaterThan(afterGrant);
        assertThat(afterPermission).isGreaterThan(afterAssign);
    }
}
