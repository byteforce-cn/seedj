package cn.byteforce.seedj.iam;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IAM group aggregate：成员集合不可变、版本与时间戳归一化。
 */
class IamGroupTest {

    private static final Instant CREATED = Instant.parse("2026-01-05T10:00:00Z");

    @Test
    @DisplayName("create：tenant 回落 default，成员集合复制，版本 0")
    void createDefaults() {
        IamGroup group = IamGroup.create("g-1", " ", "Engineering", "ext-1", Set.of("u-1"));

        assertThat(group.id()).isEqualTo("g-1");
        assertThat(group.tenantId()).isEqualTo(IamUser.DEFAULT_TENANT);
        assertThat(group.displayName()).isEqualTo("Engineering");
        assertThat(group.externalId()).isEqualTo("ext-1");
        assertThat(group.memberIds()).containsExactly("u-1");
        assertThat(group.version()).isZero();
        assertThat(group.createdAt()).isEqualTo(group.updatedAt());

        assertThat(IamGroup.create("g-2", "tenant-a", "Sales", null, null).memberIds()).isEmpty();
        assertThat(IamGroup.create("g-2", "tenant-a", "Sales", null, null).tenantId())
                .isEqualTo("tenant-a");
    }

    @Test
    @DisplayName("构造：id/displayName 必填，负版本归零")
    void constructorNormalizes() {
        IamGroup group = new IamGroup("g-1", null, "Engineering", null, null, -5, null, null);

        assertThat(group.tenantId()).isEqualTo(IamUser.DEFAULT_TENANT);
        assertThat(group.memberIds()).isEmpty();
        assertThat(group.version()).isZero();
        assertThat(group.createdAt()).isNotNull();
        assertThat(group.updatedAt()).isEqualTo(group.createdAt());

        assertThatThrownBy(() -> new IamGroup(null, null, "Engineering", null, null, 0,
                CREATED, CREATED)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IamGroup("g-1", null, " ", null, null, 0,
                CREATED, CREATED)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("displayName");
        assertThatThrownBy(() -> new IamGroup(" ", null, "Engineering", null, null, 0,
                CREATED, CREATED)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
    }

    @Test
    @DisplayName("withMembers / withVersion：保留其它字段")
    void withers() {
        IamGroup base = new IamGroup("g-1", "tenant-a", "Engineering", "ext-1", Set.of("u-1"), 2,
                CREATED, CREATED);

        IamGroup withMembers = base.withMembers(Set.of("u-1", "u-2"));
        assertThat(withMembers.memberIds()).containsExactlyInAnyOrder("u-1", "u-2");
        assertThat(withMembers.displayName()).isEqualTo("Engineering");
        assertThat(withMembers.version()).isEqualTo(2);

        Instant later = CREATED.plusSeconds(30);
        IamGroup versioned = base.withVersion(3, later);
        assertThat(versioned.version()).isEqualTo(3);
        assertThat(versioned.updatedAt()).isEqualTo(later);
        assertThat(versioned.createdAt()).isEqualTo(CREATED);
        assertThat(versioned.memberIds()).containsExactly("u-1");
    }

    @Test
    @DisplayName("mutableMembers：返回可变副本")
    void mutableMembers() {
        IamGroup group = IamGroup.create("g-1", null, "Engineering", null, Set.of("u-1"));

        Set<String> copy = group.mutableMembers();
        copy.add("u-2");

        assertThat(copy).containsExactlyInAnyOrder("u-1", "u-2");
        assertThat(group.memberIds()).containsExactly("u-1");
        assertThatThrownBy(() -> group.memberIds().add("u-3"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
