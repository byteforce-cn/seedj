package cn.byteforce.seedj.starter.coord;

import cn.byteforce.coord.sdk.CoordClient;
import cn.byteforce.coord.sdk.health.HealthStatus;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Coord Agent 连接健康指示器（Actuator）。
 *
 * <p>映射：{@link CoordClient#healthCheck()} 返回 {@code SERVING} → {@code UP}；
 * {@code NOT_SERVING}（Agent 不可达 / 超时 / 协议不匹配）→ {@code DOWN}。
 * healthCheck 会顺带完成一次协议协商（幂等、只做一次）。</p>
 *
 * <p>Bean 名 {@code coordHealthIndicator}；通过 {@code management.health.coord.enabled=false}
 * 关闭注册。仅当 classpath 存在 Actuator 健康 SPI（spring-boot-health）时由
 * {@link CoordAutoConfiguration} 注册。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class CoordHealthIndicator implements HealthIndicator {

    private final CoordClient coordClient;

    /**
     * 构造健康指示器。
     *
     * @param coordClient SDK 客户端
     */
    public CoordHealthIndicator(CoordClient coordClient) {
        this.coordClient = coordClient;
    }

    @Override
    public Health health() {
        if (coordClient.healthCheck() == HealthStatus.SERVING) {
            return Health.up()
                    .withDetail("protocolVersions", coordClient.agentProtocolVersions())
                    .build();
        }
        return Health.down()
                .withDetail("hint", "agent unreachable, timed out, or protocol mismatch (see SDK debug logs)")
                .build();
    }
}
