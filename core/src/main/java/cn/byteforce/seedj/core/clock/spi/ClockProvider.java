package cn.byteforce.seedj.core.clock.spi;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.clock.ClockGuarantee;

/**
 * 时钟 provider SPI。通过 ServiceLoader 或 Spring 注册。
 */
public interface ClockProvider {

    /** provider 名称，与配置对应。 */
    String name();

    /** 本 provider 能提供的最高能力级别。 */
    ClockGuarantee guarantee();

    /** 构造时钟；失败时抛出异常，由工厂决定降级。 */
    Clock create(ClockConfig config);

    /** 排序，数字小优先，用于降级链。 */
    default int order() {
        return 0;
    }
}
