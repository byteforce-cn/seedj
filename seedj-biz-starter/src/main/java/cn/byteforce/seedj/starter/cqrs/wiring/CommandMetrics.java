package cn.byteforce.seedj.starter.cqrs.wiring;

import java.time.Duration;

/**
 * 命令执行的指标 seam：有 micrometer 时由装配层注入真实实现，否则用 no-op。
 *
 * <p>{@code command} 用命令类简单名（代码常量，基数有界）；{@code outcome} 只有 {@code accepted}/{@code rejected}/{@code
 * conflict}/{@code error} 四种。
 */
public interface CommandMetrics {

    void executed(String command, String outcome, Duration took);

    static CommandMetrics noop() {
        return (command, outcome, took) -> {};
    }
}
