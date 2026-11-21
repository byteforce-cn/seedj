package cn.byteforce.seedj.infra.local.id;

import cn.byteforce.seedj.core.clock.Clock;

/**
 * 兼容入口：UUIDv7 实现已上移到 {@link cn.byteforce.seedj.id.Uuid7IdGenerator}。
 *
 * <p>契约层的命令/事件标识生成器不应依赖适配器模块，因此实现放在 {@code core}；本类保留给 既有装配与测试引用。
 *
 * @author ByteForce
 * @since 1.0.0
 * @deprecated 使用 {@link cn.byteforce.seedj.id.Uuid7IdGenerator}
 */
@Deprecated(forRemoval = false)
public final class Uuid7IdGenerator extends cn.byteforce.seedj.id.Uuid7IdGenerator {

    /**
     * 构造生成器。
     *
     * @param clock 取时入口（UTC、微秒精度）
     */
    public Uuid7IdGenerator(Clock clock) {
        super(clock);
    }
}
