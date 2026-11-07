package cn.byteforce.seedj.starter.iam.identity;

import cn.byteforce.seedj.iam.IamRole;
import cn.byteforce.seedj.iam.IamSubject;
import cn.byteforce.seedj.iam.IamSubjectAuthorization;
import cn.byteforce.seedj.iam.IamUser;
import cn.byteforce.seedj.iam.spi.IamAuthorizationRepository;
import cn.byteforce.seedj.iam.spi.IamGroupRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Computes effective authorization for a user and caches it for a short TTL, keyed by the
 * cross-instance {@code iam_authz_epoch} value.
 */
public class IamAuthorizationService {

    private record Cached(IamSubjectAuthorization authorization, long epoch, Instant expiresAt) {}

    private final IamAuthorizationRepository repository;
    private final IamGroupRepository groups;
    private final Duration ttl;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public IamAuthorizationService(
            IamAuthorizationRepository repository, IamGroupRepository groups, Duration ttl) {
        this.repository = repository;
        this.groups = groups;
        this.ttl = ttl == null || ttl.isZero() || ttl.isNegative() ? Duration.ofSeconds(30) : ttl;
    }

    public IamSubjectAuthorization effective(String tenantId, String userId) {
        long epoch = repository.currentEpoch(tenantId);
        String key = tenantId + "\u0000" + userId;
        Instant now = Instant.now();
        Cached cached = cache.get(key);
        if (cached != null && cached.epoch() == epoch && now.isBefore(cached.expiresAt())) {
            return cached.authorization();
        }

        Set<String> directRoles = repository.findRoleIdsForUser(userId);
        Set<String> groupIds = groups.findGroupIdsForUser(userId);
        Set<String> allRoleIds = new LinkedHashSet<>(directRoles);
        for (String groupId : groupIds) {
            allRoleIds.addAll(repository.findRoleIdsForGroup(groupId));
        }
        Set<String> roleIds = repository.findRoleIdsWithAncestors(allRoleIds);
        Set<String> roleCodes = new LinkedHashSet<>();
        for (String roleId : roleIds) {
            repository.findRole(roleId).map(IamRole::code).ifPresent(roleCodes::add);
        }
        Set<String> permissions = repository.findPermissionCodes(roleIds);
        Set<String> groupNames = new LinkedHashSet<>();
        for (String groupId : groupIds) {
            groups.findById(groupId).ifPresent(group -> groupNames.add(group.displayName()));
        }
        IamSubjectAuthorization authorization =
                new IamSubjectAuthorization(roleCodes, permissions, groupNames);
        if (cache.size() > 10_000) {
            cache.clear();
        }
        cache.put(key, new Cached(authorization, epoch, now.plus(ttl)));
        return authorization;
    }

    public IamSubject subject(IamUser user) {
        IamSubjectAuthorization authorization = effective(user.tenantId(), user.id());
        return new IamSubject(
                user.id(),
                user.userName(),
                user.tenantId(),
                authorization.roles(),
                authorization.permissions(),
                authorization.groups(),
                Set.of("pwd"),
                "urn:seedj:acr:pwd",
                user.attributes());
    }

    public long epoch(String tenantId) {
        return repository.currentEpoch(tenantId);
    }

    public void invalidate() {
        cache.clear();
    }
}
