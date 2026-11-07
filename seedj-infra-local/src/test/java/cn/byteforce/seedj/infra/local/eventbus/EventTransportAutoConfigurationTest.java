package cn.byteforce.seedj.infra.local.eventbus;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.eventbus.spi.EventBusTransport;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.infra.local.id.LocalStringIdGenerator;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class EventTransportAutoConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(EventTransportAutoConfiguration.class))
                    .withBean(IdGenerator.class, () -> new LocalStringIdGenerator());

    @Test
    @DisplayName("默认是磁盘队列（file）")
    void defaultsToFile() {
        runner.run(
                context ->
                        assertThat(context.getBean(EventBusTransport.class))
                                .isInstanceOf(FileEventBusTransport.class));
    }

    @Test
    @DisplayName("seedj.eventbus.transport=memory 选择进程内队列")
    void memory() {
        runner.withPropertyValues("seedj.eventbus.transport=memory")
                .run(
                        context ->
                                assertThat(context.getBean(EventBusTransport.class))
                                        .isInstanceOf(MemoryEventBusTransport.class));
    }

    @Test
    @DisplayName("seedj.eventbus.file.base-dir 生效")
    void fileBaseDir(@TempDir Path dir) {
        runner.withPropertyValues("seedj.eventbus.file.base-dir=" + dir)
                .run(
                        context ->
                                assertThat(
                                                ((FileEventBusTransport)
                                                                context.getBean(
                                                                        EventBusTransport.class))
                                                        .baseDir())
                                        .isEqualTo(dir.toAbsolutePath().normalize()));
    }

    @Test
    @DisplayName("seedj.eventbus.enabled=false 时不提供队列")
    void disabled() {
        runner.withPropertyValues("seedj.eventbus.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(EventBusTransport.class));
    }

    @Test
    @DisplayName("业务自定义 transport 优先")
    void userBeanWins() {
        runner.withBean(EventBusTransport.class, () -> new MemoryEventBusTransport(1))
                .withPropertyValues("seedj.eventbus.transport=file")
                .run(
                        context ->
                                assertThat(context.getBean(EventBusTransport.class))
                                        .isInstanceOf(MemoryEventBusTransport.class));
    }
}
