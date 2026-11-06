package cn.byteforce.seedj.starter.cqrs.wiring;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.cqrs.spi.CommandStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * PENDING 回收器装配。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration(after = TemporalCommandAutoConfiguration.class)
@ConditionalOnBean(CommandStore.class)
@EnableScheduling
public class CommandReaperAutoConfiguration {

    /**
     * 回收器。
     *
     * @param commands 命令存储
     * @param clock    取时入口
     * @param metrics  可选指标
     * @return 回收器
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "seedj.command", name = "reaper-enabled",
            havingValue = "true", matchIfMissing = true)
    public CommandReaper commandReaper(CommandStore commands, Clock clock,
                                       ObjectProvider<MeterRegistry> metrics) {
        return new CommandReaper(commands, clock, metrics.getIfAvailable());
    }
}
