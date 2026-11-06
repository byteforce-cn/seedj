package cn.byteforce.seedj.core.security;

/**
 * 事实解析扩展点：把调用方转换为一次请求的不可变快照。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@FunctionalInterface
public interface ContextResolver {

    /**
     * 解析。
     *
     * @param caller 调用方
     * @return 快照
     */
    ScopeContext resolve(Caller caller);
}
