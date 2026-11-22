package cn.byteforce.seedj.infra.local.im;

import cn.byteforce.seedj.im.ImEnabledMarker;
import cn.byteforce.seedj.im.spi.ImCardActionStore;
import cn.byteforce.seedj.im.spi.ImConversationStore;
import cn.byteforce.seedj.im.spi.ImCursorStore;
import cn.byteforce.seedj.im.spi.ImMessageStore;
import cn.byteforce.seedj.im.spi.ImSequenceAllocator;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * 内存 IM 存储：{@code seedj.im.store=memory}（缺省）时由适配器自带装配。
 *
 * <p>starter 只消费 {@code Im*Store} SPI；{@code store=jdbc} 时本配置整体退出，由 PostgreSQL 适配器接管。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration(afterName = "cn.byteforce.seedj.starter.im.ImStoreAutoConfiguration")
@ConditionalOnBean(ImEnabledMarker.class)
@ConditionalOnProperty(
        prefix = "seedj.im",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@ConditionalOnProperty(
        prefix = "seedj.im",
        name = "store",
        havingValue = "memory",
        matchIfMissing = true)
public class InMemoryImStoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ImSequenceAllocator imSequenceAllocator() {
        return new AtomicLongSequenceAllocator();
    }

    @Bean
    @ConditionalOnMissingBean
    ImConversationStore imConversationStore(ImSequenceAllocator numbers, Clock clock) {
        return new InMemoryImConversationStore(numbers, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    ImMessageStore imMessageStore(Clock clock) {
        return new InMemoryImMessageStore(clock);
    }

    @Bean
    @ConditionalOnMissingBean
    ImCursorStore imCursorStore() {
        return new InMemoryImCursorStore();
    }

    @Bean
    @ConditionalOnMissingBean
    ImCardActionStore imCardActionStore() {
        return new InMemoryImCardActionStore();
    }
}
