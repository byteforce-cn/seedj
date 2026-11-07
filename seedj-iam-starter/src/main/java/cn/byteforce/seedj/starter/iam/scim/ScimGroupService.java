package cn.byteforce.seedj.starter.iam.scim;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.IamGroup;
import cn.byteforce.seedj.iam.IamGroupQuery;
import cn.byteforce.seedj.iam.IamPage;
import cn.byteforce.seedj.iam.scim.ScimFilterParser;
import cn.byteforce.seedj.iam.scim.ScimPatchOperation;
import cn.byteforce.seedj.iam.scim.ScimPatchParser;
import cn.byteforce.seedj.iam.scim.ScimPatchPath;
import cn.byteforce.seedj.iam.spi.IamAuthorizationRepository;
import cn.byteforce.seedj.iam.spi.IamGroupRepository;
import cn.byteforce.seedj.iam.spi.IamUserRepository;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.starter.iam.IamProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** SCIM Group use cases, backed by the canonical IAM group store. */
public class ScimGroupService {

    private final IamGroupRepository groups;
    private final IamUserRepository users;
    private final IamProperties properties;
    private final IamAuthorizationRepository authorization;
    private final IdGenerator<String> idGenerator;
    private final String basePath;

    public ScimGroupService(
            IamGroupRepository groups,
            IamUserRepository users,
            IamProperties properties,
            IamAuthorizationRepository authorization,
            IdGenerator<String> idGenerator) {
        this.groups = groups;
        this.users = users;
        this.properties = properties;
        this.authorization = authorization;
        this.idGenerator = idGenerator;
        this.basePath = properties.scim().basePath();
    }

    public IamGroup require(String id) {
        return groups.findById(id)
                .orElseThrow(
                        () ->
                                new IamException(
                                        IamErrorCode.GROUP_NOT_FOUND, "group not found: " + id));
    }

    public Map<String, Object> get(String id) {
        return map(require(id));
    }

    public Map<String, Object> create(Map<String, Object> body) {
        String displayName = ScimSupport.string(body.get("displayName"));
        if (displayName == null || displayName.isBlank()) {
            throw new IamException(IamErrorCode.SCIM_BAD_REQUEST, "displayName is required");
        }
        Set<String> members = memberIds(body.get("members"));
        validateMembers(members);
        IamGroup group =
                IamGroup.create(
                        idGenerator.id(),
                        properties.tenantId(),
                        displayName,
                        ScimSupport.string(body.get("externalId")),
                        members);
        IamGroup created = groups.create(group);
        authorization.bumpEpoch(properties.tenantId());
        return map(created);
    }

    public Map<String, Object> update(String id, Map<String, Object> body, String ifMatch) {
        IamGroup current = require(id);
        ScimSupport.checkVersion(ScimSupport.parseVersion(ifMatch), current.version());
        String displayName = ScimSupport.string(body.get("displayName"));
        if (displayName == null || displayName.isBlank()) {
            displayName = current.displayName();
        }
        Set<String> members =
                body.containsKey("members") ? memberIds(body.get("members")) : current.memberIds();
        validateMembers(members);
        IamGroup updated =
                groups.update(
                        new IamGroup(
                                current.id(),
                                current.tenantId(),
                                displayName,
                                ScimSupport.string(body.get("externalId")),
                                members,
                                current.version(),
                                current.createdAt(),
                                current.updatedAt()));
        authorization.bumpEpoch(properties.tenantId());
        return map(updated);
    }

    public Map<String, Object> patch(String id, Object body, String ifMatch) {
        IamGroup current = require(id);
        ScimSupport.checkVersion(ScimSupport.parseVersion(ifMatch), current.version());
        Draft draft = new Draft(current);
        for (ScimPatchOperation operation : ScimPatchParser.parse(body)) {
            draft.apply(operation);
        }
        if (draft.displayName == null || draft.displayName.isBlank()) {
            throw new IamException(IamErrorCode.SCIM_BAD_REQUEST, "displayName is required");
        }
        validateMembers(draft.members);
        IamGroup updated =
                groups.update(
                        new IamGroup(
                                current.id(),
                                current.tenantId(),
                                draft.displayName,
                                draft.externalId,
                                draft.members,
                                current.version(),
                                current.createdAt(),
                                current.updatedAt()));
        authorization.bumpEpoch(properties.tenantId());
        return map(updated);
    }

    public void delete(String id, String ifMatch) {
        IamGroup current = require(id);
        ScimSupport.checkVersion(ScimSupport.parseVersion(ifMatch), current.version());
        groups.delete(id);
        authorization.bumpEpoch(properties.tenantId());
    }

    public Map<String, Object> list(String filter, int startIndex, int count) {
        IamPage<IamGroup> page =
                groups.find(
                        new IamGroupQuery(
                                properties.tenantId(),
                                ScimFilterParser.parse(filter),
                                startIndex,
                                count));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("schemas", List.of(ScimSupport.LIST_SCHEMA));
        response.put("totalResults", page.totalResults());
        response.put("startIndex", page.startIndex());
        response.put("itemsPerPage", page.itemsPerPage());
        response.put("Resources", page.items().stream().map(this::map).toList());
        return response;
    }

    public Map<String, Object> map(IamGroup group) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemas", List.of(ScimSupport.GROUP_SCHEMA));
        value.put("id", group.id());
        if (group.externalId() != null) {
            value.put("externalId", group.externalId());
        }
        value.put("displayName", group.displayName());
        List<Map<String, Object>> members = new ArrayList<>();
        for (String userId : group.memberIds()) {
            Map<String, Object> member = new LinkedHashMap<>();
            member.put("value", userId);
            users.findById(userId)
                    .ifPresent(
                            user -> {
                                member.put("display", user.userName());
                                member.put("$ref", basePath + "/Users/" + user.id());
                            });
            members.add(member);
        }
        if (!members.isEmpty()) {
            value.put("members", members);
        }
        value.put(
                "meta",
                ScimSupport.meta(
                        "Group",
                        basePath + "/Groups/" + group.id(),
                        group.version(),
                        group.createdAt(),
                        group.updatedAt()));
        return value;
    }

    private void validateMembers(Set<String> members) {
        for (String memberId : members) {
            if (users.findById(memberId).isEmpty()) {
                throw new IamException(
                        IamErrorCode.SCIM_BAD_REQUEST, "member does not exist: " + memberId);
            }
        }
    }

    private static Set<String> memberIds(Object value) {
        Set<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> member : ScimSupport.maps(value)) {
            Object id = member.get("value");
            if (id != null) {
                ids.add(String.valueOf(id));
            }
        }
        if (value instanceof String text && !text.isBlank()) {
            ids.add(text);
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String text && !text.isBlank()) {
                    ids.add(text);
                }
            }
        }
        return ids;
    }

    private static final class Draft {
        private String displayName;
        private String externalId;
        private Set<String> members;

        private Draft(IamGroup group) {
            this.displayName = group.displayName();
            this.externalId = group.externalId();
            this.members = new LinkedHashSet<>(group.memberIds());
        }

        private void apply(ScimPatchOperation operation) {
            if (operation.path() == null) {
                if (operation.value() instanceof Map<?, ?> map) {
                    if (map.containsKey("displayName")) {
                        displayName = ScimSupport.string(map.get("displayName"));
                    }
                    if (map.containsKey("externalId")) {
                        externalId = ScimSupport.string(map.get("externalId"));
                    }
                    if (map.containsKey("members")) {
                        members = memberIds(map.get("members"));
                    }
                    return;
                }
                throw new IamException(
                        IamErrorCode.SCIM_PATCH_INVALID,
                        "PATCH without path requires an object value");
            }
            ScimPatchPath path;
            try {
                path = ScimPatchPath.parse(operation.path());
            } catch (IllegalArgumentException e) {
                throw new IamException(IamErrorCode.SCIM_PATCH_INVALID, e.getMessage());
            }
            if (path == null) {
                throw new IamException(IamErrorCode.SCIM_PATCH_INVALID, "path is required");
            }
            if (path.valueFilter() != null) {
                String memberId = filterValue(path.valueFilter());
                if (memberId == null) {
                    throw new IamException(
                            IamErrorCode.SCIM_PATCH_INVALID,
                            "members path filter must be value eq \"id\"");
                }
                if ("remove".equals(operation.op())) {
                    members.remove(memberId);
                } else if ("add".equals(operation.op())) {
                    members.addAll(memberIds(operation.value()));
                } else {
                    members.remove(memberId);
                    members.addAll(memberIds(operation.value()));
                }
                return;
            }
            switch (path.attribute()) {
                case "displayName" -> {
                    if ("remove".equals(operation.op())) {
                        throw new IamException(
                                IamErrorCode.SCIM_PATCH_INVALID, "displayName cannot be removed");
                    }
                    displayName = ScimSupport.string(operation.value());
                }
                case "externalId" ->
                        externalId =
                                "remove".equals(operation.op())
                                        ? null
                                        : ScimSupport.string(operation.value());
                case "members" -> {
                    if ("remove".equals(operation.op())) {
                        members.clear();
                    } else if ("replace".equals(operation.op())) {
                        members = memberIds(operation.value());
                    } else {
                        members.addAll(memberIds(operation.value()));
                    }
                }
                default ->
                        throw new IamException(
                                IamErrorCode.SCIM_PATCH_INVALID,
                                "unsupported PATCH path: " + path.attribute());
            }
        }

        private static String filterValue(cn.byteforce.seedj.iam.ScimFilter filter) {
            if (filter instanceof cn.byteforce.seedj.iam.ScimFilter.Compare compare
                    && compare.operator() == cn.byteforce.seedj.iam.ScimComparisonOperator.EQ) {
                return String.valueOf(compare.value());
            }
            return null;
        }
    }
}
