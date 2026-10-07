package cn.byteforce.seedj.starter.coord.config.bootstrap;

import cn.byteforce.seedj.starter.coord.CoordProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataLocation;
import org.springframework.boot.context.config.ConfigDataLocationNotFoundException;
import org.springframework.boot.context.config.ConfigDataLocationResolverContext;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link CoordConfigDataLocationResolver} 解析语义测试（约定 C2）。
 */
class CoordConfigDataLocationResolverTest {

    private final CoordConfigDataLocationResolver resolver = new CoordConfigDataLocationResolver();

    private static ConfigDataLocationResolverContext context(MockEnvironment environment) {
        ConfigDataLocationResolverContext context = mock(ConfigDataLocationResolverContext.class);
        when(context.getBinder()).thenReturn(Binder.get(environment));
        return context;
    }

    @Test
    void resolvableOnlyForCoordPrefix() {
        assertThat(resolver.isResolvable(null, ConfigDataLocation.of("coord:a.b"))).isTrue();
        assertThat(resolver.isResolvable(null, ConfigDataLocation.of("optional:coord:a.b"))).isTrue();
        assertThat(resolver.isResolvable(null, ConfigDataLocation.of("classpath:/application.yml"))).isFalse();
    }

    @Test
    void resolvesPrefixesAndBindsConnectionProperties() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("byteforce.coord.agent-port", "59999");
        ConfigDataLocationResolverContext context = context(environment);

        var resources = resolver.resolve(context, ConfigDataLocation.of("coord:app.orders; app.common"));

        assertThat(resources).hasSize(1);
        CoordConfigDataResource resource = resources.get(0);
        assertThat(resource.getPrefixes()).containsExactly("app.orders", "app.common");
        assertThat(resource.isOptional()).isFalse();
        CoordProperties connection = resource.getConnectionProperties();
        assertThat(connection.getAgentPort()).isEqualTo(59999);
    }

    @Test
    void toleratesRepeatedCoordPrefixWithinSingleLocation() {
        ConfigDataLocationResolverContext context = context(new MockEnvironment());

        var resources = resolver.resolve(context, ConfigDataLocation.of("coord:app.orders;coord:app.common"));

        assertThat(resources.get(0).getPrefixes()).containsExactly("app.orders", "app.common");
    }

    @Test
    void optionalFlagIsPropagatedFromLocation() {
        ConfigDataLocationResolverContext context = context(new MockEnvironment());

        var resources = resolver.resolve(context, ConfigDataLocation.of("optional:coord:app.orders"));

        assertThat(resources.get(0).isOptional()).isTrue();
    }

    @Test
    void missingPrefixesIsRejected() {
        ConfigDataLocationResolverContext context = context(new MockEnvironment());

        assertThatThrownBy(() -> resolver.resolve(context, ConfigDataLocation.of("coord:")))
                .isInstanceOf(ConfigDataLocationNotFoundException.class);
        assertThatThrownBy(() -> resolver.resolve(context, ConfigDataLocation.of("coord: ; ")))
                .isInstanceOf(ConfigDataLocationNotFoundException.class);
    }

    @Test
    void resourceEqualsByPrefixesAndOptionality() {
        ConfigDataLocationResolverContext context = context(new MockEnvironment());
        var a = resolver.resolve(context, ConfigDataLocation.of("coord:p1")).get(0);
        var b = resolver.resolve(context, ConfigDataLocation.of("coord:p1")).get(0);
        var c = resolver.resolve(context, ConfigDataLocation.of("optional:coord:p1")).get(0);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }
}
