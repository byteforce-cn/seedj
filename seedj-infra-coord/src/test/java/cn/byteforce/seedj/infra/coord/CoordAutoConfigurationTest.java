package cn.byteforce.seedj.infra.coord;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.coord.sdk.CoordClient;
import cn.byteforce.seedj.id.IdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CoordAutoConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(
                                    CoordAutoConfiguration.class,
                                    CoordProviderGuardAutoConfiguration.class));

    @Test
    void createsClientAndIdGeneratorWhenEnabled() {
        runner.withPropertyValues(
                        "seedj.coord.enabled=true",
                        "seedj.id.provider=coord",
                        "seedj.coord.id.batch-size=2")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(CoordClient.class);
                            assertThat(context).hasSingleBean(IdGenerator.class);
                        });
    }

    @Test
    void failsFastWhenCoordProviderSelectedWithoutCoordEnabled() {
        runner.withPropertyValues("seedj.id.provider=coord")
                .run(
                        context -> {
                            Throwable failure = context.getStartupFailure();
                            assertThat(failure).isNotNull();
                            assertThat(failure)
                                    .hasRootCauseInstanceOf(IllegalStateException.class)
                                    .hasMessageContaining("seedj.coord.enabled");
                        });
    }
}
