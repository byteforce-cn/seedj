package cn.byteforce.seedj.infra.local.id;

import cn.byteforce.seedj.id.IdErrorCode;
import cn.byteforce.seedj.id.IdException;
import cn.byteforce.seedj.id.IdGenerator;

import java.util.Objects;

/**
 * 把数值型 ID 适配为字符串 ID。
 *
 * <p>业务契约（IAM、outbox、eventbus）使用 {@code String} 标识，而雪花产出
 * {@code long}。本类负责唯一的转换点，避免各模块各自调用
 * {@code Long.toString(...)} 或退回 {@code UUID.randomUUID()}。</p>
 *
 * <p><b>幂等关闭</b>：委托生成器只会被关闭一次。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class StringIdGenerator implements IdGenerator<String> {

    private final IdGenerator<Long> delegate;

    private volatile boolean closed;

    /**
     * 构造字符串 ID 生成器。
     *
     * @param delegate 数值型生成器（不得为 {@code null}）
     */
    public StringIdGenerator(IdGenerator<Long> delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public String id() {
        if (closed) {
            throw new IdException(IdErrorCode.PROVIDER_CLOSED, "IdGenerator is closed");
        }
        return Long.toString(delegate.id());
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        delegate.close();
    }
}
