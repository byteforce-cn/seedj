package cn.byteforce.seedj.core.clock;

/**
 * 时钟实现的能力声明。业务只能依赖 provider 声明级别的保证。
 */
public enum ClockGuarantee {

    /** 单实例内相邻调用严格递增，跨实例无保证。 */
    LOCAL_MONOTONIC(1),

    /** 跨节点因果一致（HLC 语义），并发事件不保证全序。 */
    CROSS_NODE_CAUSAL(2),

    /** 跨节点全序（需外部序列锚点），并发事件有确定顺序。 */
    CROSS_NODE_TOTAL_ORDER(3);

    private final int level;

    ClockGuarantee(int level) {
        this.level = level;
    }

    public boolean satisfies(ClockGuarantee required) {
        return this.level >= required.level;
    }
}
