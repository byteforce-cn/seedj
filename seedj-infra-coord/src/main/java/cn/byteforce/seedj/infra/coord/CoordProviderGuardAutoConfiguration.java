package cn.byteforce.seedj.infra.coord;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * provider 与 coord 总开关的一致性守卫。
 *
 * <p>{@code provider=coord} 但没有 {@code seedj.coord.enabled=true} 时启动失败，而不是让应用 悄悄退回本地实现。guard 只在
 * coord 未启用时装配。
 */
@AutoConfiguration
public class CoordProviderGuardAutoConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "seedj.coord",
            name = "enabled",
            havingValue = "false",
            matchIfMissing = true)
    CoordProviderGuard coordProviderGuard(Environment environment) {
        return new CoordProviderGuard(environment);
    }
}

final class CoordProviderGuard implements InitializingBean {

    private static final List<String> PROVIDER_KEYS =
            List.of(
                    "seedj.id.provider",
                    "seedj.numbering.provider",
                    "seedj.clock.provider",
                    "seedj.eventbus.transport",
                    "seedj.lock.provider",
                    "seedj.pki.provider",
                    "seedj.transit.provider",
                    "seedj.workflow.provider");

    private final Environment environment;

    CoordProviderGuard(Environment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    @Override
    public void afterPropertiesSet() {
        List<String> conflicts = new ArrayList<>();
        for (String key : PROVIDER_KEYS) {
            if ("coord".equalsIgnoreCase(environment.getProperty(key))) {
                conflicts.add(key);
            }
        }
        if (!conflicts.isEmpty()) {
            throw new IllegalStateException(
                    "coord provider selected but seedj.coord.enabled is not true: "
                            + String.join(", ", conflicts));
        }
    }
}
