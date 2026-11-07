package cn.byteforce.seedj.iam.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.IamGroup;
import cn.byteforce.seedj.iam.IamGroupQuery;
import cn.byteforce.seedj.iam.ScimComparisonOperator;
import cn.byteforce.seedj.iam.ScimFilter;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@link IamGroupRepository} 契约测试套件：SCIM 组、成员关系与版本语义。 */
public abstract class IamGroupRepositoryContractTest {

    protected abstract IamGroupRepository repository();

    @Test
    void createsAndFindsGroup() {
        IamGroup group = IamGroup.create("g1", "default", "Engineering", "ext-1", Set.of("u1"));

        repository().create(group);

        assertThat(repository().findById("g1")).contains(group);
        assertThat(repository().findByDisplayName("default", "engineering")).contains(group);
        assertThat(repository().findMemberIds("g1")).containsExactly("u1");
        assertThat(repository().findGroupIdsForUser("u1")).containsExactly("g1");
    }

    @Test
    void rejectsDuplicateIdAndDisplayName() {
        repository().create(IamGroup.create("g1", "default", "Engineering", null, Set.of()));

        assertThatThrownBy(
                        () ->
                                repository()
                                        .create(
                                                IamGroup.create(
                                                        "g1", "default", "Other", null, Set.of())))
                .isInstanceOfSatisfying(
                        IamException.class,
                        e -> assertThat(e.getCode()).isEqualTo(IamErrorCode.GROUP_CONFLICT));
        assertThatThrownBy(
                        () ->
                                repository()
                                        .create(
                                                IamGroup.create(
                                                        "g2",
                                                        "default",
                                                        "ENGINEERING",
                                                        null,
                                                        Set.of())))
                .isInstanceOfSatisfying(
                        IamException.class,
                        e -> assertThat(e.getCode()).isEqualTo(IamErrorCode.GROUP_CONFLICT));
    }

    @Test
    void allowsSameDisplayNameInAnotherTenant() {
        repository().create(IamGroup.create("g1", "t1", "Engineering", null, Set.of()));
        repository().create(IamGroup.create("g2", "t2", "Engineering", null, Set.of()));

        assertThat(repository().findByDisplayName("t2", "Engineering")).isPresent();
    }

    @Test
    void updateIncrementsVersion() {
        IamGroup stored =
                repository()
                        .create(IamGroup.create("g1", "default", "Engineering", null, Set.of()));

        IamGroup updated =
                repository()
                        .update(
                                stored.withMembers(Set.of("u1", "u2"))
                                        .withVersion(stored.version(), stored.updatedAt()));

        assertThat(updated.version()).isEqualTo(stored.version() + 1);
        assertThat(repository().findMemberIds("g1")).containsExactlyInAnyOrder("u1", "u2");
    }

    @Test
    void updateRejectsStaleVersion() {
        IamGroup stored =
                repository()
                        .create(IamGroup.create("g1", "default", "Engineering", null, Set.of()));
        repository().update(stored.withMembers(Set.of("u1")));

        assertThatThrownBy(() -> repository().update(stored.withMembers(Set.of("u2"))))
                .isInstanceOfSatisfying(
                        IamException.class,
                        e -> assertThat(e.getCode()).isEqualTo(IamErrorCode.CONFLICT));
    }

    @Test
    void updateRejectsUnknownGroup() {
        IamGroup ghost = IamGroup.create("missing", "default", "Ghost", null, Set.of());

        assertThatThrownBy(() -> repository().update(ghost))
                .isInstanceOfSatisfying(
                        IamException.class,
                        e -> assertThat(e.getCode()).isEqualTo(IamErrorCode.GROUP_NOT_FOUND));
    }

    @Test
    void updateRejectsDisplayNameTakenByAnotherGroup() {
        repository().create(IamGroup.create("g1", "default", "Engineering", null, Set.of()));
        IamGroup ops = repository().create(IamGroup.create("g2", "default", "Ops", null, Set.of()));

        IamGroup renamed =
                new IamGroup(
                        ops.id(),
                        ops.tenantId(),
                        "engineering",
                        ops.externalId(),
                        ops.memberIds(),
                        ops.version(),
                        ops.createdAt(),
                        ops.updatedAt());

        assertThatThrownBy(() -> repository().update(renamed))
                .isInstanceOfSatisfying(
                        IamException.class,
                        e -> assertThat(e.getCode()).isEqualTo(IamErrorCode.GROUP_CONFLICT));
    }

    @Test
    void membersCanBeAddedAndRemoved() {
        IamGroup stored =
                repository()
                        .create(IamGroup.create("g1", "default", "Engineering", null, Set.of()));

        repository().addMember("g1", "u1");
        repository().addMember("g1", "u1");
        repository().removeMember("g1", "missing");

        assertThat(repository().findMemberIds("g1")).containsExactly("u1");
        assertThat(repository().findGroupIdsForUser("u1")).containsExactly("g1");

        repository().removeMember("g1", "u1");

        assertThat(repository().findMemberIds("g1")).isEmpty();
        assertThat(repository().findGroupIdsForUser("u1")).isEmpty();
        assertThat(repository().findById("g1").orElseThrow().version())
                .isGreaterThanOrEqualTo(stored.version());
    }

    @Test
    void addingMemberToUnknownGroupFails() {
        assertThatThrownBy(() -> repository().addMember("missing", "u1"))
                .isInstanceOfSatisfying(
                        IamException.class,
                        e -> assertThat(e.getCode()).isEqualTo(IamErrorCode.GROUP_NOT_FOUND));
    }

    @Test
    void deleteRemovesGroupAndMembership() {
        repository().create(IamGroup.create("g1", "default", "Engineering", null, Set.of("u1")));

        repository().delete("g1");

        assertThat(repository().findById("g1")).isEmpty();
        assertThat(repository().findMemberIds("g1")).isEmpty();
        assertThat(repository().findGroupIdsForUser("u1")).isEmpty();
    }

    @Test
    void pagesInCaseInsensitiveDisplayNameOrder() {
        repository().create(IamGroup.create("g1", "default", "platform", null, Set.of()));
        repository().create(IamGroup.create("g2", "default", "Analytics", null, Set.of()));
        repository().create(IamGroup.create("g3", "default", "backend", null, Set.of()));

        var page = repository().find(new IamGroupQuery("default", null, 2, 1));

        assertThat(page.items()).extracting(IamGroup::displayName).containsExactly("backend");
        assertThat(page.totalResults()).isEqualTo(3);
    }

    @Test
    void filtersByDisplayNameAndTenant() {
        repository().create(IamGroup.create("g1", "default", "Engineering", null, Set.of()));
        repository().create(IamGroup.create("g2", "default", "Ops", null, Set.of()));
        repository().create(IamGroup.create("g3", "other", "Engineering", null, Set.of()));

        ScimFilter like = new ScimFilter.Compare("displayName", ScimComparisonOperator.SW, "eng");
        var page = repository().find(new IamGroupQuery("default", like, 1, 10));

        assertThat(page.items()).extracting(IamGroup::id).containsExactly("g1");
    }

    @Test
    void keepsVersionOnMemberUpdate() {
        repository().create(IamGroup.create("g1", "default", "Engineering", null, Set.of()));

        repository().addMember("g1", "u1");

        assertThat(repository().findById("g1").orElseThrow().updatedAt())
                .isBeforeOrEqualTo(Instant.now());
    }
}
