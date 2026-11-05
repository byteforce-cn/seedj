package cn.byteforce.seedj.iam.spi;

import cn.byteforce.seedj.iam.IamPermission;
import cn.byteforce.seedj.iam.IamRole;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** RBAC roles, permissions, assignments, and inheritance. */
public interface IamAuthorizationRepository {

    IamRole createRole(IamRole role);

    IamRole updateRole(IamRole role);

    void deleteRole(String roleId);

    Optional<IamRole> findRole(String roleId);

    Optional<IamRole> findRoleByCode(String tenantId, String code);

    List<IamRole> findRoles(String tenantId);

    IamPermission savePermission(IamPermission permission);

    List<IamPermission> findPermissions(String tenantId);

    void grantPermission(String roleId, String permissionCode);

    void revokePermission(String roleId, String permissionCode);

    Set<String> findPermissionCodes(Set<String> roleIds);

    void assignRoleToUser(String userId, String roleId, Instant assignedAt, String assignedBy);

    void unassignRoleFromUser(String userId, String roleId);

    Set<String> findRoleIdsForUser(String userId);

    void assignRoleToGroup(String groupId, String roleId);

    void unassignRoleFromGroup(String groupId, String roleId);

    Set<String> findRoleIdsForGroup(String groupId);

    /** Returns the given role ids plus all ancestors (cycle-safe). */
    Set<String> findRoleIdsWithAncestors(Set<String> roleIds);

    long currentEpoch(String tenantId);

    void bumpEpoch(String tenantId);
}
