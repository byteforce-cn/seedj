package cn.byteforce.seedj.starter.iam.scim;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** SCIM 2.0 core User/Group endpoints plus discovery. */
@RestController
@RequestMapping(path = "${seedj.iam.scim.base-path:/scim/v2}", produces = ScimSupport.CONTENT_TYPE)
public class ScimController {

    private static final MediaType SCIM = MediaType.parseMediaType(ScimSupport.CONTENT_TYPE);

    private final ScimUserService users;
    private final ScimGroupService groups;
    private final String basePath;

    public ScimController(
            ScimUserService users,
            ScimGroupService groups,
            cn.byteforce.seedj.starter.iam.IamProperties properties) {
        this.users = users;
        this.groups = groups;
        this.basePath = properties.scim().basePath();
    }

    @GetMapping("/ServiceProviderConfig")
    public Map<String, Object> serviceProviderConfig() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig"));
        body.put("patch", Map.of("supported", true));
        body.put("bulk", Map.of("supported", false, "maxOperations", 0, "maxPayloadSize", 0));
        body.put("filter", Map.of("supported", true, "maxResults", 1000));
        body.put("changePassword", Map.of("supported", true));
        body.put("sort", Map.of("supported", false));
        body.put("etag", Map.of("supported", true));
        body.put(
                "authenticationSchemes",
                List.of(
                        Map.of(
                                "type", "oauthbearertoken",
                                "name", "OAuth Bearer Token",
                                "description", "Bearer token with SCIM scopes",
                                "primary", true)));
        return body;
    }

    @GetMapping("/Schemas")
    public Map<String, Object> schemas() {
        return Map.of(
                "schemas",
                List.of("urn:ietf:params:scim:api:messages:2.0:ListResponse"),
                "totalResults",
                2,
                "startIndex",
                1,
                "itemsPerPage",
                2,
                "Resources",
                List.of(
                        schema(
                                ScimSupport.USER_SCHEMA,
                                "User",
                                List.of(
                                        "userName",
                                        "externalId",
                                        "name",
                                        "displayName",
                                        "emails",
                                        "active",
                                        "password",
                                        "groups")),
                        schema(
                                ScimSupport.GROUP_SCHEMA,
                                "Group",
                                List.of("displayName", "externalId", "members"))));
    }

    @GetMapping("/ResourceTypes")
    public Map<String, Object> resourceTypes() {
        return Map.of(
                "schemas",
                List.of("urn:ietf:params:scim:api:messages:2.0:ListResponse"),
                "totalResults",
                2,
                "startIndex",
                1,
                "itemsPerPage",
                2,
                "Resources",
                List.of(
                        resourceType("User", ScimSupport.USER_SCHEMA, "/Users"),
                        resourceType("Group", ScimSupport.GROUP_SCHEMA, "/Groups")));
    }

    @GetMapping("/Users")
    public Map<String, Object> listUsers(
            @RequestParam(value = "filter", required = false) String filter,
            @RequestParam(value = "startIndex", defaultValue = "1") int startIndex,
            @RequestParam(value = "count", defaultValue = "100") int count) {
        return users.list(filter, startIndex, count);
    }

    @PostMapping(
            path = "/Users",
            consumes = {ScimSupport.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<Map<String, Object>> createUser(@RequestBody Map<String, Object> body) {
        Map<String, Object> created = users.create(body);
        String id = String.valueOf(created.get("id"));
        return ResponseEntity.status(HttpStatus.CREATED)
                .contentType(SCIM)
                .header(HttpHeaders.LOCATION, basePath + "/Users/" + id)
                .header(HttpHeaders.ETAG, etag(created))
                .body(created);
    }

    @GetMapping("/Users/{id}")
    public ResponseEntity<Map<String, Object>> getUser(@PathVariable("id") String id) {
        Map<String, Object> user = users.get(id);
        return ResponseEntity.ok()
                .contentType(SCIM)
                .header(HttpHeaders.ETAG, etag(user))
                .body(user);
    }

    @PutMapping(
            path = "/Users/{id}",
            consumes = {ScimSupport.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<Map<String, Object>> updateUser(
            @PathVariable("id") String id,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        Map<String, Object> updated = users.update(id, body, ifMatch);
        return ResponseEntity.ok()
                .contentType(SCIM)
                .header(HttpHeaders.ETAG, etag(updated))
                .body(updated);
    }

    @PatchMapping(
            path = "/Users/{id}",
            consumes = {ScimSupport.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<Map<String, Object>> patchUser(
            @PathVariable("id") String id,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        Map<String, Object> updated = users.patch(id, body, ifMatch);
        return ResponseEntity.ok()
                .contentType(SCIM)
                .header(HttpHeaders.ETAG, etag(updated))
                .body(updated);
    }

    @DeleteMapping("/Users/{id}")
    public ResponseEntity<Void> deleteUser(
            @PathVariable("id") String id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        users.delete(id, ifMatch);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/Groups")
    public Map<String, Object> listGroups(
            @RequestParam(value = "filter", required = false) String filter,
            @RequestParam(value = "startIndex", defaultValue = "1") int startIndex,
            @RequestParam(value = "count", defaultValue = "100") int count) {
        return groups.list(filter, startIndex, count);
    }

    @PostMapping(
            path = "/Groups",
            consumes = {ScimSupport.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<Map<String, Object>> createGroup(@RequestBody Map<String, Object> body) {
        Map<String, Object> created = groups.create(body);
        String id = String.valueOf(created.get("id"));
        return ResponseEntity.status(HttpStatus.CREATED)
                .contentType(SCIM)
                .header(HttpHeaders.LOCATION, basePath + "/Groups/" + id)
                .header(HttpHeaders.ETAG, etag(created))
                .body(created);
    }

    @GetMapping("/Groups/{id}")
    public ResponseEntity<Map<String, Object>> getGroup(@PathVariable("id") String id) {
        Map<String, Object> group = groups.get(id);
        return ResponseEntity.ok()
                .contentType(SCIM)
                .header(HttpHeaders.ETAG, etag(group))
                .body(group);
    }

    @PutMapping(
            path = "/Groups/{id}",
            consumes = {ScimSupport.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<Map<String, Object>> updateGroup(
            @PathVariable("id") String id,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        Map<String, Object> updated = groups.update(id, body, ifMatch);
        return ResponseEntity.ok()
                .contentType(SCIM)
                .header(HttpHeaders.ETAG, etag(updated))
                .body(updated);
    }

    @PatchMapping(
            path = "/Groups/{id}",
            consumes = {ScimSupport.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<Map<String, Object>> patchGroup(
            @PathVariable("id") String id,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        Map<String, Object> updated = groups.patch(id, body, ifMatch);
        return ResponseEntity.ok()
                .contentType(SCIM)
                .header(HttpHeaders.ETAG, etag(updated))
                .body(updated);
    }

    @DeleteMapping("/Groups/{id}")
    public ResponseEntity<Void> deleteGroup(
            @PathVariable("id") String id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        groups.delete(id, ifMatch);
        return ResponseEntity.noContent().build();
    }

    private static String etag(Map<String, Object> resource) {
        Object meta = resource.get("meta");
        if (meta instanceof Map<?, ?> map && map.get("version") != null) {
            return String.valueOf(map.get("version"));
        }
        return null;
    }

    private static Map<String, Object> schema(String id, String name, List<String> attributes) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("id", id);
        schema.put("name", name);
        schema.put("description", name + " resource");
        schema.put(
                "attributes",
                attributes.stream()
                        .map(
                                attribute ->
                                        Map.of(
                                                "name",
                                                attribute,
                                                "type",
                                                "string",
                                                "multiValued",
                                                false,
                                                "required",
                                                "userName".equals(attribute)
                                                        || "displayName".equals(attribute)))
                        .toList());
        return schema;
    }

    private static Map<String, Object> resourceType(String name, String schema, String endpoint) {
        return Map.of(
                "id", name,
                "name", name,
                "endpoint", endpoint,
                "description", name + " resource",
                "schema", schema,
                "schemaExtensions", List.of());
    }
}
