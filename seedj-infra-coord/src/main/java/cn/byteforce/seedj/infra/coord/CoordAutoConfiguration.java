package cn.byteforce.seedj.infra.coord;

import cn.byteforce.coord.sdk.CoordClient;
import cn.byteforce.coord.sdk.CoordConfig;
import cn.byteforce.seedj.core.clock.spi.GlobalSequencer;
import cn.byteforce.seedj.eventbus.spi.EventBusTransport;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.numbering.Numbering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * coord 基础设施装配。
 *
 * <p>只在 {@code seedj.coord.enabled=true} 时创建 {@link CoordClient}；各能力再由 {@code
 * seedj.<cap>.provider=coord} 显式选择。没有本地实现兜底：provider=coord 而 coord 未启用时由 {@link CoordProviderGuard}
 * 在启动期失败。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "seedj.coord", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(CoordProperties.class)
public class CoordAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public CoordClient coordClient(CoordProperties properties) {
        validate(properties);
        return CoordClient.create(toConfig(properties));
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "seedj.id", name = "provider", havingValue = "coord")
    public IdGenerator<String> coordIdGenerator(
            CoordClient client, CoordProperties properties, Environment environment) {
        return new CoordIdGenerator(
                client.idgen(),
                namespace(properties, environment) + ":" + properties.getId().getName(),
                properties.getId().getBatchSize());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "seedj.numbering", name = "provider", havingValue = "coord")
    public Numbering coordNumbering(
            CoordClient client, CoordProperties properties, Environment environment) {
        return new CoordNumbering(
                client.idgen(),
                namespace(properties, environment) + ":" + properties.getNumbering().getPrefix());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "seedj.clock", name = "provider", havingValue = "coord")
    public GlobalSequencer coordGlobalSequencer(
            CoordClient client, CoordProperties properties, Environment environment) {
        return new CoordGlobalSequencer(
                client.idgen(),
                namespace(properties, environment) + ":" + properties.getClock().getName());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(EventBusTransport.class)
    @ConditionalOnProperty(prefix = "seedj.eventbus", name = "transport", havingValue = "coord")
    public EventBusTransport coordEventBusTransport(
            CoordClient client, CoordProperties properties, Environment environment) {
        return new CoordEventBusTransport(
                client.mqV2(),
                producer(properties, environment),
                properties.getEventbus().getPartitions());
    }

    private static CoordConfig toConfig(CoordProperties properties) {
        CoordProperties.Tls tls = properties.getTls();
        CoordConfig.Builder builder =
                CoordConfig.builder()
                        .agentHost(properties.getAgentHost())
                        .agentPort(properties.getAgentPort())
                        .requestTimeout(properties.getRequestTimeout())
                        .autoRestoreWatches(properties.isAutoRestoreWatches())
                        .heartbeatThreads(properties.getHeartbeatThreads())
                        .useTls(tls.isEnabled())
                        .allowInsecurePlaintext(properties.isAllowInsecurePlaintext());
        if (tls.isEnabled()) {
            builder.tlsCaCertPath(tls.getCaCertPath())
                    .tlsClientCertPath(tls.getClientCertPath())
                    .tlsClientKeyPath(tls.getClientKeyPath());
        }
        Supplier<String> token = authTokenSupplier(properties);
        if (token != null) {
            builder.authTokenSupplier(token);
        }
        return builder.build();
    }

    private static Supplier<String> authTokenSupplier(CoordProperties properties) {
        String token = properties.getAuth().getToken();
        if (token != null && !token.isBlank()) {
            return () -> token;
        }
        String tokenFile = properties.getAuth().getTokenFile();
        if (tokenFile != null && !tokenFile.isBlank()) {
            Path path = Path.of(tokenFile);
            return () -> {
                try {
                    return Files.readString(path).trim();
                } catch (IOException e) {
                    throw new IllegalStateException(
                            "cannot read coord auth token file: " + path, e);
                }
            };
        }
        String envToken = System.getenv("COORD_AUTH_TOKEN");
        if (envToken != null && !envToken.isBlank()) {
            return () -> envToken;
        }
        return null;
    }

    private static void validate(CoordProperties properties) {
        if (properties.getAgentHost() == null || properties.getAgentHost().isBlank()) {
            throw new IllegalStateException("seedj.coord.agent-host must not be blank");
        }
        if (properties.getAgentPort() < 1 || properties.getAgentPort() > 65_535) {
            throw new IllegalStateException("seedj.coord.agent-port must be in 1..65535");
        }
        Duration timeout = properties.getRequestTimeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalStateException("seedj.coord.request-timeout must be positive");
        }
        if (properties.getHeartbeatThreads() < 0) {
            throw new IllegalStateException("seedj.coord.heartbeat-threads must be >= 0");
        }
        if (properties.getId().getName() == null || properties.getId().getName().isBlank()) {
            throw new IllegalStateException("seedj.coord.id.name must not be blank");
        }
        if (properties.getId().getBatchSize() < 1 || properties.getId().getBatchSize() > 10_000) {
            throw new IllegalStateException("seedj.coord.id.batch-size must be in 1..10000");
        }
        if (properties.getNumbering().getPrefix() == null
                || properties.getNumbering().getPrefix().isBlank()) {
            throw new IllegalStateException("seedj.coord.numbering.prefix must not be blank");
        }
        if (properties.getClock().getName() == null || properties.getClock().getName().isBlank()) {
            throw new IllegalStateException("seedj.coord.clock.name must not be blank");
        }
        if (properties.getEventbus().getPartitions() < 1) {
            throw new IllegalStateException("seedj.coord.eventbus.partitions must be >= 1");
        }
    }

    private static String namespace(CoordProperties properties, Environment environment) {
        String namespace = properties.getNamespace();
        if (namespace == null || namespace.isBlank()) {
            return "seedj:" + environment.getProperty("spring.application.name", "default");
        }
        return namespace;
    }

    private static String producer(CoordProperties properties, Environment environment) {
        String producer = properties.getEventbus().getProducer();
        if (producer == null || producer.isBlank()) {
            return environment.getProperty("spring.application.name", "seedj");
        }
        return producer;
    }
}
