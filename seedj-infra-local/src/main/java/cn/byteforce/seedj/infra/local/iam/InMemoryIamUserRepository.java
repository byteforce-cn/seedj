package cn.byteforce.seedj.infra.local.iam;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.IamPage;
import cn.byteforce.seedj.iam.IamUser;
import cn.byteforce.seedj.iam.IamUserQuery;
import cn.byteforce.seedj.iam.spi.IamUserRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe in-memory user store for tests and local development. */
public class InMemoryIamUserRepository implements IamUserRepository {

    private record Entry(IamUser user, String passwordHash, Instant passwordChangedAt) {
    }

    private final Map<String, Entry> byId = new ConcurrentHashMap<>();
    private final Map<String, String> idByLogin = new ConcurrentHashMap<>();

    @Override
    public Optional<IamUser> findById(String id) {
        Entry entry = byId.get(id);
        return entry == null ? Optional.empty() : Optional.of(entry.user());
    }

    @Override
    public Optional<IamUser> findByUserName(String tenantId, String userName) {
        if (userName == null) {
            return Optional.empty();
        }
        String key = loginKey(tenantId, userName);
        String id = idByLogin.get(key);
        return id == null ? Optional.empty() : findById(id);
    }

    @Override
    public IamPage<IamUser> find(IamUserQuery query) {
        List<IamUser> matches = new ArrayList<>();
        for (Entry entry : byId.values()) {
            IamUser user = entry.user();
            if (!user.tenantId().equals(query.tenantId())) {
                continue;
            }
            if (MemoryScimFilters.matches(query.filter(), attributes(user))) {
                matches.add(user);
            }
        }
        matches.sort(Comparator.comparing(IamUser::userName, String.CASE_INSENSITIVE_ORDER));
        return IamPage.of(matches, query.startIndex(), query.count());
    }

    @Override
    public synchronized IamUser create(IamUser user, String passwordHash) {
        String key = loginKey(user.tenantId(), user.userName());
        if (byId.containsKey(user.id()) || idByLogin.containsKey(key)) {
            throw new IamException(IamErrorCode.USER_CONFLICT,
                    "user already exists: " + user.userName());
        }
        Entry entry = new Entry(user, passwordHash, Instant.now());
        byId.put(user.id(), entry);
        idByLogin.put(key, user.id());
        return user;
    }

    @Override
    public synchronized IamUser update(IamUser user) {
        Entry current = byId.get(user.id());
        if (current == null) {
            throw new IamException(IamErrorCode.USER_NOT_FOUND, "user not found: " + user.id());
        }
        if (current.user().version() != user.version()) {
            throw new IamException(IamErrorCode.CONFLICT,
                    "user version conflict: expected " + current.user().version());
        }
        String newKey = loginKey(user.tenantId(), user.userName());
        String owner = idByLogin.get(newKey);
        if (owner != null && !owner.equals(user.id())) {
            throw new IamException(IamErrorCode.USER_CONFLICT,
                    "user already exists: " + user.userName());
        }
        IamUser updated = user.withVersion(user.version() + 1, Instant.now());
        byId.put(user.id(), new Entry(updated, current.passwordHash(), current.passwordChangedAt()));
        idByLogin.remove(loginKey(current.user().tenantId(), current.user().userName()));
        idByLogin.put(newKey, user.id());
        return updated;
    }

    @Override
    public synchronized void delete(String id) {
        Entry removed = byId.remove(id);
        if (removed != null) {
            idByLogin.remove(loginKey(removed.user().tenantId(), removed.user().userName()));
        }
    }

    @Override
    public Optional<String> findPasswordHash(String userId) {
        Entry entry = byId.get(userId);
        return entry == null ? Optional.empty() : Optional.ofNullable(entry.passwordHash());
    }

    @Override
    public synchronized void updatePassword(String userId, String passwordHash, Instant changedAt) {
        Entry entry = byId.get(userId);
        if (entry == null) {
            throw new IamException(IamErrorCode.USER_NOT_FOUND, "user not found: " + userId);
        }
        byId.put(userId, new Entry(entry.user(), passwordHash,
                changedAt == null ? Instant.now() : changedAt));
    }

    @Override
    public long count() {
        return byId.size();
    }

    private static String loginKey(String tenantId, String userName) {
        String tenant = tenantId == null || tenantId.isBlank() ? IamUser.DEFAULT_TENANT : tenantId;
        return tenant + "\u0000" + userName.toLowerCase(Locale.ROOT);
    }

    private static Map<String, Object> attributes(IamUser user) {
        Map<String, Object> attributes = new LinkedHashMap<>(user.attributes());
        attributes.put("id", user.id());
        attributes.put("userName", user.userName());
        attributes.put("externalId", user.externalId());
        attributes.put("displayName", user.displayName());
        attributes.put("active", user.active());
        attributes.put("emails.value", user.email());
        attributes.put("email", user.email());
        return attributes;
    }
}
