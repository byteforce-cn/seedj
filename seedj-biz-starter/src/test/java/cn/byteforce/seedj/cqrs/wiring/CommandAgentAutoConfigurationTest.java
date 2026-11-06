package cn.byteforce.seedj.cqrs.wiring;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.lock.LockProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 事件溯源命令装配：缺锁默认失败，degraded-mode 才允许显式禁用。 */
class CommandAgentAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CommandAgentAutoConfiguration.class));

    @Test
    @DisplayName("缺 LockProvider：默认启动失败，消息指向 degraded-mode")
    void missingLockFailsFastByDefault() {
        runner.run(context -> assertThat(context.getStartupFailure())
                .hasMessageContaining("seedj.command.degraded-mode"));
    }

    @Test
    @DisplayName("degraded-mode=true：允许禁用锁并启动")
    void degradedModeAllowsDisabledLock() {
        runner.withPropertyValues("seedj.command.degraded-mode=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(CommandAgents.class);
        });
    }

    @Test
    @DisplayName("提供 LockProvider：默认装配成功")
    void lockProviderIsEnough() {
        runner.withBean(LockProvider.class, LockProvider::disabled).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(CommandAgents.class);
        });
    }
}
