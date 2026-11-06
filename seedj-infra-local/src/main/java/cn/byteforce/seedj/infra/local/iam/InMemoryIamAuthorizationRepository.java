package cn.byteforce.seedj.infra.local.iam;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.IamPermission;
import cn.byteforce.seedj.iam.IamRole;
import cn.byteforce.seedj.iam.spi.IamAuthorizationRepository;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe in-memory RBAC store. */
public class InMemoryIamAuthorizationRepository implements IamAuthorizationRepository {

    private final Map<String, IamRole> roles = new ConcurrentHashMap<>();
    private final Map<String, IamPermission> permissions = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> rolePermissions = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> userRoles = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> groupRoles = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> epochs = new ConcurrentHashMap<>();

    @Override
    public synchronized IamRole createRole(IamRole role) {
        if (roles.containsKey(role.id()) || findRoleByCode(role.tenantId(), role.code()).isPresent()) {
            throw new IamException(IamErrorCode.ROLE_CONFLICT, "role already exists: " + role.code());
        }
        roles.put(role.id(), role);
        bumpEpoch(role.tenantId());
        return role;
    }

    @Override
    public synchronized IamRole updateRole(IamRole role) {
        IamRole current = roles.get(role.id());
        if (current == null) {
            throw new IamException(IamErrorCode.ROLE_NOT_FOUND, "role not found: " + role.id());
        }
        if (current.version() != role.version()) {
            throw new IamException(IamErrorCode.CONFLICT,
                    "role version conflict: expected " + current.version());
        }
        IamRole updated = new IamRole(role.id(), role.tenantId(), role.code(), role.name(),
                role.description(), role.parentId(), role.builtIn(), role.version() + 1);
        roles.put(updated.id(), updated);
        bumpEpoch(updated.tenantId());
        return updated;
    }

    @Override
    public synchronized void deleteRole(String roleId) {
        IamRole removed = roles.remove(roleId);
        if (removed != null) {
            bumpEpoch(removed.tenantId());
        }
        rolePermissions.remove(roleId);
        userRoles.values().forEach(set -> set.remove(roleId));
        groupRoles.values().forEach(set -> set.remove(roleId));
    }

    @Override
    public Optional<IamRole> findRole(String roleId) {
        return Optional.ofNullable(roles.get(roleId));
    }

    @Override
    public Optional<IamRole> findRoleByCode(String tenantId, String code) {
        return roles.values().stream()
                .filter(role -> role.tenantId().equals(tenantId) && role.code().equals(code))
                .findFirst();
    }

    @Override
    public List<IamRole> findRoles(String tenantId) {
        return roles.values().stream()
                .filter(role -> role.tenantId().equals(tenantId))
                .sorted(java.util.Comparator.comparing(IamRole::code))
                .toList();
    }

    @Override
    public IamPermission savePermission(IamPermission permission) {
        permissions.put(key(permission.tenantId(), permission.code()), permission);
        bumpEpoch(permission.tenantId());
        return permission;
    }

    @Override
    public List<IamPermission> findPermissions(String tenantId) {
        return permissions.values().stream()
                .filter(permission -> permission.tenantId().equals(tenantId))
                .sorted(java.util.Comparator.comparing(IamPermission::code))
                .toList();
    }

    @Override
    public void grantPermission(String roleId, String permissionCode) {
        rolePermissions.computeIfAbsent(roleId, ignored -> ConcurrentHashMap.newKeySet())
                .add(permissionCode);
        bumpForRole(roleId);
    }

    @Override
    public void revokePermission(String roleId, String permissionCode) {
        Set<String> codes = rolePermissions.get(roleId);
        if (codes != null) {
            codes.remove(permissionCode);
        }
        bumpForRole(roleId);
    }

    @Override
    public Set<String> findPermissionCodes(Set<String> roleIds) {
        Set<String> result = new LinkedHashSet<>();
        for (String roleId : roleIds) {
            result.addAll(rolePermissions.getOrDefault(roleId, Set.of()));
        }
        return result;
    }

    @Override
    public void assignRoleToUser(String userId, String roleId, Instant assignedAt, String assignedBy) {
        userRoles.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet()).add(roleId);
        bumpForRole(roleId);
    }

    @Override
    public void unassignRoleFromUser(String userId, String roleId) {
        Set<String> assigned = userRoles.get(userId);
        if (assigned != null) {
            assigned.remove(roleId);
        }
        bumpForRole(roleId);
    }

    @Override
    public Set<String> findRoleIdsForUser(String userId) {
        return Set.copyOf(userRoles.getOrDefault(userId, Set.of()));
    }

    @Override
    public void assignRoleToGroup(String groupId, String roleId) {
        groupRoles.computeIfAbsent(groupId, ignored -> ConcurrentHashMap.newKeySet()).add(roleId);
        bumpForRole(roleId);
    }

    @Override
    public void unassignRoleFromGroup(String groupId, String roleId) {
        Set<String> assigned = groupRoles.get(groupId);
        if (assigned != null) {
            assigned.remove(roleId);
        }
        bumpForRole(roleId);
    }

    @Override
    public Set<String> findRoleIdsForGroup(String groupId) {
        return Set.copyOf(groupRoles.getOrDefault(groupId, Set.of()));
    }

    @Override
    public Set<String> findRoleIdsWithAncestors(Set<String> roleIds) {
        Set<String> result = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(roleIds);
        while (!queue.isEmpty()) {
            String roleId = queue.removeFirst();
            if (!result.add(roleId)) {
                continue;
            }
            IamRole role = roles.get(roleId);
            if (role != null && role.parentId() != null && !role.parentId().isBlank()) {
                queue.addLast(role.parentId());
            }
        }
        return result;
    }

    @Override
    public long currentEpoch(String tenantId) {
        AtomicLong epoch = epochs.get(tenantId);
        return epoch == null ? 0 : epoch.get();
    }

    @Override
    public void bumpEpoch(String tenantId) {
        epochs.computeIfAbsent(tenantId, ignored -> new AtomicLong()).incrementAndGet();
    }

    private void bumpForRole(String roleId) {
        IamRole role = roles.get(roleId);
        if (role != null) {
            bumpEpoch(role.tenantId());
        }
    }

    private static String key(String tenantId, String code) {
        return tenantId + "\u0000" + code;
    }
}
