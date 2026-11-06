package cn.byteforce.seedj.infra.local.iam;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.IamGroup;
import cn.byteforce.seedj.iam.IamGroupQuery;
import cn.byteforce.seedj.iam.IamPage;
import cn.byteforce.seedj.iam.spi.IamGroupRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe in-memory SCIM group store. */
public class InMemoryIamGroupRepository implements IamGroupRepository {

    private final Map<String, IamGroup> groups = new ConcurrentHashMap<>();

    @Override
    public Optional<IamGroup> findById(String id) {
        return Optional.ofNullable(groups.get(id));
    }

    @Override
    public Optional<IamGroup> findByDisplayName(String tenantId, String displayName) {
        return groups.values().stream()
                .filter(group -> group.tenantId().equals(tenantId))
                .filter(group -> group.displayName().equalsIgnoreCase(displayName))
                .findFirst();
    }

    @Override
    public IamPage<IamGroup> find(IamGroupQuery query) {
        List<IamGroup> matches = new ArrayList<>();
        for (IamGroup group : groups.values()) {
            if (group.tenantId().equals(query.tenantId())
                    && MemoryScimFilters.matches(query.filter(), attributes(group))) {
                matches.add(group);
            }
        }
        matches.sort(Comparator.comparing(IamGroup::displayName, String.CASE_INSENSITIVE_ORDER));
        return IamPage.of(matches, query.startIndex(), query.count());
    }

    @Override
    public synchronized IamGroup create(IamGroup group) {
        boolean duplicate = groups.values().stream()
                .anyMatch(existing -> existing.tenantId().equals(group.tenantId())
                        && existing.displayName().equalsIgnoreCase(group.displayName()));
        if (duplicate || groups.containsKey(group.id())) {
            throw new IamException(IamErrorCode.GROUP_CONFLICT,
                    "group already exists: " + group.displayName());
        }
        groups.put(group.id(), group);
        return group;
    }

    @Override
    public synchronized IamGroup update(IamGroup group) {
        IamGroup current = groups.get(group.id());
        if (current == null) {
            throw new IamException(IamErrorCode.GROUP_NOT_FOUND, "group not found: " + group.id());
        }
        if (current.version() != group.version()) {
            throw new IamException(IamErrorCode.CONFLICT,
                    "group version conflict: expected " + current.version());
        }
        boolean duplicate = groups.values().stream()
                .anyMatch(existing -> !existing.id().equals(group.id())
                        && existing.tenantId().equals(group.tenantId())
                        && existing.displayName().equalsIgnoreCase(group.displayName()));
        if (duplicate) {
            throw new IamException(IamErrorCode.GROUP_CONFLICT,
                    "group already exists: " + group.displayName());
        }
        IamGroup updated = group.withVersion(group.version() + 1, Instant.now());
        groups.put(group.id(), updated);
        return updated;
    }

    @Override
    public synchronized void delete(String id) {
        groups.remove(id);
    }

    @Override
    public Set<String> findMemberIds(String groupId) {
        IamGroup group = groups.get(groupId);
        return group == null ? Set.of() : group.memberIds();
    }

    @Override
    public Set<String> findGroupIdsForUser(String userId) {
        Set<String> result = new LinkedHashSet<>();
        for (IamGroup group : groups.values()) {
            if (group.memberIds().contains(userId)) {
                result.add(group.id());
            }
        }
        return result;
    }

    @Override
    public synchronized void addMember(String groupId, String userId) {
        IamGroup group = groups.get(groupId);
        if (group == null) {
            throw new IamException(IamErrorCode.GROUP_NOT_FOUND, "group not found: " + groupId);
        }
        Set<String> members = group.mutableMembers();
        if (members.add(userId)) {
            groups.put(groupId, new IamGroup(group.id(), group.tenantId(), group.displayName(),
                    group.externalId(), members, group.version() + 1, group.createdAt(),
                    Instant.now()));
        }
    }

    @Override
    public synchronized void removeMember(String groupId, String userId) {
        IamGroup group = groups.get(groupId);
        if (group == null) {
            return;
        }
        Set<String> members = group.mutableMembers();
        if (members.remove(userId)) {
            groups.put(groupId, new IamGroup(group.id(), group.tenantId(), group.displayName(),
                    group.externalId(), members, group.version() + 1, group.createdAt(),
                    Instant.now()));
        }
    }

    private static Map<String, Object> attributes(IamGroup group) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("id", group.id());
        attributes.put("displayName", group.displayName());
        attributes.put("externalId", group.externalId());
        attributes.put("members.value", group.memberIds());
        return attributes;
    }
}
