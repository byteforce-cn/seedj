package cn.byteforce.seedj.infra.local.lock;

import cn.byteforce.seedj.lock.LockProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内存锁装配：<b>默认不生效</b>。
 *
 * <p>锁的默认值不能是"进程内互斥"：单实例测试全绿、多实例静默失效，是这类能力最坏的
 * 失效模式。所以必须显式开启，生产环境应提供分布式实现。
 */
class InMemoryLockAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(InMemoryLockAutoConfiguration.class));

    @Test
    @DisplayName("默认不注册 LockProvider")
    void disabledByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(LockProvider.class);
        });
    }

    @Test
    @DisplayName("seedj.lock.memory.enabled=true 时才注册")
    void registersWhenExplicitlyEnabled() {
        runner.withPropertyValues("seedj.lock.memory.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(LockProvider.class);
        });
    }

    @Test
    @DisplayName("已存在 provider 时不覆盖")
    void backsOffWhenProviderExists() {
        runner.withPropertyValues("seedj.lock.memory.enabled=true")
                .withBean(LockProvider.class, LockProvider::disabled)
                .run(context -> assertThat(context).hasSingleBean(LockProvider.class));
    }
}
