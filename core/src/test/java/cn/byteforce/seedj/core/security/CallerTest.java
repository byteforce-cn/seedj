package cn.byteforce.seedj.core.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CallerTest {

    @Test
    @DisplayName("空白字段归一化为默认值，集合不可变")
    void normalizesAndCopies() {
        Caller caller = new Caller("u-1", "alice", " ", null, null, null, null, null, null);

        assertThat(caller.tenantId()).isEqualTo(Caller.DEFAULT_TENANT);
        assertThat(caller.roles()).isEmpty();
        assertThat(caller.amr()).containsExactly(Caller.DEFAULT_AMR);
        assertThat(caller.acr()).isEqualTo(Caller.DEFAULT_ACR);
        assertThat(caller.attributes()).isEmpty();
    }

    @Test
    @DisplayName("角色/权限/组判定不带前缀")
    void roleAndPermissionChecks() {
        Caller caller = new Caller("u-1", "alice", "default",
                Set.of("ADMIN"), Set.of("order:read"), Set.of("sales"),
                Set.of("pwd"), Caller.DEFAULT_ACR, Map.of());

        assertThat(caller.hasRole("ADMIN")).isTrue();
        assertThat(caller.hasRole("ROLE_ADMIN")).isFalse();
        assertThat(caller.hasPermission("order:read")).isTrue();
        assertThat(caller.hasGroup("sales")).isTrue();
        assertThat(caller.hasRole(null)).isFalse();
    }

    @Test
    @DisplayName("构造后修改入参不影响快照")
    void snapshotsAttributes() {
        Map<String, Object> attributes = new HashMap<>(Map.of("k", "v"));
        Caller caller = new Caller("u-1", "alice", "default", Set.of(), Set.of(), Set.of(),
                Set.of(), Caller.DEFAULT_ACR, attributes);

        attributes.put("k", "changed");

        assertThat(caller.attributes()).containsEntry("k", "v");
    }

    @Test
    @DisplayName("集合属性按字符串列表读取，缺失或标量返回空")
    void attributeStringsReadsCollections() {
        Caller caller = new Caller("u-1", "alice", "default", Set.of(), Set.of(), Set.of(),
                Set.of(), Caller.DEFAULT_ACR,
                Map.of("org_units", java.util.List.of("D01", "D02")));

        assertThat(caller.attributeStrings("org_units")).containsExactly("D01", "D02");
        assertThat(caller.attributeStrings("missing")).isEmpty();
        assertThat(new Caller("u-1", "alice", "default", Set.of(), Set.of(), Set.of(),
                Set.of(), Caller.DEFAULT_ACR, Map.of("org_units", "D01"))
                .attributeStrings("org_units")).isEmpty();
    }

    @Test
    @DisplayName("空主体标识视为缺失")
    void blankIdIsAbsent() {
        Caller caller = new Caller("", "alice", "default", Set.of(), Set.of(), Set.of(),
                Set.of(), Caller.DEFAULT_ACR, Map.of());

        assertThat(caller.idIfPresent()).isEmpty();
    }

    @Test
    @DisplayName("CallerProvider 可用 lambda 构造")
    void providerIsFunctional() {
        Caller alice = new Caller("u-1", "alice", "default", Set.of(), Set.of(), Set.of(),
                Set.of(), Caller.DEFAULT_ACR, Map.of());
        CallerProvider anonymous = Optional::empty;

        assertThat(anonymous.find()).isEmpty();
        assertThat(((CallerProvider) () -> Optional.of(alice)).find()).contains(alice);
    }
}
