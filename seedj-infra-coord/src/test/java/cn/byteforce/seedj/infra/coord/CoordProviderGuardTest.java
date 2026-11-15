package cn.byteforce.seedj.infra.coord;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    @ParameterizedTest
    @ValueSource(
            strings = {
                "seedj.id.provider=coord",
                "seedj.numbering.provider=coord",
                "seedj.clock.provider=coord",
                "seedj.eventbus.transport=coord",
                "seedj.lock.provider=coord",
                "seedj.pki.provider=coord",
                "seedj.transit.provider=coord",
                "seedj.workflow.provider=coord"
            })
    void failsForEveryCoordProviderKey(String property) {
        MockEnvironment environment = new MockEnvironment();
        String key = property.substring(0, property.indexOf('='));
        String value = property.substring(property.indexOf('=') + 1);
        environment.setProperty(key, value);

        assertThatThrownBy(() -> new CoordProviderGuard(environment).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(key);
    }
}
