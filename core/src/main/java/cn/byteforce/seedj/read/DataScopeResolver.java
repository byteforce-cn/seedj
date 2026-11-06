package cn.byteforce.seedj.read;

import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.ScopeContext;
import cn.byteforce.seedj.domain.ScopePredicate;

/**
 * 行范围贡献者：业务规则与策略引擎都只是谓词的贡献者，多个结果 AND 收窄。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface DataScopeResolver {

    /**
     * 是否支持某对象类型。
     *
     * @param objectType 对象类型
     * @return 支持为 {@code true}
     */
    boolean supports(String objectType);

    /**
     * 执行顺序，升序；默认 0。
     *
     * @return 顺序
     */
    default int order() {
        return 0;
    }

    /**
     * 解析行范围。
     *
     * @param objectType 对象类型
     * @param caller     调用方
     * @param context    请求事实快照（不会为 {@code null}）
     * @return 谓词；不得为 {@code null}
     */
    ScopePredicate resolve(String objectType, Caller caller, ScopeContext context);
}
