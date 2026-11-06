package cn.byteforce.seedj.infra.local.audit;

import cn.byteforce.seedj.audit.spi.AuditStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * 内存审计存储装配：只在 {@code seedj.audit.provider=memory} 时生效。
 *
 * <p><b>仅开发/测试</b>：进程退出即丢失，不能作为合规证据；与内存 eventbus 同级。</p>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "seedj.audit", name = "provider", havingValue = "memory")
public class InMemoryAuditAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AuditStore<String> inMemoryAuditStore() {
        return new InMemoryAuditStore();
    }
}
