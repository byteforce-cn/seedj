package cn.byteforce.seedj.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 发起主体的构造口径：执行主体必填，被代理人、代理绑定与服务名可缺省。
 *
 * <p>只有 {@code actor} 必填是因为直接调用没有代理关系；但三个身份字段一旦存在， 守卫就要一起比对——这里不替守卫做取舍。
 */
class AgentIdentityTest {

    private static final UUID ACTOR = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    @DisplayName("执行主体缺失时拒绝")
    void actor_is_mandatory() {
        assertThatThrownBy(() -> new AgentIdentity(null, null, null, "seedj-ai-starter"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("actor");
    }

    @Test
    @DisplayName("被代理人、代理绑定与服务名可缺省")
    void proxy_components_may_be_absent() {
        AgentIdentity identity = new AgentIdentity(ACTOR, null, null, null);

        assertThat(identity.actor()).isEqualTo(ACTOR);
        assertThat(identity.onBehalfOf()).isNull();
        assertThat(identity.proxyBindingId()).isNull();
        assertThat(identity.actingService()).isNull();
    }
}
