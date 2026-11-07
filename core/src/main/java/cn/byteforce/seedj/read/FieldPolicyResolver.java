package cn.byteforce.seedj.read;

import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.ScopeContext;

/**
 * 字段策略贡献者：只能收窄默认策略。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface FieldPolicyResolver {

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
     * 解析字段策略。
     *
     * @param objectType 对象类型
     * @param caller 调用方
     * @param context 请求事实快照（不会为 {@code null}）
     * @return 策略；不得为 {@code null}
     */
    FieldPolicy resolve(String objectType, Caller caller, ScopeContext context);
}
