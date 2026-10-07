package cn.byteforce.seedj.core.context;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * 请求上下文：当前请求的链路追踪与身份信息（traceId / userId / tenantId / username）。
 *
 * <p>由 Web 运行时（starter-web 的过滤器）或安全接入（starter-resource 的身份贡献器）
 * 在请求进入时写入，请求结束时清理；业务代码通过静态访问器读取：</p>
 *
 * <pre>{@code
 * Optional<Long> userId = RequestContext.userId();
 * Optional<Long> tenantId = RequestContext.tenantId();
 * }</pre>
 *
 * <p><b>异步传播</b>：上下文基于 {@link ThreadLocal}，不跨线程自动传播；
 * 提交到线程池的任务必须使用 {@link #wrap(Runnable)} / {@link #wrap(Callable)} 包装：</p>
 *
 * <pre>{@code
 * executor.submit(RequestContext.wrap(() -> doSomething()));
 * }</pre>
 *
 * <p><b>清理责任</b>：写入方（过滤器）必须在请求结束时调用 {@link #clear()}，
 * 避免线程复用造成上下文串扰。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class RequestContext {

    /**
     * MDC 键：链路追踪 ID。
     */
    public static final String MDC_TRACE_ID = "traceId";

    /**
     * MDC 键：用户 ID。
     */
    public static final String MDC_USER_ID = "userId";

    /**
     * MDC 键：租户 ID。
     */
    public static final String MDC_TENANT_ID = "tenantId";

    /**
     * MDC 键：用户名。
     */
    public static final String MDC_USERNAME = "username";

    private static final ThreadLocal<RequestContext> CURRENT = new ThreadLocal<>();

    private String traceId;

    private Long userId;

    private Long tenantId;

    private String username;

    private RequestContext() {
    }

    /**
     * 返回当前请求的链路追踪 ID。
     *
     * @return traceId；未设置时为空
     */
    public static Optional<String> traceId() {
        return Optional.ofNullable(peek()).map(ctx -> ctx.traceId);
    }

    /**
     * 设置链路追踪 ID（空白串视为清除）。
     *
     * @param traceId 链路追踪 ID
     */
    public static void setTraceId(String traceId) {
        obtain().traceId = normalize(traceId);
    }

    /**
     * 返回当前请求的用户 ID。
     *
     * @return 用户 ID；未设置时为空
     */
    public static Optional<Long> userId() {
        return Optional.ofNullable(peek()).map(ctx -> ctx.userId);
    }

    /**
     * 设置用户 ID（{@code null} 视为清除）。
     *
     * @param userId 用户 ID
     */
    public static void setUserId(Long userId) {
        obtain().userId = userId;
    }

    /**
     * 返回当前请求的租户 ID。
     *
     * @return 租户 ID；未设置时为空
     */
    public static Optional<Long> tenantId() {
        return Optional.ofNullable(peek()).map(ctx -> ctx.tenantId);
    }

    /**
     * 设置租户 ID（{@code null} 视为清除）。
     *
     * @param tenantId 租户 ID
     */
    public static void setTenantId(Long tenantId) {
        obtain().tenantId = tenantId;
    }

    /**
     * 返回当前请求的用户名。
     *
     * @return 用户名；未设置时为空
     */
    public static Optional<String> username() {
        return Optional.ofNullable(peek()).map(ctx -> ctx.username);
    }

    /**
     * 设置用户名（空白串视为清除）。
     *
     * @param username 用户名
     */
    public static void setUsername(String username) {
        obtain().username = normalize(username);
    }

    /**
     * 一次性设置身份三元组（由身份解析方调用；各参数可为 {@code null} 表示清除对应项）。
     *
     * @param userId   用户 ID
     * @param tenantId 租户 ID
     * @param username 用户名
     */
    public static void setIdentity(Long userId, Long tenantId, String username) {
        RequestContext ctx = obtain();
        ctx.userId = userId;
        ctx.tenantId = tenantId;
        ctx.username = normalize(username);
    }

    /**
     * 清空当前线程的全部上下文（请求收尾必须调用）。
     */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * 包装 {@link Runnable}：运行时恢复调用方捕获的上下文，结束后还原线程原上下文。
     *
     * @param task 目标任务
     * @return 携带上下文的包装任务
     */
    public static Runnable wrap(Runnable task) {
        Objects.requireNonNull(task, "task 不能为 null");
        Snapshot captured = snapshot();
        return () -> {
            Snapshot previous = snapshot();
            restore(captured);
            try {
                task.run();
            } finally {
                restore(previous);
            }
        };
    }

    /**
     * 包装 {@link Callable}：运行时恢复调用方捕获的上下文，结束后还原线程原上下文。
     *
     * @param task 目标任务
     * @param <T>  返回值类型
     * @return 携带上下文的包装任务
     */
    public static <T> Callable<T> wrap(Callable<T> task) {
        Objects.requireNonNull(task, "task 不能为 null");
        Snapshot captured = snapshot();
        return () -> {
            Snapshot previous = snapshot();
            restore(captured);
            try {
                return task.call();
            } finally {
                restore(previous);
            }
        };
    }

    private static RequestContext peek() {
        return CURRENT.get();
    }

    private static RequestContext obtain() {
        RequestContext ctx = CURRENT.get();
        if (ctx == null) {
            ctx = new RequestContext();
            CURRENT.set(ctx);
        }
        return ctx;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Snapshot snapshot() {
        RequestContext ctx = peek();
        if (ctx == null) {
            return null;
        }
        return new Snapshot(ctx.traceId, ctx.userId, ctx.tenantId, ctx.username);
    }

    private static void restore(Snapshot snapshot) {
        if (snapshot == null) {
            clear();
            return;
        }
        RequestContext ctx = obtain();
        ctx.traceId = snapshot.traceId;
        ctx.userId = snapshot.userId;
        ctx.tenantId = snapshot.tenantId;
        ctx.username = snapshot.username;
    }

    /**
     * 上下文快照（供 {@link #wrap} 传播使用）。
     */
    private static final class Snapshot {

        private final String traceId;

        private final Long userId;

        private final Long tenantId;

        private final String username;

        private Snapshot(String traceId, Long userId, Long tenantId, String username) {
            this.traceId = traceId;
            this.userId = userId;
            this.tenantId = tenantId;
            this.username = username;
        }
    }
}
