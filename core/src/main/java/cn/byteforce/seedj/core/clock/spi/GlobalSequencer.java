package cn.byteforce.seedj.core.clock.spi;

/**
 * 全局序列锚点，由业务按中间件实现。
 * 仅在需要全序保证时提供。
 */
public interface GlobalSequencer {

    /** 返回全局单调递增序列。 */
    long next();
}
