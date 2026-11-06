package cn.byteforce.seedj.core.security;

/**
 * 事实写入端：同一 key 第二次写入是错误，不做“后者覆盖”。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@FunctionalInterface
public interface ContextSink {

    /**
     * 写入一个事实。
     *
     * @param key   声明 key
     * @param value 值
     * @param <T>   值类型
     */
    <T> void put(ScopeKey<T> key, T value);
}
