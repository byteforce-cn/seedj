package cn.byteforce.seedj.lock.memory;

import cn.byteforce.seedj.lock.LockProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * 内存锁装配：<b>默认关闭</b>。
 *
 * <p>锁的能力一旦在多实例部署下退化成"进程内互斥"，任何并发错误都会静默发生。
 * 所以这里要求显式开启：{@code seedj.lock.memory.enabled=true}。
 * 生产环境请提供分布式实现（Redis / 数据库 / ZooKeeper），不要开启本配置。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "seedj.lock.memory", name = "enabled", havingValue = "true")
public class InMemoryLockAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public LockProvider inMemoryLockProvider() {
        return new InMemoryLockProvider();
    }
}
