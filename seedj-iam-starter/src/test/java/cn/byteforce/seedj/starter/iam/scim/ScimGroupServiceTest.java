package cn.byteforce.seedj.starter.iam.scim;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamUser;
import cn.byteforce.seedj.iam.spi.IamPasswordHasher;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamAuthorizationRepository;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamGroupRepository;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamUserRepository;
import cn.byteforce.seedj.starter.iam.IamProperties;
import cn.byteforce.seedj.starter.iam.identity.IamPasswordHasherAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;

class ScimGroupServiceTest {

    private final InMemoryIamUserRepository users = new InMemoryIamUserRepository();
    private final InMemoryIamGroupRepository groups = new InMemoryIamGroupRepository();
    private final InMemoryIamAuthorizationRepository authorization =
            new InMemoryIamAuthorizationRepository();
    private final IamPasswordHasher hasher = new IamPasswordHasherAdapter(
            PasswordEncoderFactories.createDelegatingPasswordEncoder());
    private final AtomicLong seq = new AtomicLong();
    private final IdGenerator<String> idGenerator = () -> "id-" + seq.incrementAndGet();
    private final IamProperties properties = IamProperties.defaults();
    private final ScimGroupService service = new ScimGroupService(groups, users, properties,
            authorization, idGenerator);
    private final String alice = userId("alice");
    private final String bob = userId("bob");

    @Test
    void createRequiresDisplayName() {
        ScimAssertions.assertIamError(() -> service.create(Map.of("externalId", "ext-1")),
                IamErrorCode.SCIM_BAD_REQUEST, "displayName is required");
        ScimAssertions.assertIamError(() -> service.create(Map.of("displayName", " ")),
                IamErrorCode.SCIM_BAD_REQUEST, "displayName is required");
    }

    @Test
    void createValidatesMembersAndBumpsEpoch() {
        long epochBefore = authorization.currentEpoch(properties.tenantId());

        Map<String, Object> created = service.create(Map.of("displayName", "sales",
                "externalId", "ext-1",
                "members", List.of(Map.of("value", alice), Map.of("value", bob))));

        assertThat(created).containsEntry("displayName", "sales").containsEntry("externalId", "ext-1");
        assertThat((List<?>) created.get("members")).hasSize(2);
        assertThat(authorization.currentEpoch(properties.tenantId())).isGreaterThan(epochBefore);

        ScimAssertions.assertIamError(() -> service.create(Map.of("displayName", "support",
                        "members", List.of(Map.of("value", "ghost")))),
                IamErrorCode.SCIM_BAD_REQUEST, "member does not exist: ghost");
    }

    @Test
    void createAcceptsPlainStringMembers() {
        Map<String, Object> single = service.create(Map.of("displayName", "single",
                "members", alice));
        Map<String, Object> listed = service.create(Map.of("displayName", "listed",
                "members", List.of(alice, bob)));

        assertThat((List<?>) single.get("members")).hasSize(1);
        assertThat((List<?>) listed.get("members")).hasSize(2);
    }

    @Test
    void updateKeepsMembersWhenAbsentAndReplacesThemWhenPresent() {
        Map<String, Object> created = service.create(Map.of("displayName", "sales",
                "members", List.of(Map.of("value", alice))));
        String id = String.valueOf(created.get("id"));

        Map<String, Object> renamed = service.update(id, Map.of("displayName", "sales-eu"), "W/\"0\"");
        assertThat(renamed).containsEntry("displayName", "sales-eu");
        assertThat((List<?>) renamed.get("members")).hasSize(1);

        Map<String, Object> replaced = service.update(id, Map.of("displayName", "sales-eu",
                "members", List.of(Map.of("value", bob))), "W/\"1\"");
        assertThat((List<?>) replaced.get("members")).singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("value", bob);
    }

    @Test
    void updateDefaultsBlankDisplayNameToCurrent() {
        Map<String, Object> created = service.create(Map.of("displayName", "sales"));
        String id = String.valueOf(created.get("id"));

        Map<String, Object> updated = service.update(id, Map.of("displayName", ""), null);

        assertThat(updated).containsEntry("displayName", "sales");
    }

    @Test
    void updateRejectsStaleEtagAndUnknownMember() {
        Map<String, Object> created = service.create(Map.of("displayName", "sales"));
        String id = String.valueOf(created.get("id"));

        ScimAssertions.assertIamError(() -> service.update(id,
                        Map.of("displayName", "sales"), "W/\"9\""),
                IamErrorCode.SCIM_PRECONDITION_FAILED, "resource version mismatch");
        ScimAssertions.assertIamError(() -> service.update(id,
                        Map.of("displayName", "sales", "members", List.of(Map.of("value", "ghost"))),
                        null),
                IamErrorCode.SCIM_BAD_REQUEST, "member does not exist: ghost");
    }

    @Test
    void patchAddsAndRemovesMembersByValueFilter() {
        Map<String, Object> created = service.create(Map.of("displayName", "sales",
                "members", List.of(Map.of("value", alice))));
        String id = String.valueOf(created.get("id"));

        Map<String, Object> added = service.patch(id, Map.of("Operations", List.of(Map.of(
                        "op", "add", "path", "members[value eq \"" + bob + "\"]",
                        "value", List.of(Map.of("value", bob))))), null);
        assertThat((List<?>) added.get("members")).hasSize(2);

        Map<String, Object> removed = service.patch(id, Map.of("Operations", List.of(Map.of(
                        "op", "remove", "path", "members[value eq \"" + alice + "\"]"))), null);
        assertThat((List<?>) removed.get("members")).singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("value", bob);
    }

    @Test
    void patchReplaceWithValueFilterSubstitutesMembers() {
        Map<String, Object> created = service.create(Map.of("displayName", "sales",
                "members", List.of(Map.of("value", alice))));
        String id = String.valueOf(created.get("id"));

        // A replace with a value filter drops the matched member and adds the value members.
        Map<String, Object> patched = service.patch(id, Map.of("Operations", List.of(Map.of(
                        "op", "replace", "path", "members[value eq \"" + alice + "\"]",
                        "value", List.of(Map.of("value", bob))))), null);

        assertThat((List<?>) patched.get("members")).singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("value", bob);
    }

    @Test
    void patchMembersPathSupportsAddReplaceAndRemove() {
        Map<String, Object> created = service.create(Map.of("displayName", "sales"));
        String id = String.valueOf(created.get("id"));

        Map<String, Object> added = service.patch(id, Map.of("Operations", List.of(Map.of(
                "op", "add", "path", "members",
                "value", List.of(Map.of("value", alice))))), null);
        assertThat((List<?>) added.get("members")).hasSize(1);

        Map<String, Object> replaced = service.patch(id, Map.of("Operations", List.of(Map.of(
                "op", "replace", "path", "members",
                "value", List.of(Map.of("value", bob))))), null);
        assertThat((List<?>) replaced.get("members")).singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("value", bob);

        Map<String, Object> cleared = service.patch(id, Map.of("Operations", List.of(Map.of(
                "op", "remove", "path", "members"))), null);
        assertThat(cleared).doesNotContainKey("members");
    }

    @Test
    void patchAppliesMassUpdateWithoutPath() {
        Map<String, Object> created = service.create(Map.of("displayName", "sales",
                "members", List.of(Map.of("value", alice))));
        String id = String.valueOf(created.get("id"));

        Map<String, Object> patched = service.patch(id, Map.of("Operations", List.of(Map.of(
                "op", "replace",
                "value", Map.of("displayName", "sales-eu", "externalId", "ext-2",
                        "members", List.of(Map.of("value", bob)))))), null);

        assertThat(patched).containsEntry("displayName", "sales-eu").containsEntry("externalId", "ext-2");
        assertThat((List<?>) patched.get("members")).singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("value", bob);
    }

    @Test
    void patchReplacesExternalId() {
        Map<String, Object> created = service.create(Map.of("displayName", "sales",
                "externalId", "ext-1"));
        String id = String.valueOf(created.get("id"));

        Map<String, Object> patched = service.patch(id, Map.of("Operations", List.of(Map.of(
                "op", "replace", "path", "externalId", "value", "ext-2"))), null);

        assertThat(patched).containsEntry("externalId", "ext-2");
    }

    @Test
    void patchRemovesExternalId() {
        Map<String, Object> created = service.create(Map.of("displayName", "sales",
                "externalId", "ext-1"));
        String id = String.valueOf(created.get("id"));

        Map<String, Object> patched = service.patch(id, Map.of("Operations", List.of(Map.of(
                "op", "remove", "path", "externalId"))), null);

        assertThat(patched).doesNotContainKey("externalId");
    }

    @Test
    void patchRejectsDisplayNameRemovalAndUnsupportedPaths() {
        Map<String, Object> created = service.create(Map.of("displayName", "sales"));
        String id = String.valueOf(created.get("id"));

        ScimAssertions.assertIamError(() -> service.patch(id, Map.of("Operations", List.of(
                        Map.of("op", "remove", "path", "displayName"))), null),
                IamErrorCode.SCIM_PATCH_INVALID, "displayName cannot be removed");
        ScimAssertions.assertIamError(() -> service.patch(id, Map.of("Operations", List.of(
                        Map.of("op", "replace", "path", "roles", "value", List.of()))), null),
                IamErrorCode.SCIM_PATCH_INVALID, "unsupported PATCH path: roles");
        ScimAssertions.assertIamError(() -> service.patch(id, Map.of("Operations", List.of(
                        Map.of("op", "replace", "value", "text"))), null),
                IamErrorCode.SCIM_PATCH_INVALID, "PATCH without path requires an object value");
    }

    @Test
    void patchRejectsUnsupportedMemberFilterAndMalformedPath() {
        Map<String, Object> created = service.create(Map.of("displayName", "sales",
                "members", List.of(Map.of("value", alice))));
        String id = String.valueOf(created.get("id"));

        ScimAssertions.assertIamError(() -> service.patch(id, Map.of("Operations", List.of(
                        Map.of("op", "remove", "path", "members[value co \"bob\"]"))), null),
                IamErrorCode.SCIM_PATCH_INVALID,
                "members path filter must be value eq \"id\"");
        ScimAssertions.assertIamError(() -> service.patch(id, Map.of("Operations", List.of(
                        Map.of("op", "remove", "path", "members[value eq \"bob\""))), null),
                IamErrorCode.SCIM_PATCH_INVALID, "unterminated PATCH path filter");
    }

    @Test
    void patchRequiresDisplayNameWhenCleared() {
        Map<String, Object> created = service.create(Map.of("displayName", "sales"));
        String id = String.valueOf(created.get("id"));

        ScimAssertions.assertIamError(() -> service.patch(id, Map.of("Operations", List.of(
                        Map.of("op", "replace", "path", "displayName", "value", ""))), null),
                IamErrorCode.SCIM_BAD_REQUEST, "displayName is required");
    }

    @Test
    void patchValidatesMembersAfterUpdate() {
        Map<String, Object> created = service.create(Map.of("displayName", "sales"));
        String id = String.valueOf(created.get("id"));

        ScimAssertions.assertIamError(() -> service.patch(id, Map.of("Operations", List.of(
                        Map.of("op", "add", "path", "members",
                                "value", List.of(Map.of("value", "ghost"))))), null),
                IamErrorCode.SCIM_BAD_REQUEST, "member does not exist: ghost");
    }

    @Test
    void deleteRemovesGroupAndBumpsEpoch() {
        Map<String, Object> created = service.create(Map.of("displayName", "sales"));
        String id = String.valueOf(created.get("id"));
        long epochBefore = authorization.currentEpoch(properties.tenantId());

        service.delete(id, null);

        assertThat(groups.findById(id)).isEmpty();
        assertThat(authorization.currentEpoch(properties.tenantId())).isGreaterThan(epochBefore);
        ScimAssertions.assertIamError(() -> service.get(id), IamErrorCode.GROUP_NOT_FOUND,
                "group not found: " + id);
    }

    @Test
    void deleteRejectsStaleEtag() {
        Map<String, Object> created = service.create(Map.of("displayName", "sales"));
        String id = String.valueOf(created.get("id"));

        ScimAssertions.assertIamError(() -> service.delete(id, "W/\"3\""),
                IamErrorCode.SCIM_PRECONDITION_FAILED, "resource version mismatch");
        assertThat(groups.findById(id)).isPresent();
    }

    @Test
    void listFiltersGroups() {
        service.create(Map.of("displayName", "sales"));
        service.create(Map.of("displayName", "sales-eu"));
        service.create(Map.of("displayName", "support"));

        Map<String, Object> page = service.list("displayName sw \"sales\"", 1, 100);

        assertThat(page).containsEntry("totalResults", 2).containsEntry("itemsPerPage", 2);
        assertThat(page.get("schemas")).isEqualTo(List.of(ScimSupport.LIST_SCHEMA));
    }

    @Test
    void mapIncludesMemberDetailsOnlyForExistingUsers() {
        Map<String, Object> created = service.create(Map.of("displayName", "sales",
                "members", List.of(Map.of("value", alice), Map.of("value", bob))));
        String id = String.valueOf(created.get("id"));
        users.delete(bob);

        Map<String, Object> mapped = service.map(groups.findById(id).orElseThrow());

        Map<String, Object> membersByValue = new java.util.LinkedHashMap<>();
        for (Object member : (List<?>) mapped.get("members")) {
            Map<?, ?> reference = (Map<?, ?>) member;
            membersByValue.put(String.valueOf(reference.get("value")), reference);
        }
        assertThat(membersByValue).containsOnlyKeys(alice, bob);
        assertThat(membersByValue.get(alice)).asInstanceOf(MAP)
                .containsEntry("display", "alice")
                .containsEntry("$ref", "/scim/v2/Users/" + alice);
        assertThat(membersByValue.get(bob)).asInstanceOf(MAP).doesNotContainKey("display");
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) mapped.get("meta");
        assertThat(meta).containsEntry("resourceType", "Group")
                .containsEntry("location", "/scim/v2/Groups/" + id);
    }

    @Test
    void mapOmitsMembersWhenGroupIsEmpty() {
        Map<String, Object> mapped = service.create(Map.of("displayName", "empty"));

        assertThat(mapped).doesNotContainKey("members");
    }

    private String userId(String userName) {
        IamUser user = users.create(IamUser.create("u-" + userName, properties.tenantId(), userName,
                userName, null, true), hasher.encode("password"));
        return user.id();
    }

    @Test
    void requireFailsForUnknownGroup() {
        ScimAssertions.assertIamError(() -> service.require("missing"),
                IamErrorCode.GROUP_NOT_FOUND, "group not found: missing");
        assertThat(groups.findById("missing")).isEmpty();
    }
}
