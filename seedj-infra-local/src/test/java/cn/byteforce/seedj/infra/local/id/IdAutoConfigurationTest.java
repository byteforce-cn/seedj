package cn.byteforce.seedj.infra.local.id;

import cn.byteforce.seedj.id.IdGenerator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class IdAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IdAutoConfiguration.class));

    @Test
    @DisplayName("默认提供 IdGenerator，多实例从共享文件领取不同节点编号")
    void providesGenerator(@TempDir Path dir) {
        String nodes = dir.resolve("nodes.properties").toString();

        runner.withPropertyValues("seedj.id.node-state-file=" + nodes).run(first -> {
            IdGenerator<String> a = first.getBeanProvider(IdGenerator.class).getObject();
            runner.withPropertyValues("seedj.id.node-state-file=" + nodes).run(second -> {
                IdGenerator<String> b = second.getBeanProvider(IdGenerator.class).getObject();
                assertThat(nodeOf(a)).isNotEqualTo(nodeOf(b));
            });
        });
    }

    @Test
    @DisplayName("seedj.id.node-id 显式配置优先")
    void explicitNodeId() {
        runner.withPropertyValues("seedj.id.node-id=42").run(context ->
                assertThat(nodeOf(context.getBeanProvider(IdGenerator.class).getObject())).isEqualTo(42));
    }

    @Test
    @DisplayName("seedj.id.enabled=false 时不提供 bean")
    void disabled() {
        runner.withPropertyValues("seedj.id.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(IdGenerator.class));
    }

    private static long nodeOf(IdGenerator<String> generator) {
        return Long.parseLong(generator.id()) >> 12 & 0x3FF;
    }
}
