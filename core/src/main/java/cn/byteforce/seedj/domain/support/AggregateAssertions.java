package cn.byteforce.seedj.domain.support;

import java.util.Objects;
import java.util.function.Function;

/**
 * 聚合的测试期断言。
 *
 * <p>
 * 只依赖 JDK，不需要 JUnit，也不启动容器：在纯单元测试或集成测试里都能用。
 */
public final class AggregateAssertions {

    private AggregateAssertions() {
    }

    /**
     * 校验「命令方法改出来的状态」与「重放事件得到的状态」一致。
     *
     * <p>
     * 这是唯一能在 CI 抓住下面这类静默错误的断言：
     * 
     * <pre>{@code
     * public void freeze() {
     *     this.frozen = true; // 改了状态，但没有 raise 事件
     * } // 内存里是 true，重放后是 false
     * }</pre>
     *
     * <p>
     * 典型用法（保存并重新加载之后）：
     * 
     * <pre>{@code
     * Account live = accounts.loadOrThrow(id);
     * live.freeze();
     * accounts.save(live);
     *
     * Account reloaded = accounts.loadOrThrow(id);
     * AggregateAssertions.assertReplayable(live, reloaded, Account::snapshotState);
     * }</pre>
     *
     * @param live       执行过命令的聚合实例
     * @param rehydrated 由事件重建的同 id 聚合实例
     * @param stateOf    提取可比较状态的方法，通常复用 {@code snapshotState()}
     * @throws AssertionError 两份状态不一致
     */
    public static <T> void assertReplayable(T live, T rehydrated, Function<T, ?> stateOf) {
        Objects.requireNonNull(live, "live aggregate must not be null");
        Objects.requireNonNull(rehydrated, "rehydrated aggregate must not be null");
        Objects.requireNonNull(stateOf, "stateOf must not be null");

        Object liveState = stateOf.apply(live);
        Object replayedState = stateOf.apply(rehydrated);
        if (Objects.equals(liveState, replayedState)) {
            return;
        }

        throw new AssertionError("""
                State after the command does not match the state rebuilt from events.
                  live    : %s
                  replayed: %s

                This usually means a command method mutated a field without raising an event,
                or apply(...) does not reproduce the same change as the command method.

                Fix: move the state change into apply(...) and call raise(new XxxEvent(...))
                     from the command method.
                """.formatted(liveState, replayedState));
    }
}
