package cn.byteforce.seedj.core.clock.spi;

import cn.byteforce.seedj.core.clock.ClockGuarantee;
import java.util.Map;

/** 时钟装配配置。 */
public record ClockConfig(
        String provider, ClockGuarantee required, String nodeId, Map<String, String> options) {

    public ClockConfig {
        options = options == null ? Map.of() : Map.copyOf(options);
    }
}
