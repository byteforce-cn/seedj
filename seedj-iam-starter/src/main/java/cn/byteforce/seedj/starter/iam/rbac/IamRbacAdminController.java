package cn.byteforce.seedj.starter.iam.rbac;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.IamPermission;
import cn.byteforce.seedj.iam.IamRole;
import cn.byteforce.seedj.iam.spi.IamAuthorizationRepository;
import cn.byteforce.seedj.iam.spi.IamUserRepository;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.starter.iam.IamProperties;
import cn.byteforce.seedj.starter.iam.identity.IamAuthorizationService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Role/permission/assignment management. SCIM remains the user/group API. */
@RestController
@RequestMapping(path = "/iam/admin", produces = MediaType.APPLICATION_JSON_VALUE)
public class IamRbacAdminController {

    private final IamAuthorizationRepository repository;
    private final IamUserRepository users;
    private final IamAuthorizationService authorization;
    private final IamProperties properties;
    private final IdGenerator<String> idGenerator;

    public IamRbacAdminController(
            IamAuthorizationRepository repository,
            IamUserRepository users,
            IamAuthorizationService authorization,
            IamProperties properties,
            IdGenerator<String> idGenerator) {
        this.repository = repository;
        this.users = users;
        this.authorization = authorization;
        this.properties = properties;
        this.idGenerator = idGenerator;
    }

    @GetMapping("/roles")
    public List<Map<String, Object>> roles() {
        return repository.findRoles(properties.tenantId()).stream()
                .map(IamRbacAdminController::role)
                .toList();
    }

    @PostMapping("/roles")
    public Map<String, Object> createRole(@RequestBody Map<String, Object> body) {
        String code = string(body.get("code"));
        if (code == null || code.isBlank()) {
            throw new IamException(IamErrorCode.SCIM_BAD_REQUEST, "role code is required");
        }
        IamRole role =
                IamRole.create(
                        idGenerator.id(),
                        properties.tenantId(),
                        code,
                        string(body.get("name")),
                        string(body.get("description")),
                        string(body.get("parentId")),
                        false);
        IamRole created = repository.createRole(role);
        invalidate();
        return role(created);
    }

    @PutMapping("/roles/{id}")
    public Map<String, Object> updateRole(
            @PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        IamRole current =
                repository
                        .findRole(id)
                        .orElseThrow(
                                () ->
                                        new IamException(
                                                IamErrorCode.ROLE_NOT_FOUND,
                                                "role not found: " + id));
        IamRole updated =
                new IamRole(
                        current.id(),
                        current.tenantId(),
                        body.containsKey("code") ? string(body.get("code")) : current.code(),
                        body.containsKey("name") ? string(body.get("name")) : current.name(),
                        body.containsKey("description")
                                ? string(body.get("description"))
                                : current.description(),
                        body.containsKey("parentId")
                                ? string(body.get("parentId"))
                                : current.parentId(),
                        current.builtIn(),
                        current.version());
        IamRole saved = repository.updateRole(updated);
        invalidate();
        return role(saved);
    }

    @DeleteMapping("/roles/{id}")
    public Map<String, Object> deleteRole(@PathVariable("id") String id) {
        repository.deleteRole(id);
        invalidate();
        return Map.of("deleted", id);
    }

    @GetMapping("/roles/{id}/permissions")
    public Map<String, Object> rolePermissions(@PathVariable("id") String id) {
        return Map.of("roleId", id, "permissions", repository.findPermissionCodes(Set.of(id)));
    }

    @PutMapping("/roles/{id}/permissions")
    public Map<String, Object> replaceRolePermissions(
            @PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        List<String> wanted = strings(body.get("permissions"));
        Set<String> existing = repository.findPermissionCodes(Set.of(id));
        existing.forEach(permission -> repository.revokePermission(id, permission));
        wanted.forEach(permission -> repository.grantPermission(id, permission));
        invalidate();
        return Map.of("roleId", id, "permissions", wanted);
    }

    @GetMapping("/permissions")
    public List<Map<String, Object>> permissions() {
        return repository.findPermissions(properties.tenantId()).stream()
                .map(
                        permission ->
                                Map.<String, Object>of(
                                        "code", permission.code(),
                                        "resource", permission.resource(),
                                        "action", permission.action(),
                                        "description", permission.description()))
                .toList();
    }

    @PostMapping("/permissions")
    public Map<String, Object> createPermission(@RequestBody Map<String, Object> body) {
        String resource = string(body.get("resource"));
        String action = string(body.get("action"));
        if (resource == null || resource.isBlank() || action == null || action.isBlank()) {
            throw new IamException(
                    IamErrorCode.SCIM_BAD_REQUEST, "permission resource and action are required");
        }
        IamPermission permission =
                IamPermission.of(
                        properties.tenantId(), resource, action, string(body.get("description")));
        repository.savePermission(permission);
        invalidate();
        return Map.of(
                "code",
                permission.code(),
                "resource",
                permission.resource(),
                "action",
                permission.action());
    }

    @GetMapping("/users/{userId}/roles")
    public Map<String, Object> userRoles(@PathVariable("userId") String userId) {
        return Map.of("userId", userId, "roles", repository.findRoleIdsForUser(userId));
    }

    @PutMapping("/users/{userId}/roles")
    public Map<String, Object> replaceUserRoles(
            @PathVariable("userId") String userId, @RequestBody Map<String, Object> body) {
        users.findById(userId)
                .orElseThrow(
                        () ->
                                new IamException(
                                        IamErrorCode.USER_NOT_FOUND, "user not found: " + userId));
        List<String> wanted = strings(body.get("roles"));
        Set<String> existing = new LinkedHashSet<>(repository.findRoleIdsForUser(userId));
        existing.forEach(roleId -> repository.unassignRoleFromUser(userId, roleId));
        for (String roleId : wanted) {
            if (repository.findRole(roleId).isEmpty()) {
                throw new IamException(IamErrorCode.ROLE_NOT_FOUND, "role not found: " + roleId);
            }
            repository.assignRoleToUser(userId, roleId, Instant.now(), "admin-api");
        }
        invalidate();
        return Map.of("userId", userId, "roles", wanted);
    }

    @PostMapping("/groups/{groupId}/roles/{roleId}")
    public Map<String, Object> assignGroupRole(
            @PathVariable("groupId") String groupId, @PathVariable("roleId") String roleId) {
        repository.assignRoleToGroup(groupId, roleId);
        invalidate();
        return Map.of("groupId", groupId, "roleId", roleId);
    }

    @DeleteMapping("/groups/{groupId}/roles/{roleId}")
    public Map<String, Object> unassignGroupRole(
            @PathVariable("groupId") String groupId, @PathVariable("roleId") String roleId) {
        repository.unassignRoleFromGroup(groupId, roleId);
        invalidate();
        return Map.of("groupId", groupId, "roleId", roleId);
    }

    private void invalidate() {
        authorization.invalidate();
    }

    private static Map<String, Object> role(IamRole role) {
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("id", role.id());
        value.put("code", role.code());
        value.put("name", role.name());
        value.put("description", role.description());
        value.put("parentId", role.parentId());
        value.put("builtIn", role.builtIn());
        value.put("version", role.version());
        return value;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        list.forEach(item -> result.add(String.valueOf(item)));
        return result;
    }
}
