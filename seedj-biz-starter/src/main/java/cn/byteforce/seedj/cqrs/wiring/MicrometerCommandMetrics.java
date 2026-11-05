package cn.byteforce.seedj.cqrs.wiring;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * micrometer 实现的命令指标。
 *
 * <p>Timer 在构造期按 {@code command|outcome} 缓存：热路径上只做一次 map 查找，
 * 不在每次命令里构造 {@code Meter.Id}。</p>
 */
final class MicrometerCommandMetrics implements CommandMetrics {

    private final MeterRegistry registry;

    private final ConcurrentMap<String, Timer> timers = new ConcurrentHashMap<>();

    MicrometerCommandMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void executed(String command, String outcome, Duration took) {
        timers.computeIfAbsent(command + '|' + outcome, key -> Timer.builder("seedj.command.execute.time")
                .tag("command", command)
                .tag("outcome", outcome)
                .register(registry)).record(took);
    }
}
