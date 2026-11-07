package cn.byteforce.seedj.core.clock;

/**
 * 混合逻辑时钟扩展接口。
 *
 * <p>业务主路径仍只依赖 {@link Clock#now()}；只有需要传播因果关系的 组件（消息发送方/接收方、跨节点复制）才注入本接口，调用 {@link #nowTimestamp()}
 * 发送、{@link #merge(HybridTimestamp)} 接收。
 */
public interface HybridClock extends Clock {

    /** 当前混合时间戳（物理 + 逻辑），用于随消息发送。 */
    HybridTimestamp nowTimestamp();

    /** 合并远端时间戳，推进本地 HLC 状态。 */
    void merge(HybridTimestamp remote);
}
