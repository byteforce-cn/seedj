package cn.byteforce.seedj.domain;

/**
 * 追加类型。
 *
 * <p>{@link #CORRECTION} 是常规更正：业务时点通常落在当前或未来；
 * {@link #LATE_FACT} 是迟到事实：业务时点早于当前系统时间，允许其有效区间
 * 覆盖历史区间，但只封口系统时间轴。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public enum AppendKind {

    /** 创建：对象不得已存在。 */
    CREATE,

    /** 常规更正：对象必须已存在。 */
    CORRECTION,

    /** 迟到事实追加。 */
    LATE_FACT
}
