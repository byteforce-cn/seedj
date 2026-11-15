package cn.byteforce.seedj.infra.coord;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class CoordProviderGuardTest {

    @Test
    void failsWhenProviderSelectedWithoutCoordEnabled() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("seedj.eventbus.transport", "coord");
        environment.setProperty("seedj.id.provider", "local");

        assertThatThrownBy(() -> new CoordProviderGuard(environment).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("seedj.eventbus.transport");
    }

    @Test
    void acceptsLocalProviders() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("seedj.eventbus.transport", "file");
        environment.setProperty("seedj.id.provider", "local");

        assertThatCode(() -> new CoordProviderGuard(environment).afterPropertiesSet())
                .doesNotThrowAnyException();
    }
}
