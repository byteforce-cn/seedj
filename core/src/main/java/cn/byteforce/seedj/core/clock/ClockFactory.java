package cn.byteforce.seedj.core.clock;

import cn.byteforce.seedj.core.clock.spi.ClockConfig;
import cn.byteforce.seedj.core.clock.spi.ClockProvider;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** 时钟装配工厂：按配置选择 provider，失败时显式降级并告警。 */
public final class ClockFactory {

    private ClockFactory() {}

    public static Clock resolve(ClockConfig config, List<ClockProvider> providers) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(providers, "providers");

        ClockProvider selected =
                providers.stream()
                        .filter(p -> p.name().equals(config.provider()))
                        .findFirst()
                        .orElse(null);

        if (selected != null) {
            ClockGuarantee provided = selected.guarantee();
            ClockGuarantee required = config.required();
            if (required != null && !provided.satisfies(required)) {
                throw new IllegalStateException(
                        "provider "
                                + selected.name()
                                + " guarantee "
                                + provided
                                + " does not satisfy required "
                                + required);
            }
            try {
                Clock clock = selected.create(config);
                log("selected provider=" + selected.name() + " guarantee=" + provided);
                return clock;
            } catch (RuntimeException ex) {
                log("provider " + selected.name() + " failed, falling back: " + ex);
            }
        }

        Clock fallback = Clocks.monotonic(Clocks.system());
        log("fallback to monotonic(system())");
        return fallback;
    }

    private static void log(String message) {
        // 生产替换为结构化日志；此处保持零依赖
        System.out.println("[ClockFactory] " + message);
    }

    /** 供降级链排序使用。 */
    public static List<ClockProvider> ordered(List<ClockProvider> providers) {
        return providers.stream().sorted(Comparator.comparingInt(ClockProvider::order)).toList();
    }
}
