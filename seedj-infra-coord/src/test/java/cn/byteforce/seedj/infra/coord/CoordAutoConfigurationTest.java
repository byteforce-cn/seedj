package cn.byteforce.seedj.infra.coord;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.coord.sdk.CoordClient;
import cn.byteforce.seedj.eventbus.spi.EventConsumerStore;
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
    void createsConsumerStoreForCoordTransport() {
        runner.withPropertyValues("seedj.coord.enabled=true", "seedj.eventbus.transport=coord")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(EventConsumerStore.class);
                            assertThat(context.getBean(EventConsumerStore.class))
                                    .isInstanceOf(CoordEventConsumerStore.class);
                        });
    }

    @Test
    void bindsConsumerTuning() {
        runner.withPropertyValues(
                        "seedj.coord.enabled=true",
                        "seedj.eventbus.transport=coord",
                        "seedj.coord.eventbus.consumer-max-messages=50",
                        "seedj.coord.eventbus.consumer-scan-limit=200")
                .run(
                        context -> {
                            CoordProperties properties = context.getBean(CoordProperties.class);
                            assertThat(properties.getEventbus().getConsumerMaxMessages())
                                    .isEqualTo(50);
                            assertThat(properties.getEventbus().getConsumerScanLimit())
                                    .isEqualTo(200);
                        });
    }

    @Test
    void leavesConsumerStoreToFileTransport() {
        runner.withPropertyValues("seedj.coord.enabled=true", "seedj.eventbus.transport=file")
                .run(context -> assertThat(context).doesNotHaveBean(EventConsumerStore.class));
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
