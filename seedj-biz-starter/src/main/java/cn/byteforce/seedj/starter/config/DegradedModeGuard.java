package cn.byteforce.seedj.starter.config;

import java.util.Arrays;
import java.util.Objects;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;

/**
 * 降级开关的统一守卫：{@code degraded-mode} / {@code dev-auth} 只允许在 local/test 打开。
 *
 * <p>允许 profile 由 {@code seedj.degraded.allowed-profiles} 覆盖，默认 {@code local,test}； 判断用允许列表，不是“匹配
 * prod”。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class DegradedModeGuard implements InitializingBean {

    private final Environment environment;

    /**
     * 构造守卫。
     *
     * @param environment Spring 环境
     */
    public DegradedModeGuard(Environment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    @Override
    public void afterPropertiesSet() {
        boolean degraded =
                environment.getProperty("seedj.command.degraded-mode", Boolean.class, false);
        boolean devAuth =
                environment.getProperty("seedj.biz.dev-auth.enabled", Boolean.class, false);
        if (!degraded && !devAuth) {
            return;
        }
        String allowed = environment.getProperty("seedj.degraded.allowed-profiles", "local,test");
        boolean permitted =
                Arrays.stream(allowed.split(","))
                        .map(String::trim)
                        .filter(profile -> !profile.isBlank())
                        .anyMatch(environment::matchesProfiles);
        if (!permitted) {
            throw new IllegalStateException(
                    "degraded-mode/dev-auth is only allowed in profiles "
                            + allowed
                            + "; active profiles: "
                            + Arrays.toString(environment.getActiveProfiles()));
        }
    }
}
