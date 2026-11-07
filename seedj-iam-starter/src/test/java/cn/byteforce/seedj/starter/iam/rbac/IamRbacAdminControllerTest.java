package cn.byteforce.seedj.starter.iam.rbac;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.IamRole;
import cn.byteforce.seedj.iam.IamUser;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamAuthorizationRepository;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamUserRepository;
import cn.byteforce.seedj.starter.iam.IamProperties;
import cn.byteforce.seedj.starter.iam.TestIdGenerators;
import cn.byteforce.seedj.starter.iam.identity.IamAuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** RBAC 管理面：角色/权限/分配与授权缓存失效。 */
class IamRbacAdminControllerTest {

    private final InMemoryIamAuthorizationRepository repository =
            new InMemoryIamAuthorizationRepository();
    private final InMemoryIamUserRepository users = new InMemoryIamUserRepository();
    private final IamAuthorizationService authorization = mock(IamAuthorizationService.class);

    private IamRbacAdminController controller;

    @BeforeEach
    void setUp() {
        controller = new IamRbacAdminController(repository, users, authorization,
                IamProperties.defaults(), TestIdGenerators.sequential());
    }

    @Test
    @DisplayName("createRole 生成 id 并失效授权缓存")
    void createRoleStoresAndInvalidates() {
        Map<String, Object> created = controller.createRole(Map.of(
                "code", "approver",
                "name", "审批人",
                "description", "采购审批",
                "parentId", "r-parent"));

        assertThat(created).containsEntry("id", "test-1")
                .containsEntry("code", "approver")
                .containsEntry("name", "审批人")
                .containsEntry("description", "采购审批")
                .containsEntry("parentId", "r-parent")
                .containsEntry("builtIn", false)
                .containsEntry("version", 0L);
        assertThat(repository.findRole("test-1")).isPresent();
        verify(authorization).invalidate();
    }

    @Test
    @DisplayName("createRole 缺 code 报 SCIM_BAD_REQUEST 且不失效缓存")
    void createRoleRequiresCode() {
        assertThatThrownBy(() -> controller.createRole(Map.of("name", "no code")))
                .isInstanceOfSatisfying(IamException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(IamErrorCode.SCIM_BAD_REQUEST));

        verify(authorization, never()).invalidate();
    }

    @Test
    @DisplayName("roles 只列出当前租户的角色")
    void rolesListsTenantRoles() {
        repository.createRole(IamRole.create("r-1", "default", "approver", "审批人", "", null,
                false));
        repository.createRole(IamRole.create("r-2", "acme", "foreign", "外部", "", null, false));

        assertThat(controller.roles()).extracting(role -> role.get("code"))
                .containsExactly("approver");
    }

    @Test
    @DisplayName("updateRole 覆盖给出的字段并递增版本")
    void updateRoleOverwritesProvidedFields() {
        repository.createRole(IamRole.create("r-1", "default", "approver", "审批人", "旧描述",
                null, false));

        Map<String, Object> updated = controller.updateRole("r-1", Map.of(
                "code", "approver-v2",
                "description", "新描述"));

        assertThat(updated).containsEntry("code", "approver-v2")
                .containsEntry("name", "审批人")
                .containsEntry("description", "新描述")
                .containsEntry("version", 1L);
        verify(authorization).invalidate();
    }

    @Test
    @DisplayName("updateRole 空请求体保留全部字段")
    void updateRoleWithEmptyBodyKeepsAllFields() {
        repository.createRole(IamRole.create("r-1", "default", "approver", "审批人", "描述",
                "r-parent", false));

        Map<String, Object> updated = controller.updateRole("r-1", Map.of());

        assertThat(updated).containsEntry("code", "approver")
                .containsEntry("name", "审批人")
                .containsEntry("description", "描述")
                .containsEntry("parentId", "r-parent")
                .containsEntry("version", 1L);
    }

    @Test
    @DisplayName("updateRole 未知角色报 ROLE_NOT_FOUND")
    void updateUnknownRoleFails() {
        assertThatThrownBy(() -> controller.updateRole("missing", Map.of("name", "x")))
                .isInstanceOfSatisfying(IamException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(IamErrorCode.ROLE_NOT_FOUND));
    }

    @Test
    @DisplayName("deleteRole 返回被删 id 并失效缓存")
    void deleteRoleRemovesIt() {
        repository.createRole(IamRole.create("r-1", "default", "approver", "审批人", "", null,
                false));

        assertThat(controller.deleteRole("r-1")).containsEntry("deleted", "r-1");
        assertThat(repository.findRole("r-1")).isEmpty();
        verify(authorization).invalidate();
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("replaceRolePermissions 撤销旧权限、授予新权限")
    void replaceRolePermissionsReplacesSet() {
        repository.createRole(IamRole.create("r-1", "default", "approver", "审批人", "", null,
                false));
        repository.grantPermission("r-1", "order.read");
        repository.grantPermission("r-1", "order.delete");

        Map<String, Object> replaced = controller.replaceRolePermissions("r-1", Map.of(
                "permissions", List.of("order.read", "order.approve")));

        assertThat((List<Object>) replaced.get("permissions")).containsExactly("order.read",
                "order.approve");
        assertThat(controller.rolePermissions("r-1").get("permissions"))
                .isEqualTo(repository.findPermissionCodes(java.util.Set.of("r-1")));
        assertThat(repository.findPermissionCodes(java.util.Set.of("r-1")))
                .containsExactlyInAnyOrder("order.read", "order.approve");
        verify(authorization).invalidate();
    }

    @Test
    @DisplayName("replaceRolePermissions 缺 permissions 时清空全部授权")
    void replaceRolePermissionsWithoutBodyClearsSet() {
        repository.grantPermission("r-1", "order.read");

        controller.replaceRolePermissions("r-1", Map.of());

        assertThat(repository.findPermissionCodes(java.util.Set.of("r-1"))).isEmpty();
    }

    @Test
    @DisplayName("permissions 列出当前租户权限码")
    void permissionsListsTenantPermissions() {
        controller.createPermission(Map.of("resource", "order", "action", "read",
                "description", "读取订单"));

        assertThat(controller.permissions()).singleElement()
                .satisfies(permission -> {
                    assertThat(permission).containsEntry("code", "order:read")
                            .containsEntry("resource", "order")
                            .containsEntry("action", "read")
                            .containsEntry("description", "读取订单");
                });
    }

    @Test
    @DisplayName("createPermission 缺 resource/action 报 SCIM_BAD_REQUEST")
    void createPermissionRequiresResourceAndAction() {
        assertThatThrownBy(() -> controller.createPermission(Map.of("resource", "order")))
                .isInstanceOfSatisfying(IamException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(IamErrorCode.SCIM_BAD_REQUEST));
        assertThatThrownBy(() -> controller.createPermission(Map.of("action", "read")))
                .isInstanceOfSatisfying(IamException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(IamErrorCode.SCIM_BAD_REQUEST));
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("replaceUserRoles 校验用户与角色并重排直接角色")
    void replaceUserRolesReassigns() {
        users.create(IamUser.create("u-1", "default", "alice", "Alice", null, true), "hash");
        repository.createRole(IamRole.create("r-1", "default", "approver", "审批人", "", null,
                false));
        repository.createRole(IamRole.create("r-2", "default", "auditor", "审计", "", null,
                false));
        repository.assignRoleToUser("u-1", "r-1", java.time.Instant.now(), "seed");

        Map<String, Object> replaced = controller.replaceUserRoles("u-1", Map.of(
                "roles", List.of("r-2")));

        assertThat((List<Object>) replaced.get("roles")).containsExactly("r-2");
        assertThat(controller.userRoles("u-1").get("roles")).isEqualTo(
                repository.findRoleIdsForUser("u-1"));
        assertThat(repository.findRoleIdsForUser("u-1")).containsExactly("r-2");
        verify(authorization).invalidate();
    }

    @Test
    @DisplayName("replaceUserRoles 用户不存在报 USER_NOT_FOUND")
    void replaceUserRolesRequiresExistingUser() {
        assertThatThrownBy(() -> controller.replaceUserRoles("ghost", Map.of(
                "roles", List.of("r-1"))))
                .isInstanceOfSatisfying(IamException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(IamErrorCode.USER_NOT_FOUND));
    }

    @Test
    @DisplayName("replaceUserRoles 角色不存在报 ROLE_NOT_FOUND")
    void replaceUserRolesRequiresExistingRole() {
        users.create(IamUser.create("u-1", "default", "alice", "Alice", null, true), "hash");

        assertThatThrownBy(() -> controller.replaceUserRoles("u-1", Map.of(
                "roles", List.of("r-missing"))))
                .isInstanceOfSatisfying(IamException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(IamErrorCode.ROLE_NOT_FOUND));
    }

    @Test
    @DisplayName("组角色分配与解除")
    void assignAndUnassignGroupRole() {
        repository.createRole(IamRole.create("r-1", "default", "approver", "审批人", "", null,
                false));

        assertThat(controller.assignGroupRole("g-1", "r-1"))
                .containsEntry("groupId", "g-1")
                .containsEntry("roleId", "r-1");
        assertThat(repository.findRoleIdsForGroup("g-1")).containsExactly("r-1");

        assertThat(controller.unassignGroupRole("g-1", "r-1"))
                .containsEntry("groupId", "g-1")
                .containsEntry("roleId", "r-1");
        assertThat(repository.findRoleIdsForGroup("g-1")).isEmpty();
        verify(authorization, org.mockito.Mockito.times(2)).invalidate();
    }
}
