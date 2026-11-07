package cn.byteforce.seedj.starter.iam.identity;

import cn.byteforce.seedj.iam.IamGroup;
import cn.byteforce.seedj.iam.IamPermission;
import cn.byteforce.seedj.iam.IamRole;
import cn.byteforce.seedj.iam.IamSubject;
import cn.byteforce.seedj.iam.IamUser;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamAuthorizationRepository;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamGroupRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IamAuthorizationServiceSubjectTest {

    private final InMemoryIamAuthorizationRepository repository =
            new InMemoryIamAuthorizationRepository();
    private final InMemoryIamGroupRepository groups = new InMemoryIamGroupRepository();
    private final IamAuthorizationService service =
            new IamAuthorizationService(repository, groups, Duration.ofSeconds(30));

    @Test
    void buildsSubjectSnapshotFromEffectiveAuthorization() {
        IamUser user = IamUser.create("u-1", "default", "alice", "Alice", null, true)
                .withProfile("Alice", null, java.util.Map.of("givenName", "Alice"));
        IamGroup group = groups.create(IamGroup.create("g-1", "default", "sales", null,
                Set.of(user.id())));
        IamRole role = repository.createRole(
                IamRole.create("r-1", "default", "reader", "Reader", "", null, false));
        repository.savePermission(IamPermission.of("default", "order", "read", ""));
        repository.grantPermission(role.id(), "order:read");
        repository.assignRoleToUser(user.id(), role.id(), Instant.now(), "test");
        repository.assignRoleToGroup(group.id(), role.id());

        IamSubject subject = service.subject(user);

        assertThat(subject.userId()).isEqualTo("u-1");
        assertThat(subject.username()).isEqualTo("alice");
        assertThat(subject.tenantId()).isEqualTo("default");
        assertThat(subject.roles()).containsExactly("reader");
        assertThat(subject.permissions()).containsExactly("order:read");
        assertThat(subject.groups()).containsExactly("sales");
        assertThat(subject.amr()).containsExactly("pwd");
        assertThat(subject.acr()).isEqualTo("urn:seedj:acr:pwd");
        assertThat(subject.attributes()).containsEntry("givenName", "Alice");
    }

    @Test
    void exposesAndInvalidatesTheCacheEpoch() {
        assertThat(service.epoch("default")).isZero();

        repository.createRole(IamRole.create("r-1", "default", "reader", "Reader", "", null, false));

        assertThat(service.epoch("default")).isPositive();
        assertThat(service.effective("default", "u-1").roles()).isEmpty();
        service.invalidate();
        assertThat(service.effective("default", "u-1").roles()).isEmpty();
    }

    @Test
    void defaultsTtlForNullZeroAndNegativeDurations() {
        for (Duration ttl : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-1)}) {
            IamAuthorizationService tolerant =
                    new IamAuthorizationService(repository, groups, ttl);

            assertThat(tolerant.effective("default", "u-1").roles()).isEmpty();
        }
    }
}
