package cn.byteforce.seedj.infra.local;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.ai.spi.DecisionAdmissionStore;
import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.clock.Clocks;
import cn.byteforce.seedj.cqrs.spi.CommandStore;
import cn.byteforce.seedj.domain.ObjectSchemaRegistry;
import cn.byteforce.seedj.domain.TemporalStore;
import cn.byteforce.seedj.infra.local.ai.InMemoryDecisionAdmissionStore;
import cn.byteforce.seedj.infra.local.cqrs.InMemoryCommandStore;
import cn.byteforce.seedj.infra.local.domain.InMemoryTemporalStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 内存适配器的 provider 选择与让位语义。 */
class InMemoryStoreAutoConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(InMemoryStoreAutoConfiguration.class))
                    .withBean(Clock.class, Clocks::system)
                    .withBean(ObjectSchemaRegistry.class, ObjectSchemaRegistry::empty);

    @Test
    @DisplayName("默认（无 JDBC）装配内存命令/对象存储与准入存储")
    void memoryIsTheDefault() {
        runner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CommandStore.class))
                            .isInstanceOf(InMemoryCommandStore.class);
                    assertThat(context.getBean(TemporalStore.class))
                            .isInstanceOf(InMemoryTemporalStore.class);
                    assertThat(context.getBean(DecisionAdmissionStore.class))
                            .isInstanceOf(InMemoryDecisionAdmissionStore.class);
                });
    }

    @Test
    @DisplayName("已有存储 bean 时不覆盖（PostgreSQL 适配器先注册）")
    void existingBeansWin() {
        CommandStore commands = new InMemoryCommandStore();
        TemporalStore objects = new InMemoryTemporalStore(Clocks.system(), ObjectSchemaRegistry.empty());

        runner.withBean(CommandStore.class, () -> commands)
                .withBean(TemporalStore.class, () -> objects)
                .run(
                        context -> {
                            assertThat(context.getBean(CommandStore.class)).isSameAs(commands);
                            assertThat(context.getBean(TemporalStore.class)).isSameAs(objects);
                        });
    }

    @Test
    @DisplayName("显式 provider=memory 时生效；enabled=false 时退出")
    void switchesAreHonoured() {
        runner.withPropertyValues(
                        "seedj.command.provider=memory", "seedj.object.provider=memory")
                .run(context -> assertThat(context).hasSingleBean(CommandStore.class));

        runner.withPropertyValues("seedj.object.enabled=false", "seedj.command.enabled=false")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(CommandStore.class);
                            assertThat(context).doesNotHaveBean(TemporalStore.class);
                        });
    }
}
