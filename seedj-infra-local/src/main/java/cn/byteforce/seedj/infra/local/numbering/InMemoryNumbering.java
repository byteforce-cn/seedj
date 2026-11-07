package cn.byteforce.seedj.infra.local.numbering;

import cn.byteforce.seedj.numbering.Numbering;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存编号服务（单进程，不跨实例共享）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class InMemoryNumbering implements Numbering {

    private static final java.util.regex.Pattern SCOPE =
            java.util.regex.Pattern.compile("^[a-z0-9_]+$");

    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

    @Override
    public long next(String scope) {
        Objects.requireNonNull(scope, "scope");
        if (!SCOPE.matcher(scope).matches()) {
            throw new IllegalArgumentException("illegal numbering scope: " + scope);
        }
        return counters.computeIfAbsent(scope, ignored -> new AtomicLong()).incrementAndGet();
    }

    /** 清空全部计数器（测试用）。 */
    public void clear() {
        counters.clear();
    }
}
