package cn.byteforce.seedj.starter.coord.lock;

import cn.byteforce.coord.sdk.CoordClient;
import cn.byteforce.coord.sdk.lock.LockClient;
import cn.byteforce.seedj.starter.coord.CoordAutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Coord 锁服务自动配置（Phase 2，C3）：专用 API + 看门狗续约 + 失锁回调。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnClass({CoordClient.class, LockClient.class})
@AutoConfigureAfter(CoordAutoConfiguration.class)
@ConditionalOnBean(CoordClient.class)
@ConditionalOnProperty(
        prefix = "byteforce.coord.lock",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
@EnableConfigurationProperties(CoordLockProperties.class)
public class CoordLockAutoConfiguration {

    /**
     * 锁服务（bean 销毁时关闭看门狗调度器）。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public CoordLockService coordLockService(CoordClient coordClient, CoordLockProperties properties,
                                             Environment environment,
                                             ObjectProvider<CoordLockLostListener> listeners) {
        return new CoordLockService(coordClient.lock(), resolveHolderId(properties, environment),
                properties.getTtl(), listeners.orderedStream().toList());
    }

    /**
     * 解析 holder_id（C3）：显式配置 &gt; {@code {app}:{hostname}:{pid}}。
     */
    static String resolveHolderId(CoordLockProperties properties, Environment environment) {
        if (StringUtils.hasText(properties.getHolderId())) {
            return properties.getHolderId().trim();
        }
        String app = environment.getProperty("spring.application.name", "app");
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "localhost";
        }
        return app + ":" + host + ":" + ProcessHandle.current().pid();
    }
}
