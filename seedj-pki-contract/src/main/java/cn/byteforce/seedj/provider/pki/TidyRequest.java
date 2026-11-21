package cn.byteforce.seedj.provider.pki;

import java.time.Duration;

/**
 * tidy 请求。
 *
 * <p>{@code retention == null} 表示 provider/CA 的默认保留期；{@code Duration.ZERO} 表示立即回收已过期
 * 记录。coord 侧的吊销账本永不被 tidy 回收。
 */
public record TidyRequest(Duration retention) {

    public TidyRequest {
        if (retention != null && retention.isNegative()) {
            throw new IllegalArgumentException("retention 不能为负数");
        }
    }

    public static TidyRequest defaults() {
        return new TidyRequest(null);
    }

    public static TidyRequest retainFor(Duration retention) {
        return new TidyRequest(retention);
    }

    /** 换算为 provider 需要的秒数；{@code null} 保留期交给 provider 默认值。 */
    public long retentionSeconds(long defaultSeconds) {
        return retention == null ? defaultSeconds : retention.toSeconds();
    }
}
