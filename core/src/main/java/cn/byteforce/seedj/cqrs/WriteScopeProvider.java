package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.domain.ScopePredicate;

/**
 * 写范围提供者：与读路径共用同一套受限谓词。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@FunctionalInterface
public interface WriteScopeProvider {

    /**
     * 解析某对象类型的写范围。
     *
     * @param objectType 对象类型
     * @param caller     调用方
     * @return 谓词；不得为 {@code null}
     */
    ScopePredicate scope(String objectType, Caller caller);
}
