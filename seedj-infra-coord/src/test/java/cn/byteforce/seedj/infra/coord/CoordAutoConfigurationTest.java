package cn.byteforce.seedj.infra.coord;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.coord.sdk.CoordClient;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.lock.LockProvider;
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
    void createsLockProviderWhenCoordLockSelected() {
        runner.withPropertyValues("seedj.coord.enabled=true", "seedj.lock.provider=coord")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(LockProvider.class);
                            assertThat(context.getBean(LockProvider.class))
                                    .isInstanceOf(CoordLockProvider.class);
                        });
    }

    @Test
    void bindsLockHolderId() {
        runner.withPropertyValues(
                        "seedj.coord.enabled=true",
                        "seedj.lock.provider=coord",
                        "seedj.coord.lock.holder-id=node-a:42")
                .run(
                        context -> {
                            assertThat(
                                            context.getBean(CoordProperties.class)
                                                    .getLock()
                                                    .getHolderId())
                                    .isEqualTo("node-a:42");
                            assertThat(context).hasSingleBean(LockProvider.class);
                        });
    }

    @Test
    void leavesLockToOtherProviders() {
        runner.withPropertyValues("seedj.coord.enabled=true", "seedj.lock.provider=postgres")
                .run(context -> assertThat(context).doesNotHaveBean(LockProvider.class));
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
