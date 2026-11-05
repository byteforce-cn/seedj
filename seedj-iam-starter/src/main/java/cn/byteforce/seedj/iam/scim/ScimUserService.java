package cn.byteforce.seedj.iam.scim;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.IamGroup;
import cn.byteforce.seedj.iam.IamPage;
import cn.byteforce.seedj.iam.IamUser;
import cn.byteforce.seedj.iam.IamUserQuery;
import cn.byteforce.seedj.iam.config.IamProperties;
import cn.byteforce.seedj.iam.identity.IamIdentityService;
import cn.byteforce.seedj.iam.spi.IamGroupRepository;
import cn.byteforce.seedj.iam.spi.IamPasswordHasher;
import cn.byteforce.seedj.iam.spi.IamUserRepository;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.iam.totp.Base32;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** SCIM User use cases. It reuses the IAM user store instead of a second model. */
public class ScimUserService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final IamUserRepository users;
    private final IamGroupRepository groups;
    private final IamPasswordHasher hasher;
    private final IamIdentityService identities;
    private final IamProperties properties;
    private final IdGenerator<String> idGenerator;
    private final String basePath;

    public ScimUserService(IamUserRepository users, IamGroupRepository groups,
                           IamPasswordHasher hasher, IamIdentityService identities,
                           IamProperties properties, IdGenerator<String> idGenerator) {
        this.users = users;
        this.groups = groups;
        this.hasher = hasher;
        this.identities = identities;
        this.properties = properties;
        this.idGenerator = idGenerator;
        this.basePath = properties.scim().basePath();
    }

    public Map<String, Object> get(String id) {
        return map(require(id));
    }

    public IamUser require(String id) {
        return users.findById(id).orElseThrow(
                () -> new IamException(IamErrorCode.USER_NOT_FOUND, "user not found: " + id));
    }

    public Map<String, Object> create(Map<String, Object> body) {
        String userName = ScimSupport.string(body.get("userName"));
        if (userName == null || userName.isBlank()) {
            throw new IamException(IamErrorCode.SCIM_BAD_REQUEST, "userName is required");
        }
        Draft draft = Draft.from(body);
        IamUser user = IamUser.create(idGenerator.id(), properties.tenantId(), userName, draft.displayName,
                draft.email, draft.active);
        user = user.withExternalId(draft.externalId)
                .withProfile(draft.displayName, draft.email, draft.attributes());
        String passwordHash = draft.password == null || draft.password.isBlank()
                ? hasher.encode(randomPassword()) : hasher.encode(draft.password);
        IamUser created = users.create(user, passwordHash);
        return map(created);
    }

    public Map<String, Object> update(String id, Map<String, Object> body, String ifMatch) {
        IamUser current = require(id);
        ScimSupport.checkVersion(ScimSupport.parseVersion(ifMatch), current.version());
        Draft draft = Draft.from(body);
        IamUser updated = apply(current, draft);
        updated = users.update(updated);
        if (!updated.active() && current.active()) {
            identities.revokeSessions(updated);
        }
        if (draft.password != null && !draft.password.isBlank()) {
            users.updatePassword(id, hasher.encode(draft.password), Instant.now());
            identities.revokeSessions(updated);
            updated = require(id);
        }
        return map(updated);
    }

    public Map<String, Object> patch(String id, Object body, String ifMatch) {
        IamUser current = require(id);
        ScimSupport.checkVersion(ScimSupport.parseVersion(ifMatch), current.version());
        Draft draft = Draft.from(current);
        List<ScimPatchOperation> operations = ScimPatchParser.parse(body);
        for (ScimPatchOperation operation : operations) {
            draft.apply(operation);
        }
        IamUser updated = apply(current, draft);
        updated = users.update(updated);
        if (!updated.active() && current.active()) {
            identities.revokeSessions(updated);
        }
        if (draft.password != null && !draft.password.isBlank()) {
            users.updatePassword(id, hasher.encode(draft.password), Instant.now());
            identities.revokeSessions(updated);
            updated = require(id);
        }
        return map(updated);
    }

    public void delete(String id, String ifMatch) {
        IamUser current = require(id);
        ScimSupport.checkVersion(ScimSupport.parseVersion(ifMatch), current.version());
        identities.deleteUser(id);
    }

    public Map<String, Object> list(String filter, int startIndex, int count) {
        IamPage<IamUser> page = users.find(new IamUserQuery(properties.tenantId(),
                ScimFilterParser.parse(filter), startIndex, count));
        List<Map<String, Object>> resources = page.items().stream().map(this::map).toList();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("schemas", List.of(ScimSupport.LIST_SCHEMA));
        response.put("totalResults", page.totalResults());
        response.put("startIndex", page.startIndex());
        response.put("itemsPerPage", page.itemsPerPage());
        response.put("Resources", resources);
        return response;
    }

    public Map<String, Object> map(IamUser user) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemas", List.of(ScimSupport.USER_SCHEMA));
        value.put("id", user.id());
        if (user.externalId() != null) {
            value.put("externalId", user.externalId());
        }
        value.put("userName", user.userName());
        Map<String, Object> name = new LinkedHashMap<>();
        Object given = user.attributes().get("givenName");
        Object family = user.attributes().get("familyName");
        if (given != null) {
            name.put("givenName", given);
        }
        if (family != null) {
            name.put("familyName", family);
        }
        if (user.displayName() != null) {
            name.put("formatted", user.displayName());
            value.put("displayName", user.displayName());
        }
        if (!name.isEmpty()) {
            value.put("name", name);
        }
        if (user.email() != null) {
            value.put("emails", List.of(Map.of("value", user.email(), "primary", true)));
        }
        value.put("active", user.active());
        List<Map<String, Object>> memberships = new ArrayList<>();
        for (String groupId : groups.findGroupIdsForUser(user.id())) {
            groups.findById(groupId).ifPresent(group -> memberships.add(groupRef(group)));
        }
        if (!memberships.isEmpty()) {
            value.put("groups", memberships);
        }
        value.put("meta", ScimSupport.meta("User", basePath + "/Users/" + user.id(),
                user.version(), user.createdAt(), user.updatedAt()));
        return value;
    }

    private Map<String, Object> groupRef(IamGroup group) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("value", group.id());
        ref.put("display", group.displayName());
        ref.put("$ref", basePath + "/Groups/" + group.id());
        return ref;
    }

    private IamUser apply(IamUser current, Draft draft) {
        if (draft.userName == null || draft.userName.isBlank()) {
            throw new IamException(IamErrorCode.SCIM_BAD_REQUEST, "userName is required");
        }
        IamUser user = current.withExternalId(draft.externalId)
                .withProfile(draft.displayName, draft.email, draft.attributes())
                .withActive(draft.active);
        if (!draft.userName.equals(current.userName())) {
            user = new IamUser(user.id(), user.tenantId(), draft.userName, user.externalId(),
                    user.displayName(), user.email(), user.active(), user.attributes(),
                    user.version(), user.createdAt(), user.updatedAt());
        }
        return user;
    }

    private static String randomPassword() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base32.encode(bytes);
    }

    private static final class Draft {
        private String userName;
        private String externalId;
        private String displayName;
        private String givenName;
        private String familyName;
        private String email;
        private boolean active = true;
        private String password;

        static Draft from(IamUser user) {
            Draft draft = new Draft();
            draft.userName = user.userName();
            draft.externalId = user.externalId();
            draft.displayName = user.displayName();
            draft.givenName = ScimSupport.string(user.attributes().get("givenName"));
            draft.familyName = ScimSupport.string(user.attributes().get("familyName"));
            draft.email = user.email();
            draft.active = user.active();
            return draft;
        }

        static Draft from(Map<String, Object> body) {
            Draft draft = new Draft();
            draft.userName = ScimSupport.string(body.get("userName"));
            draft.externalId = ScimSupport.string(body.get("externalId"));
            draft.displayName = ScimSupport.string(body.get("displayName"));
            draft.active = ScimSupport.bool(body.get("active"), true);
            draft.password = ScimSupport.string(body.get("password"));
            Object name = body.get("name");
            if (name instanceof Map<?, ?> map) {
                draft.givenName = ScimSupport.string(map.get("givenName"));
                draft.familyName = ScimSupport.string(map.get("familyName"));
                if (draft.displayName == null) {
                    draft.displayName = ScimSupport.string(map.get("formatted"));
                }
            }
            Object emails = body.get("emails");
            if (emails instanceof List<?> list && !list.isEmpty()) {
                Object first = list.get(0);
                if (first instanceof Map<?, ?> map) {
                    draft.email = ScimSupport.string(map.get("value"));
                }
            } else if (emails instanceof Map<?, ?> map) {
                draft.email = ScimSupport.string(map.get("value"));
            }
            if (draft.displayName == null) {
                draft.displayName = join(draft.givenName, draft.familyName);
            }
            return draft;
        }

        void apply(ScimPatchOperation operation) {
            if (operation.path() == null) {
                if (operation.value() instanceof Map<?, ?> map) {
                    map.forEach((key, value) -> set(String.valueOf(key), value));
                    return;
                }
                throw new IamException(IamErrorCode.SCIM_PATCH_INVALID,
                        "PATCH without path requires an object value");
            }
            String path = operation.path();
            if ("remove".equals(operation.op())) {
                set(path, null);
            } else {
                set(path, operation.value());
            }
        }

        private void set(String path, Object value) {
            switch (path) {
                case "userName" -> userName = ScimSupport.string(value);
                case "externalId" -> externalId = ScimSupport.string(value);
                case "displayName" -> displayName = ScimSupport.string(value);
                case "name" -> {
                    if (value instanceof Map<?, ?> map) {
                        givenName = ScimSupport.string(map.get("givenName"));
                        familyName = ScimSupport.string(map.get("familyName"));
                        displayName = ScimSupport.string(map.get("formatted"));
                    }
                }
                case "name.givenName" -> givenName = ScimSupport.string(value);
                case "name.familyName" -> familyName = ScimSupport.string(value);
                case "emails", "emails.value" -> email = emailValue(value);
                case "active" -> active = ScimSupport.bool(value, active);
                case "password" -> password = ScimSupport.string(value);
                default -> throw new IamException(IamErrorCode.SCIM_PATCH_INVALID,
                        "unsupported PATCH path: " + path);
            }
            if (displayName == null) {
                displayName = join(givenName, familyName);
            }
        }

        private static String emailValue(Object value) {
            if (value instanceof List<?> list && !list.isEmpty()) {
                return emailValue(list.get(0));
            }
            if (value instanceof Map<?, ?> map) {
                return ScimSupport.string(map.get("value"));
            }
            return ScimSupport.string(value);
        }

        Map<String, Object> attributes() {
            Map<String, Object> attributes = new LinkedHashMap<>();
            if (givenName != null) {
                attributes.put("givenName", givenName);
            }
            if (familyName != null) {
                attributes.put("familyName", familyName);
            }
            return attributes;
        }
    }

    private static String join(String given, String family) {
        if (given == null && family == null) {
            return null;
        }
        return ((given == null ? "" : given) + " " + (family == null ? "" : family)).trim();
    }
}
