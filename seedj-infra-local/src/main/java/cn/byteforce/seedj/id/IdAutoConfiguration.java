package cn.byteforce.seedj.id;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 标识生成装配。业务方注入 {@code IdGenerator<String>} 即可：
 * {@code idGenerator.id()}。
 *
 * <p>业务契约统一使用字符串 ID（数据库列为 {@code VARCHAR}，事件/审计幂等键也是
 * 字符串）；雪花只在适配器内部以 {@code long} 生成，再由
 * {@link StringIdGenerator} 转换，业务代码不感知。</p>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "seedj.id", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(IdProperties.class)
public class IdAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public IdGenerator<String> idGenerator(IdProperties properties) {
        long nodeId = properties.getNodeId() != null
                ? properties.getNodeId()
                : new NodeIdAllocator(properties.getNodeStateFile()).allocate();
        return new StringIdGenerator(new SnowflakeIdGenerator(nodeId, properties.getEpochMillis()));
    }
}
