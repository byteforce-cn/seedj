package cn.byteforce.seedj.starter.eis.core;

import java.util.Set;

/**
 * 规则 SPI：在处理器之前执行的领域不变量（对应扩展 {@code rules/*.yaml}）。
 *
 * <p>规则按 Spring {@code @Order} 排序执行；违规抛
 * {@link EisCommandRejectedException}（领域错误码；SoD 类附 {@code reason_code}）。
 * 规则可以读取对象存储（如唯一性检查），但不得产生副作用。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface EisRule {

    /**
     * 适用的命令类型集合。
     *
     * @return 命令类型集合（不可空）
     */
    Set<String> commandTypes();

    /**
     * 执行规则校验。
     *
     * @param context 受理上下文
     */
    void enforce(EisCommandContext context);
}
