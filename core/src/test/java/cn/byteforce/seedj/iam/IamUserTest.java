package cn.byteforce.seedj.iam;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IAM user aggregate：缺省 tenant/时间戳、用户名归一化与不可变副本。
 */
class IamUserTest {

    private static final Instant CREATED = Instant.parse("2026-01-05T10:00:00Z");

    @Test
    @DisplayName("create：tenant 为空回落 default，版本从 0 开始")
    void createDefaults() {
        IamUser user = IamUser.create("u-1", null, "Alice", "Alice Chen", "alice@example.com",
                true);

        assertThat(user.id()).isEqualTo("u-1");
        assertThat(user.tenantId()).isEqualTo(IamUser.DEFAULT_TENANT);
        assertThat(user.userName()).isEqualTo("Alice");
        assertThat(user.externalId()).isNull();
        assertThat(user.displayName()).isEqualTo("Alice Chen");
        assertThat(user.email()).isEqualTo("alice@example.com");
        assertThat(user.active()).isTrue();
        assertThat(user.attributes()).isEmpty();
        assertThat(user.version()).isZero();
        assertThat(user.createdAt()).isEqualTo(user.updatedAt());

        assertThat(IamUser.create("u-2", " ", "bob", null, null, true).tenantId())
                .isEqualTo(IamUser.DEFAULT_TENANT);
        assertThat(IamUser.create("u-2", "tenant-a", "bob", null, null, true).tenantId())
                .isEqualTo("tenant-a");
    }

    @Test
    @DisplayName("构造：id/userName 必填，版本不为负，时间戳回填")
    void constructorNormalizes() {
        IamUser user = new IamUser("u-1", null, "Alice", null, null, null, true, null, -3,
                null, null);

        assertThat(user.tenantId()).isEqualTo(IamUser.DEFAULT_TENANT);
        assertThat(user.attributes()).isEmpty();
        assertThat(user.version()).isZero();
        assertThat(user.createdAt()).isNotNull();
        assertThat(user.updatedAt()).isEqualTo(user.createdAt());

        assertThatThrownBy(() -> new IamUser(null, null, "alice", null, null, null, true,
                null, 0, CREATED, CREATED)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IamUser("u-1", null, " ", null, null, null, true,
                null, 0, CREATED, CREATED)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userName");
        assertThatThrownBy(() -> new IamUser(" ", null, "alice", null, null, null, true,
                null, 0, CREATED, CREATED)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
    }

    @Test
    @DisplayName("normalizedUserName：Locale.ROOT 小写")
    void normalizedUserName() {
        assertThat(IamUser.create("u-1", null, "Alice.Chen", null, null, true)
                .normalizedUserName()).isEqualTo("alice.chen");
    }

    @Test
    @DisplayName("with*：只改目标字段，其余保持不变")
    void withers() {
        IamUser base = new IamUser("u-1", "tenant-a", "alice", "ext-1", "Alice", "a@example.com",
                true, Map.of("dept", "sales"), 2, CREATED, CREATED);

        IamUser profiled = base.withProfile("Alice Chen", "alice@example.com",
                Map.of("dept", "hr"));
        assertThat(profiled.displayName()).isEqualTo("Alice Chen");
        assertThat(profiled.email()).isEqualTo("alice@example.com");
        assertThat(profiled.attributes()).containsEntry("dept", "hr");
        assertThat(profiled.id()).isEqualTo("u-1");
        assertThat(profiled.tenantId()).isEqualTo("tenant-a");
        assertThat(profiled.externalId()).isEqualTo("ext-1");
        assertThat(profiled.version()).isEqualTo(2);

        assertThat(base.withActive(false).active()).isFalse();
        assertThat(base.withActive(false).userName()).isEqualTo("alice");
        assertThat(base.withExternalId("ext-2").externalId()).isEqualTo("ext-2");
        assertThat(base.withExternalId("ext-2").displayName()).isEqualTo("Alice");

        Instant later = CREATED.plusSeconds(60);
        IamUser versioned = base.withVersion(3, later);
        assertThat(versioned.version()).isEqualTo(3);
        assertThat(versioned.updatedAt()).isEqualTo(later);
        assertThat(versioned.createdAt()).isEqualTo(CREATED);
        assertThat(versioned.attributes()).containsEntry("dept", "sales");
    }

    @Test
    @DisplayName("mutableAttributes：返回可变副本，不改变聚合")
    void mutableAttributes() {
        IamUser user = IamUser.create("u-1", null, "alice", null, null, true)
                .withProfile("Alice", null, Map.of("dept", "sales"));

        Map<String, Object> copy = user.mutableAttributes();
        copy.put("dept", "hr");

        assertThat(copy).containsEntry("dept", "hr");
        assertThat(user.attributes()).containsEntry("dept", "sales");
    }
}
