package cn.byteforce.seedj.starter.iam.scim;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamUser;
import cn.byteforce.seedj.iam.spi.IamPasswordHasher;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamAuditSink;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamGroupRepository;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamTokenRevoker;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamUserRepository;
import cn.byteforce.seedj.starter.iam.IamProperties;
import cn.byteforce.seedj.starter.iam.identity.IamIdentityService;
import cn.byteforce.seedj.starter.iam.identity.IamPasswordHasherAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;

class ScimUserServiceTest {

    private final InMemoryIamUserRepository users = new InMemoryIamUserRepository();
    private final InMemoryIamGroupRepository groups = new InMemoryIamGroupRepository();
    private final InMemoryIamTokenRevoker revoker = new InMemoryIamTokenRevoker();
    private final IamPasswordHasher hasher = new IamPasswordHasherAdapter(
            PasswordEncoderFactories.createDelegatingPasswordEncoder());
    private final AtomicLong seq = new AtomicLong();
    private final IdGenerator<String> idGenerator = () -> "id-" + seq.incrementAndGet();
    private final IamIdentityService identities = new IamIdentityService(users, hasher, revoker,
            new InMemoryIamAuditSink(), idGenerator);
    private final IamProperties properties = IamProperties.defaults();
    private final ScimUserService service = new ScimUserService(users, groups, hasher, identities,
            properties, idGenerator);

    @Test
    void createRequiresUserName() {
        ScimAssertions.assertIamError(() -> service.create(Map.of("displayName", "Alice")),
                IamErrorCode.SCIM_BAD_REQUEST, "userName is required");
        ScimAssertions.assertIamError(() -> service.create(Map.of("userName", "   ")),
                IamErrorCode.SCIM_BAD_REQUEST, "userName is required");
    }

    @Test
    void createGeneratesPasswordWhenOmittedAndHashesSuppliedOne() {
        Map<String, Object> generated = service.create(Map.of("userName", "alice"));
        Map<String, Object> explicit = service.create(Map.of("userName", "bob",
                "password", "bob-password"));

        String generatedHash = users.findPasswordHash(String.valueOf(generated.get("id")))
                .orElseThrow();
        String explicitHash = users.findPasswordHash(String.valueOf(explicit.get("id")))
                .orElseThrow();
        assertThat(generatedHash).startsWith("{").isNotEqualTo(explicitHash);
        assertThat(hasher.matches("bob-password", explicitHash)).isTrue();
        assertThat(hasher.matches("", generatedHash)).isFalse();
    }

    @Test
    void createMapsNestedNameEmailExternalIdAndActive() {
        Map<String, Object> created = service.create(Map.of(
                "userName", "alice",
                "externalId", "ext-1",
                "active", false,
                "name", Map.of("givenName", "Alice", "familyName", "Ng"),
                "emails", List.of(Map.of("value", "alice@example.com", "primary", true))));

        assertThat(created).containsEntry("userName", "alice")
                .containsEntry("externalId", "ext-1")
                .containsEntry("displayName", "Alice Ng")
                .containsEntry("active", false);
        assertThat(created.get("name")).asInstanceOf(MAP).containsEntry("givenName", "Alice")
                .containsEntry("familyName", "Ng");
        assertThat((List<?>) created.get("emails")).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) created.get("meta");
        assertThat(meta).containsEntry("resourceType", "User")
                .containsEntry("location", "/scim/v2/Users/" + created.get("id"))
                .containsEntry("version", "W/\"0\"");
        assertThat(meta.get("created")).isNotNull();
        assertThat(meta.get("lastModified")).isNotNull();
    }

    @Test
    void createPrefersFormattedNameAndAcceptsSingleEmailObject() {
        Map<String, Object> created = service.create(Map.of(
                "userName", "alice",
                "name", Map.of("givenName", "Alice", "formatted", "Alice N."),
                "emails", Map.of("value", "alice@example.com")));

        assertThat(created.get("displayName")).isEqualTo("Alice N.");
        assertThat(created.get("emails")).isEqualTo(
                List.of(Map.of("value", "alice@example.com", "primary", true)));
    }

    @Test
    void createFallsBackToUserNameOnlyProfile() {
        Map<String, Object> created = service.create(Map.of("userName", "alice"));

        assertThat(created).doesNotContainKey("displayName")
                .doesNotContainKey("name")
                .doesNotContainKey("emails")
                .doesNotContainKey("externalId");
    }

    @Test
    void createRejectsDuplicateUserName() {
        service.create(Map.of("userName", "alice"));

        ScimAssertions.assertIamError(() -> service.create(Map.of("userName", "alice")),
                IamErrorCode.USER_CONFLICT, "user already exists: alice");
    }

    @Test
    void updateChangesProfileAndDeactivatesSessions() {
        Map<String, Object> created = service.create(Map.of("userName", "alice",
                "name", Map.of("givenName", "Alice")));
        String id = String.valueOf(created.get("id"));

        Map<String, Object> updated = service.update(id, Map.of(
                "userName", "alice.ng",
                "displayName", "Alice Ng",
                "emails", List.of(Map.of("value", "alice@example.com")),
                "active", false), "W/\"0\"");

        assertThat(updated).containsEntry("userName", "alice.ng")
                .containsEntry("displayName", "Alice Ng")
                .containsEntry("active", false);
        assertThat(revoker.isRevoked("alice.ng")).isTrue();
        assertThat(users.findById(id).orElseThrow().email()).isEqualTo("alice@example.com");
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) updated.get("meta");
        assertThat(meta).containsEntry("version", "W/\"1\"");
    }

    @Test
    void updateRejectsStaleAndMalformedEtag() {
        Map<String, Object> created = service.create(Map.of("userName", "alice"));
        String id = String.valueOf(created.get("id"));

        ScimAssertions.assertIamError(() -> service.update(id, Map.of("userName", "alice"), "W/\"7\""),
                IamErrorCode.SCIM_PRECONDITION_FAILED, "resource version mismatch");
        ScimAssertions.assertIamError(() -> service.update(id, Map.of("userName", "alice"), "not-a-number"),
                IamErrorCode.SCIM_BAD_REQUEST, "invalid If-Match/ETag value");
    }

    @Test
    void updateRequiresUserNameInBody() {
        Map<String, Object> created = service.create(Map.of("userName", "alice"));
        String id = String.valueOf(created.get("id"));

        ScimAssertions.assertIamError(() -> service.update(id, Map.of("displayName", "Alice"), null),
                IamErrorCode.SCIM_BAD_REQUEST, "userName is required");
    }

    @Test
    void updateReplacesPasswordAndRevokesSessions() {
        Map<String, Object> created = service.create(Map.of("userName", "alice",
                "password", "alice-password"));
        String id = String.valueOf(created.get("id"));

        Map<String, Object> updated = service.update(id, Map.of("userName", "alice",
                "password", "new-password"), null);

        assertThat(hasher.matches("new-password",
                users.findPasswordHash(id).orElseThrow())).isTrue();
        assertThat(revoker.isRevoked("alice")).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) updated.get("meta");
        assertThat(meta).containsEntry("version", "W/\"1\"");
    }

    @Test
    void updateRejectsUserRenamedToExistingUserName() {
        service.create(Map.of("userName", "bob"));
        Map<String, Object> created = service.create(Map.of("userName", "alice"));
        String id = String.valueOf(created.get("id"));

        ScimAssertions.assertIamError(() -> service.update(id, Map.of("userName", "bob"), null),
                IamErrorCode.USER_CONFLICT, "user already exists: bob");
    }

    @Test
    void patchAppliesMassUpdateWithoutPath() {
        Map<String, Object> created = service.create(Map.of("userName", "alice"));
        String id = String.valueOf(created.get("id"));

        Map<String, Object> patched = service.patch(id, Map.of("Operations", List.of(Map.of(
                "op", "replace",
                "value", Map.of("userName", "alice2", "externalId", "ext-9",
                        "displayName", "Alice Two",
                        "emails", List.of(Map.of("value", "alice2@example.com")),
                        "active", "false")))), null);

        assertThat(patched).containsEntry("userName", "alice2")
                .containsEntry("externalId", "ext-9")
                .containsEntry("displayName", "Alice Two")
                .containsEntry("active", false);
    }

    @Test
    void patchUpdatesNameSubAttributesAndRemovesValues() {
        Map<String, Object> created = service.create(Map.of("userName", "alice",
                "externalId", "ext-1",
                "name", Map.of("givenName", "Alice", "familyName", "Ng"),
                "emails", List.of(Map.of("value", "alice@example.com"))));
        String id = String.valueOf(created.get("id"));

        service.patch(id, Map.of("Operations", List.of(
                Map.of("op", "replace", "path", "name.givenName", "value", "Alicia"))), null);
        Map<String, Object> patched = service.patch(id, Map.of("Operations", List.of(
                Map.of("op", "replace", "path", "name.familyName", "value", "Wong"),
                Map.of("op", "remove", "path", "externalId"),
                Map.of("op", "replace", "path", "emails.value",
                        "value", Map.of("value", "alicia@example.com")))), null);

        assertThat(patched.get("name")).asInstanceOf(MAP).containsEntry("givenName", "Alicia")
                .containsEntry("familyName", "Wong");
        // displayName is already set, so sub-attribute updates keep the formatted name.
        assertThat(patched.get("displayName")).isEqualTo("Alice Ng");
        assertThat(patched).doesNotContainKey("externalId");
        assertThat(patched.get("emails")).isEqualTo(
                List.of(Map.of("value", "alicia@example.com", "primary", true)));
    }

    @Test
    void patchReplacesPasswordAndDeactivates() {
        Map<String, Object> created = service.create(Map.of("userName", "alice",
                "password", "alice-password"));
        String id = String.valueOf(created.get("id"));

        service.patch(id, Map.of("Operations", List.of(Map.of("op", "replace",
                "path", "password", "value", "patched-password"))), null);
        Map<String, Object> patched = service.patch(id, Map.of("Operations", List.of(
                Map.of("op", "replace", "path", "active", "value", false))), null);

        assertThat(hasher.matches("patched-password",
                users.findPasswordHash(id).orElseThrow())).isTrue();
        assertThat(patched.get("active")).isEqualTo(false);
        assertThat(revoker.isRevoked("alice")).isTrue();
    }

    @Test
    void patchRejectsUnsupportedPathAndMalformedOperations() {
        Map<String, Object> created = service.create(Map.of("userName", "alice"));
        String id = String.valueOf(created.get("id"));

        ScimAssertions.assertIamError(() -> service.patch(id, Map.of("Operations", List.of(Map.of(
                        "op", "replace", "path", "nickName", "value", "Al"))), null),
                IamErrorCode.SCIM_PATCH_INVALID, "unsupported PATCH path: nickName");
        ScimAssertions.assertIamError(() -> service.patch(id, Map.of("Operations", List.of(Map.of(
                        "op", "replace", "value", "not-an-object"))), null),
                IamErrorCode.SCIM_PATCH_INVALID, "PATCH without path requires an object value");
        ScimAssertions.assertIamError(() -> service.patch(id, Map.of("Operations", List.of(Map.of(
                        "path", "active", "value", false))), null),
                IamErrorCode.SCIM_PATCH_INVALID, "invalid SCIM PATCH");
        ScimAssertions.assertIamError(() -> service.patch(id, "not-a-list", null),
                IamErrorCode.SCIM_PATCH_INVALID, "invalid SCIM PATCH");
    }

    @Test
    void patchAcceptsNameObjectPathAndEmptyEmailList() {
        Map<String, Object> created = service.create(Map.of("userName", "alice"));
        String id = String.valueOf(created.get("id"));

        Map<String, Object> patched = service.patch(id, Map.of("Operations", List.of(
                Map.of("op", "replace", "path", "name",
                        "value", Map.of("givenName", "Alice", "familyName", "Ng")),
                Map.of("op", "replace", "path", "emails", "value", List.of()))), null);

        assertThat(patched.get("name")).asInstanceOf(MAP).containsEntry("givenName", "Alice");
        assertThat(patched.get("displayName")).isEqualTo("Alice Ng");
    }

    @Test
    void deleteRemovesUserAndRevokesSessions() {
        Map<String, Object> created = service.create(Map.of("userName", "alice"));
        String id = String.valueOf(created.get("id"));

        service.delete(id, null);

        assertThat(users.findById(id)).isEmpty();
        assertThat(revoker.isRevoked("alice")).isTrue();
        ScimAssertions.assertIamError(() -> service.get(id), IamErrorCode.USER_NOT_FOUND, "user not found: " + id);
    }

    @Test
    void deleteRejectsStaleEtag() {
        Map<String, Object> created = service.create(Map.of("userName", "alice"));
        String id = String.valueOf(created.get("id"));

        ScimAssertions.assertIamError(() -> service.delete(id, "W/\"5\""),
                IamErrorCode.SCIM_PRECONDITION_FAILED, "resource version mismatch");
        assertThat(users.findById(id)).isPresent();
    }

    @Test
    void listPagesAndFiltersUsers() {
        service.create(Map.of("userName", "alice"));
        service.create(Map.of("userName", "bob"));
        service.create(Map.of("userName", "carol"));

        Map<String, Object> page = service.list("userName co \"o\"", 1, 1);

        assertThat(page).containsEntry("totalResults", 2)
                .containsEntry("startIndex", 1)
                .containsEntry("itemsPerPage", 1);
        assertThat((List<?>) page.get("Resources")).hasSize(1);
        assertThat(page.get("schemas")).isEqualTo(List.of(ScimSupport.LIST_SCHEMA));
    }

    @Test
    void mapIncludesGroupMemberships() {
        Map<String, Object> user = service.create(Map.of("userName", "alice"));
        String userId = String.valueOf(user.get("id"));
        var group = groups.create(cn.byteforce.seedj.iam.IamGroup.create("g-1", "default",
                "sales", null, java.util.Set.of(userId)));

        Map<String, Object> mapped = service.map(users.findById(userId).orElseThrow());

        assertThat((List<?>) mapped.get("groups")).singleElement().asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("value", group.id())
                .containsEntry("display", "sales")
                .containsEntry("$ref", "/scim/v2/Groups/" + group.id());
    }

    @Test
    void requireFailsForUnknownId() {
        ScimAssertions.assertIamError(() -> service.require("missing"), IamErrorCode.USER_NOT_FOUND,
                "user not found: missing");
    }

    @Test
    void createsUserRecordWithCanonicalTenantAndAttributes() {
        Map<String, Object> created = service.create(Map.of("userName", "alice",
                "name", Map.of("givenName", "Alice")));
        IamUser stored = users.findById(String.valueOf(created.get("id"))).orElseThrow();

        assertThat(stored.tenantId()).isEqualTo(IamProperties.defaults().tenantId());
        assertThat(stored.attributes()).containsEntry("givenName", "Alice");
        assertThat(stored.active()).isTrue();
    }
}
