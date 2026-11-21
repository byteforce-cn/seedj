package cn.byteforce.seedj.iam.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.IamUser;
import cn.byteforce.seedj.iam.IamUserQuery;
import cn.byteforce.seedj.iam.ScimComparisonOperator;
import cn.byteforce.seedj.iam.ScimFilter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link IamUserRepository} 契约测试套件。
 *
 * <p>实现方提供 {@link #repository()}；每个用例拿到独立存储（JUnit 每个用例新建测试实例）， 内存实现与 PostgreSQL
 * 实现必须跑同一套断言。用户名在同一租户内大小写不敏感唯一。
 */
public abstract class IamUserRepositoryContractTest {

    protected abstract IamUserRepository repository();

    @Test
    void createsAndFindsByTenantAndUserName() {
        IamUser user = IamUser.create("u1", "default", "Alice", "Alice", "alice@example.com", true);

        repository().create(user, "hash-1");

        assertThat(repository().findById("u1")).contains(user);
        assertThat(repository().findByUserName("default", "alice")).contains(user);
        assertThat(repository().findByUserName("default", "ALICE")).contains(user);
        assertThat(repository().findByUserName("other", "alice")).isEmpty();
        assertThat(repository().findPasswordHash("u1")).contains("hash-1");
        assertThat(repository().count()).isEqualTo(1);
    }

    @Test
    void sameUserNameInDifferentTenantsIsAllowed() {
        IamUser a = IamUser.create("u1", "t1", "alice", "Alice", null, true);
        IamUser b = IamUser.create("u2", "t2", "alice", "Alice", null, true);

        repository().create(a, "h1");
        repository().create(b, "h2");

        assertThat(repository().count()).isEqualTo(2);
        assertThat(repository().findByUserName("t1", "alice")).contains(a);
        assertThat(repository().findByUserName("t2", "alice")).contains(b);
    }

    @Test
    void rejectsDuplicateId() {
        repository().create(IamUser.create("u1", "default", "alice", "Alice", null, true), "h1");

        assertThatThrownBy(
                        () ->
                                repository()
                                        .create(
                                                IamUser.create(
                                                        "u1", "default", "bob", "Bob", null, true),
                                                "h2"))
                .isInstanceOfSatisfying(
                        IamException.class,
                        e -> assertThat(e.getCode()).isEqualTo(IamErrorCode.USER_CONFLICT));
    }

    @Test
    void rejectsDuplicateUserNameCaseInsensitively() {
        repository().create(IamUser.create("u1", "default", "Alice", "Alice", null, true), "h1");

        assertThatThrownBy(
                        () ->
                                repository()
                                        .create(
                                                IamUser.create(
                                                        "u2", "default", "ALICE", "Alice", null,
                                                        true),
                                                "h2"))
                .isInstanceOfSatisfying(
                        IamException.class,
                        e -> assertThat(e.getCode()).isEqualTo(IamErrorCode.USER_CONFLICT));
    }

    @Test
    void updateIncrementsVersionAndKeepsPassword() {
        IamUser stored =
                repository()
                        .create(
                                IamUser.create("u1", "default", "alice", "Alice", null, true),
                                "h1");

        IamUser updated =
                repository().update(stored.withProfile("Alicia", "a@example.com", Map.of()));

        assertThat(updated.version()).isEqualTo(stored.version() + 1);
        assertThat(repository().findById("u1"))
                .get()
                .extracting(IamUser::displayName)
                .isEqualTo("Alicia");
        assertThat(repository().findPasswordHash("u1")).contains("h1");
    }

    @Test
    void updateRejectsStaleVersion() {
        IamUser stored =
                repository()
                        .create(
                                IamUser.create("u1", "default", "alice", "Alice", null, true),
                                "h1");
        repository().update(stored.withActive(false));

        assertThatThrownBy(() -> repository().update(stored.withActive(true)))
                .isInstanceOfSatisfying(
                        IamException.class,
                        e -> assertThat(e.getCode()).isEqualTo(IamErrorCode.CONFLICT));
    }

    @Test
    void updateRejectsUnknownUser() {
        IamUser ghost = IamUser.create("missing", "default", "ghost", "Ghost", null, true);

        assertThatThrownBy(() -> repository().update(ghost))
                .isInstanceOfSatisfying(
                        IamException.class,
                        e -> assertThat(e.getCode()).isEqualTo(IamErrorCode.USER_NOT_FOUND));
    }

    @Test
    void updateRejectsUserNameTakenByAnotherUser() {
        repository().create(IamUser.create("u1", "default", "alice", "Alice", null, true), "h1");
        IamUser bob =
                repository()
                        .create(IamUser.create("u2", "default", "bob", "Bob", null, true), "h2");

        IamUser renamed =
                new IamUser(
                        bob.id(),
                        bob.tenantId(),
                        "alice",
                        bob.externalId(),
                        bob.displayName(),
                        bob.email(),
                        bob.active(),
                        bob.attributes(),
                        bob.version(),
                        bob.createdAt(),
                        bob.updatedAt());

        assertThatThrownBy(() -> repository().update(renamed))
                .isInstanceOfSatisfying(
                        IamException.class,
                        e -> assertThat(e.getCode()).isEqualTo(IamErrorCode.USER_CONFLICT));
    }

    @Test
    void renameFreesPreviousLogin() {
        IamUser stored =
                repository()
                        .create(
                                IamUser.create("u1", "default", "alice", "Alice", null, true),
                                "h1");

        IamUser renamed =
                new IamUser(
                        stored.id(),
                        stored.tenantId(),
                        "alicia",
                        stored.externalId(),
                        stored.displayName(),
                        stored.email(),
                        stored.active(),
                        stored.attributes(),
                        stored.version(),
                        stored.createdAt(),
                        stored.updatedAt());
        repository().update(renamed);

        assertThat(repository().findByUserName("default", "alice")).isEmpty();
        assertThat(repository().findByUserName("default", "alicia")).isPresent();
    }

    @Test
    void deleteRemovesUserAndFreesLogin() {
        repository().create(IamUser.create("u1", "default", "alice", "Alice", null, true), "h1");

        repository().delete("u1");

        assertThat(repository().findById("u1")).isEmpty();
        assertThat(repository().findPasswordHash("u1")).isEmpty();
        assertThat(repository().count()).isZero();
        repository().create(IamUser.create("u2", "default", "alice", "Alice", null, true), "h2");
        assertThat(repository().count()).isEqualTo(1);
    }

    @Test
    void deletingUnknownUserIsNoop() {
        repository().delete("missing");

        assertThat(repository().count()).isZero();
    }

    @Test
    void updatesPasswordHash() {
        repository().create(IamUser.create("u1", "default", "alice", "Alice", null, true), "h1");

        repository().updatePassword("u1", "h2", Instant.parse("2026-11-06T00:00:00Z"));

        assertThat(repository().findPasswordHash("u1")).contains("h2");
    }

    @Test
    void rejectingPasswordUpdateForUnknownUser() {
        assertThatThrownBy(() -> repository().updatePassword("missing", "h2", Instant.now()))
                .isInstanceOfSatisfying(
                        IamException.class,
                        e -> assertThat(e.getCode()).isEqualTo(IamErrorCode.USER_NOT_FOUND));
    }

    @Test
    void pagesInCaseInsensitiveUserNameOrder() {
        repository().create(IamUser.create("u1", "default", "carol", "Carol", null, true), "h1");
        repository().create(IamUser.create("u2", "default", "alice", "Alice", null, true), "h2");
        repository().create(IamUser.create("u3", "default", "Bob", "Bob", null, true), "h3");

        var page = repository().find(new IamUserQuery("default", null, 2, 1));

        assertThat(page.items()).extracting(IamUser::userName).containsExactly("Bob");
        assertThat(page.totalResults()).isEqualTo(3);
        assertThat(page.startIndex()).isEqualTo(2);
        assertThat(page.itemsPerPage()).isEqualTo(1);
    }

    @Test
    void filtersByActiveAndTenant() {
        repository().create(IamUser.create("u1", "default", "alice", "Alice", null, true), "h1");
        repository().create(IamUser.create("u2", "default", "bob", "Bob", null, false), "h2");
        repository().create(IamUser.create("u3", "other", "carol", "Carol", null, false), "h3");

        ScimFilter active = new ScimFilter.Compare("active", ScimComparisonOperator.EQ, true);
        var page = repository().find(new IamUserQuery("default", active, 1, 10));

        assertThat(page.items()).extracting(IamUser::userName).containsExactly("alice");
        assertThat(page.totalResults()).isEqualTo(1);
    }

    @Test
    void filtersByUserNameSubstring() {
        repository().create(IamUser.create("u1", "default", "alice", "Alice", null, true), "h1");
        repository().create(IamUser.create("u2", "default", "bob", "Bob", null, true), "h2");

        ScimFilter like = new ScimFilter.Compare("userName", ScimComparisonOperator.CO, "LI");
        List<IamUser> items = repository().find(new IamUserQuery("default", like, 1, 10)).items();

        assertThat(items).extracting(IamUser::userName).containsExactly("alice");
    }
}
