package cn.byteforce.seedj.starter.coord.lock;

import cn.byteforce.coord.sdk.CoordClient;
import cn.byteforce.coord.sdk.lock.LockClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link CoordLockAutoConfiguration} 装配 / 回退测试。
 */
class CoordLockAutoConfigurationTest {

    private final CoordClient coordClient = mock(CoordClient.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CoordLockAutoConfiguration.class))
            .withBean(CoordClient.class, () -> {
                when(coordClient.lock()).thenReturn(mock(LockClient.class));
                return coordClient;
            });

    @Test
    void registersLockServiceWithDefaultHolderId() {
        runner.withPropertyValues("spring.application.name=demo-app").run(context -> {
            assertThat(context).hasSingleBean(CoordLockService.class);
            String holderId = context.getBean(CoordLockService.class).getHolderId();
            assertThat(holderId).startsWith("demo-app:");
            assertThat(holderId).contains(":");
        });
    }

    @Test
    void honoursExplicitHolderId() {
        runner.withPropertyValues("byteforce.coord.lock.holder-id=node-a:1").run(context ->
                assertThat(context.getBean(CoordLockService.class).getHolderId()).isEqualTo("node-a:1"));
    }

    @Test
    void backsOffWhenDisabled() {
        runner.withPropertyValues("byteforce.coord.lock.enabled=false").run(context ->
                assertThat(context).doesNotHaveBean(CoordLockService.class));
    }

    @Test
    void backsOffWithoutCoordClient() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CoordLockAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(CoordLockService.class));
    }
}
