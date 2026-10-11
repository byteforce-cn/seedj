package cn.byteforce.seedj.starter.eis.temporal;

import cn.byteforce.seedj.starter.eis.core.EisObjectRepository;
import cn.byteforce.seedj.starter.eis.core.EisObjectTypeRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link EisTemporalAutoConfiguration} 装配边界测试。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class EisTemporalAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EisTemporalAutoConfiguration.class))
            .withBean("eisObjectRepository", EisObjectRepository.class, () -> mock(EisObjectRepository.class))
            .withBean("eisObjectTypeRegistry", EisObjectTypeRegistry.class,
                    () -> new EisObjectTypeRegistry(List.of()));

    @Test
    void registersControllerAndHandler() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(EisTemporalController.class);
            assertThat(context).hasSingleBean(EisTemporalExceptionHandler.class);
        });
    }

    @Test
    void disabledByPropertyRegistersNothing() {
        runner.withPropertyValues("byteforce.eis.temporal.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(EisTemporalController.class);
                });
    }
}
