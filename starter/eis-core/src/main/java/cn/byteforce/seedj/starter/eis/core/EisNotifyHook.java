package cn.byteforce.seedj.starter.eis.core;

import java.util.List;

/**
 * 失效/唤醒钩子 SPI：命令提交成功后（事务已提交）的事件通知。
 *
 * <p>语义边界（EIS 评估点 3/8）：<b>best-effort、fail-open、非投递承诺</b>——
 * 用于同域缓存失效/唤醒脉冲、outbox 唤醒等加速场景；丢失由 TTL/轮询兜底，
 * 绝不作为正确性路径。默认实现为空操作；coord Event 形态在应用侧接线。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface EisNotifyHook {

    /**
     * 已提交事件通知。
     *
     * @param events 已提交事件（不可变列表）
     */
    void onEvents(List<EisEvent> events);
}
