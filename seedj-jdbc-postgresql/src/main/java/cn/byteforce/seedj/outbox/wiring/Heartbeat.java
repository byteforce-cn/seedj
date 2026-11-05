package cn.byteforce.seedj.outbox.wiring;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次投递期间的租约心跳。
 *
 * <p>{@code lost} 必须是共享的 {@link AtomicBoolean}：投递完成回调需要看到
 * 心跳线程后来修改的值。
 */
record Heartbeat(ScheduledFuture<?> future, AtomicBoolean lost) {

    static Heartbeat none() {
        return new Heartbeat(null, new AtomicBoolean(false));
    }

    boolean isLost() {
        return lost.get();
    }

    void cancel() {
        if (future != null && !future.isCancelled()) {
            future.cancel(false);
        }
    }
}
