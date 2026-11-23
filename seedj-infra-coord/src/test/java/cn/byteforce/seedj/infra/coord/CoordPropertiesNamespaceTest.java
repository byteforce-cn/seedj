package cn.byteforce.seedj.infra.coord;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class CoordPropertiesNamespaceTest {

    private final MockEnvironment environment = new MockEnvironment();

    @Test
    void explicitNamespaceWins() {
        CoordProperties properties = new CoordProperties();
        properties.setNamespace("custom");

        assertThat(properties.resolveNamespace(environment)).isEqualTo("custom");
        assertThat(CoordNamespaces.of(properties, environment)).isEqualTo("custom");
    }

    @Test
    void defaultsToSeedjApplicationName() {
        environment.setProperty("spring.application.name", "seedj-app");
        CoordProperties properties = new CoordProperties();

        assertThat(properties.resolveNamespace(environment)).isEqualTo("seedj:seedj-app");
    }

    @Test
    void fallsBackToDefaultApplicationName() {
        CoordProperties properties = new CoordProperties();

        assertThat(properties.resolveNamespace(environment)).isEqualTo("seedj:default");
    }

    @Test
    void blankNamespaceUsesTheDefault() {
        CoordProperties properties = new CoordProperties();
        properties.setNamespace("  ");
        environment.setProperty("spring.application.name", "app");

        assertThat(properties.resolveNamespace(environment)).isEqualTo("seedj:app");
    }
}
