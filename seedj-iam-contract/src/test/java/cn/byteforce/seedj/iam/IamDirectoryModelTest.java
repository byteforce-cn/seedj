package cn.byteforce.seedj.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** IAM 目录模型：角色、权限、分页、SCIM 查询、恢复码、主体与异常。 */
class IamDirectoryModelTest {

    @Test
    @DisplayName("IamRole：name 回落 code，description 回落空串")
    void roleDefaults() {
        IamRole role = IamRole.create("r-1", null, "admin", null, null, null, true);

        assertThat(role.id()).isEqualTo("r-1");
        assertThat(role.tenantId()).isEqualTo(IamUser.DEFAULT_TENANT);
        assertThat(role.code()).isEqualTo("admin");
        assertThat(role.name()).isEqualTo("admin");
        assertThat(role.description()).isEmpty();
        assertThat(role.parentId()).isNull();
        assertThat(role.builtIn()).isTrue();
        assertThat(role.version()).isZero();

        IamRole child = role.withParent("r-0");
        assertThat(child.parentId()).isEqualTo("r-0");
        assertThat(child.code()).isEqualTo("admin");
        assertThat(child.builtIn()).isTrue();

        assertThat(
                        new IamRole(
                                        "r-2",
                                        "tenant-a",
                                        "auditor",
                                        "Auditor",
                                        "reads logs",
                                        null,
                                        false,
                                        -1)
                                .version())
                .isZero();

        assertThatThrownBy(() -> IamRole.create(null, null, "admin", null, null, null, false))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> IamRole.create("r-1", null, " ", null, null, null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("code");
    }

    @Test
    @DisplayName("IamPermission：code 回落 resource:action，of 工厂等价")
    void permissionDefaults() {
        IamPermission permission = new IamPermission(null, null, "order", "read", null);

        assertThat(permission.tenantId()).isEqualTo(IamUser.DEFAULT_TENANT);
        assertThat(permission.code()).isEqualTo("order:read");
        assertThat(permission.resource()).isEqualTo("order");
        assertThat(permission.action()).isEqualTo("read");
        assertThat(permission.description()).isEmpty();

        IamPermission viaFactory = IamPermission.of("tenant-a", "order", "read", "read orders");
        assertThat(viaFactory.tenantId()).isEqualTo("tenant-a");
        assertThat(viaFactory.code()).isEqualTo("order:read");
        assertThat(viaFactory.description()).isEqualTo("read orders");

        assertThatThrownBy(() -> IamPermission.of(null, null, "read", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> IamPermission.of(null, "order", " ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("action");
    }

    @Test
    @DisplayName("IamPage：索引从 1 开始，itemsPerPage 由实际条目数决定")
    void page() {
        IamPage<String> page = new IamPage<>(List.of("a", "b"), 10, 0, 99);

        assertThat(page.items()).containsExactly("a", "b");
        assertThat(page.totalResults()).isEqualTo(10);
        assertThat(page.startIndex()).isEqualTo(1);
        assertThat(page.itemsPerPage()).isEqualTo(2);

        IamPage<String> empty = new IamPage<>(null, -1, 1, 10);
        assertThat(empty.items()).isEmpty();
        assertThat(empty.totalResults()).isZero();
    }

    @Test
    @DisplayName("IamPage.of：按 1-based startIndex 切片并夹取边界")
    void pageOf() {
        List<String> all = List.of("a", "b", "c", "d");

        IamPage<String> firstPage = IamPage.of(all, 1, 2);
        assertThat(firstPage.items()).containsExactly("a", "b");
        assertThat(firstPage.totalResults()).isEqualTo(4);
        assertThat(firstPage.startIndex()).isEqualTo(1);

        IamPage<String> secondPage = IamPage.of(all, 3, 5);
        assertThat(secondPage.items()).containsExactly("c", "d");
        assertThat(secondPage.itemsPerPage()).isEqualTo(2);

        IamPage<String> beyondEnd = IamPage.of(all, 9, 2);
        assertThat(beyondEnd.items()).isEmpty();

        IamPage<String> negativeCount = IamPage.of(all, 2, -3);
        assertThat(negativeCount.items()).isEmpty();
        assertThat(negativeCount.startIndex()).isEqualTo(2);
    }

    @Test
    @DisplayName("IamUserQuery / IamGroupQuery：startIndex 与 count 归一化")
    void queries() {
        IamUserQuery userQuery = new IamUserQuery(" ", null, 0, -5);
        assertThat(userQuery.tenantId()).isEqualTo(IamUser.DEFAULT_TENANT);
        assertThat(userQuery.filter()).isNull();
        assertThat(userQuery.startIndex()).isEqualTo(1);
        assertThat(userQuery.count()).isEqualTo(100);

        IamUserQuery capped = IamUserQuery.all("tenant-a");
        assertThat(capped.tenantId()).isEqualTo("tenant-a");
        assertThat(capped.startIndex()).isEqualTo(1);
        assertThat(capped.count()).isEqualTo(1000);
        assertThat(new IamUserQuery("tenant-a", null, 2, 5000).count()).isEqualTo(1000);
        assertThat(new IamUserQuery("tenant-a", null, 2, 10).count()).isEqualTo(10);

        IamGroupQuery groupQuery = new IamGroupQuery(null, null, -1, 20);
        assertThat(groupQuery.tenantId()).isEqualTo(IamUser.DEFAULT_TENANT);
        assertThat(groupQuery.startIndex()).isEqualTo(1);
        assertThat(groupQuery.count()).isEqualTo(20);

        IamGroupQuery all = IamGroupQuery.all("tenant-a");
        assertThat(all.tenantId()).isEqualTo("tenant-a");
        assertThat(all.count()).isEqualTo(1000);
        assertThat(new IamGroupQuery("tenant-a", null, 1, -1).count()).isEqualTo(100);
        assertThat(new IamGroupQuery("tenant-a", null, 1, 5000).count()).isEqualTo(1000);
    }

    @Test
    @DisplayName("IamRecoveryCode：必填校验与 markUsed")
    void recoveryCode() {
        IamRecoveryCode code = new IamRecoveryCode("u-1", "{bcrypt}hash", false, null);

        assertThat(code.userId()).isEqualTo("u-1");
        assertThat(code.codeHash()).isEqualTo("{bcrypt}hash");
        assertThat(code.used()).isFalse();
        assertThat(code.createdAt()).isNotNull();

        IamRecoveryCode used = code.markUsed();
        assertThat(used.used()).isTrue();
        assertThat(used.codeHash()).isEqualTo("{bcrypt}hash");
        assertThat(used.createdAt()).isEqualTo(code.createdAt());

        assertThatThrownBy(() -> new IamRecoveryCode(null, "h", false, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
        assertThatThrownBy(() -> new IamRecoveryCode("u-1", " ", false, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("codeHash");
    }

    @Test
    @DisplayName("IamSubjectAuthorization：空集合归一化与 empty 工厂")
    void subjectAuthorization() {
        IamSubjectAuthorization authorization = new IamSubjectAuthorization(null, null, null);

        assertThat(authorization.roles()).isEmpty();
        assertThat(authorization.permissions()).isEmpty();
        assertThat(authorization.groups()).isEmpty();
        assertThat(IamSubjectAuthorization.empty().roles()).isEmpty();

        IamSubjectAuthorization populated =
                new IamSubjectAuthorization(Set.of("admin"), Set.of("order:read"), Set.of("g-1"));
        assertThat(populated.roles()).containsExactly("admin");
        assertThatThrownBy(() -> populated.roles().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("IamSubject：null/空白回落默认值，from 带上 pwd amr")
    void subject() {
        IamSubject subject = new IamSubject(null, null, " ", null, null, null, null, null, null);

        assertThat(subject.userId()).isEmpty();
        assertThat(subject.username()).isEmpty();
        assertThat(subject.tenantId()).isEqualTo(IamUser.DEFAULT_TENANT);
        assertThat(subject.roles()).isEmpty();
        assertThat(subject.permissions()).isEmpty();
        assertThat(subject.groups()).isEmpty();
        assertThat(subject.amr()).isEmpty();
        assertThat(subject.acr()).isEqualTo("urn:seedj:acr:pwd");
        assertThat(subject.attributes()).isEmpty();

        IamUser user =
                IamUser.create("u-1", "tenant-a", "alice", "Alice", "a@example.com", true)
                        .withProfile("Alice", "a@example.com", Map.of("dept", "sales"));
        IamSubject fromUser =
                IamSubject.from(
                        user,
                        new IamSubjectAuthorization(
                                Set.of("admin"), Set.of("order:read"), Set.of("g-1")));

        assertThat(fromUser.userId()).isEqualTo("u-1");
        assertThat(fromUser.username()).isEqualTo("alice");
        assertThat(fromUser.tenantId()).isEqualTo("tenant-a");
        assertThat(fromUser.roles()).containsExactly("admin");
        assertThat(fromUser.permissions()).containsExactly("order:read");
        assertThat(fromUser.groups()).containsExactly("g-1");
        assertThat(fromUser.amr()).containsExactly("pwd");
        assertThat(fromUser.acr()).isEqualTo("urn:seedj:acr:pwd");
        assertThat(fromUser.attributes()).containsEntry("dept", "sales");

        IamSubject withoutAuthorization = IamSubject.from(user, null);
        assertThat(withoutAuthorization.roles()).isEmpty();
        assertThat(withoutAuthorization.permissions()).isEmpty();
        assertThat(withoutAuthorization.groups()).isEmpty();
    }

    @Test
    @DisplayName("IamException：携带错误码，且不允许 null 码")
    void iamException() {
        IamException error = new IamException(IamErrorCode.USER_NOT_FOUND, "missing");
        assertThat(error.getCode()).isEqualTo(IamErrorCode.USER_NOT_FOUND);
        assertThat(error.getMessage()).isEqualTo("missing");
        assertThat(error.getCause()).isNull();

        IllegalStateException cause = new IllegalStateException("db");
        IamException withCause = new IamException(IamErrorCode.STORE_UNAVAILABLE, "down", cause);
        assertThat(withCause.getCode()).isEqualTo(IamErrorCode.STORE_UNAVAILABLE);
        assertThat(withCause.getCause()).isSameAs(cause);

        assertThatThrownBy(() -> new IamException(null, "missing"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("code");
    }
}
