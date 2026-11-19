package cn.byteforce.seedj.cqrs;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 当前请求的决策上下文：命令事务据此设置 {@code agent.decision_id}，审计据此写直链。
 *
 * <p>线程级、由 guard 在准入成功后打开、请求结束关闭；非 web/异步线程没有上下文时所有读取方必须 no-op。
 * 这不是事实来源——事实在账本里；它只是把「这一条 HTTP 请求对应哪个 decision」带到同一线程的后续组件。
 *
 * @param decisionId 决策标识
 * @param ledgerIntentId 账本意图记录标识
 * @param commandType 命令类型
 * @param argsHash 参数指纹
 * @author ByteForce
 * @since 1.0.0
 */
public record DecisionContext(
        UUID decisionId, UUID ledgerIntentId, String commandType, String argsHash) {

    private static final ThreadLocal<DecisionContext> CURRENT = new ThreadLocal<>();

    public DecisionContext {
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(ledgerIntentId, "ledgerIntentId");
        Objects.requireNonNull(commandType, "commandType");
        Objects.requireNonNull(argsHash, "argsHash");
    }

    /**
     * 当前线程的决策上下文。
     *
     * @return 上下文；未设置时为空
     */
    public static Optional<DecisionContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * 打开一个上下文作用域；关闭时恢复上一值（没有则清除）。
     *
     * @param context 上下文
     * @return try-with-resources 作用域
     */
    public static Scope open(DecisionContext context) {
        Objects.requireNonNull(context, "context");
        DecisionContext previous = CURRENT.get();
        CURRENT.set(context);
        return new Scope(previous);
    }

    /**
     * 上下文作用域。
     *
     * @param previous 打开前的值；{@code null} 表示之前没有
     */
    public record Scope(DecisionContext previous) implements AutoCloseable {

        @Override
        public void close() {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
