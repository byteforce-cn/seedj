package cn.byteforce.seedj.starter.iam.scim;

import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.starter.iam.config.IamProperties;
import cn.byteforce.seedj.starter.iam.identity.IamIdentityService;
import cn.byteforce.seedj.starter.iam.identity.IamPasswordHasherAdapter;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamAuditSink;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamAuthorizationRepository;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamGroupRepository;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamTokenRevoker;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamUserRepository;
import cn.byteforce.seedj.iam.spi.IamPasswordHasher;
import cn.byteforce.seedj.id.IdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScimServiceTest {

    private final InMemoryIamUserRepository users = new InMemoryIamUserRepository();
    private final InMemoryIamGroupRepository groups = new InMemoryIamGroupRepository();
    private final IamPasswordHasher hasher = new IamPasswordHasherAdapter(
            PasswordEncoderFactories.createDelegatingPasswordEncoder());
    private final java.util.concurrent.atomic.AtomicLong seq = new java.util.concurrent.atomic.AtomicLong();
    private final IdGenerator<String> idGenerator = () -> "id-" + seq.incrementAndGet();
    private final IamIdentityService identities = new IamIdentityService(users, hasher,
            new InMemoryIamTokenRevoker(), new InMemoryIamAuditSink(), idGenerator);
    private final IamProperties properties = IamProperties.defaults();
    private final ScimUserService userService = new ScimUserService(users, groups, hasher,
            identities, properties, idGenerator);
    private final ScimGroupService groupService = new ScimGroupService(groups, users, properties,
            new InMemoryIamAuthorizationRepository(), idGenerator);

    @Test
    void createsFiltersAndPatchesUsers() {
        Map<String, Object> created = userService.create(Map.of(
                "userName", "alice",
                "name", Map.of("givenName", "Alice", "familyName", "Ng"),
                "emails", List.of(Map.of("value", "alice@example.com")),
                "password", "secret-password"));

        String id = String.valueOf(created.get("id"));
        assertThat(created.get("userName")).isEqualTo("alice");
        assertThat(created.get("displayName")).isEqualTo("Alice Ng");
        assertThat(created.get("active")).isEqualTo(true);
        assertThat(users.findPasswordHash(id).orElseThrow()).isNotEqualTo("secret-password");

        Map<String, Object> page = userService.list("userName eq \"alice\"", 1, 10);
        assertThat(page.get("totalResults")).isEqualTo(1);
        assertThat((List<?>) page.get("Resources")).hasSize(1);

        String etag = String.valueOf(((Map<?, ?>) created.get("meta")).get("version"));
        Map<String, Object> patched = userService.patch(id,
                Map.of("Operations", List.of(Map.of("op", "replace", "path", "active",
                        "value", false))), etag);
        assertThat(patched.get("active")).isEqualTo(false);

        assertThatThrownBy(() -> userService.update(id, Map.of("userName", "alice"),
                etag)).isInstanceOf(IamException.class);
    }

    @Test
    void createsGroupsAndManagesMembers() {
        Map<String, Object> user = userService.create(Map.of("userName", "bob"));
        String userId = String.valueOf(user.get("id"));

        Map<String, Object> created = groupService.create(Map.of("displayName", "sales",
                "members", List.of(Map.of("value", userId))));
        String groupId = String.valueOf(created.get("id"));
        assertThat(created.get("displayName")).isEqualTo("sales");
        assertThat((List<?>) created.get("members")).hasSize(1);

        String etag = String.valueOf(((Map<?, ?>) created.get("meta")).get("version"));
        Map<String, Object> patched = groupService.patch(groupId,
                Map.of("Operations", List.of(Map.of("op", "remove",
                        "path", "members[value eq \"" + userId + "\"]"))), etag);
        assertThat(patched.get("members")).isNull();
    }
}
