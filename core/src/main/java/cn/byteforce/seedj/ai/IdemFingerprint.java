package cn.byteforce.seedj.ai;

import cn.byteforce.seedj.cqrs.IdempotencyFingerprint;
import java.util.Objects;

/**
 * 幂等指纹：同幂等键重复提交时的内容比对依据，与 {@code IdempotencyFingerprint} 同一口径。
 *
 * <p>只覆盖 {@code {type, payload, targets}}，不覆盖 {@code expectedVersion}——重放不因版本不同被当成新请求。
 * 返回 64 位小写十六进制摘要，无算法前缀。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class IdemFingerprint {

    private IdemFingerprint() {}

    /**
     * 计算幂等指纹（直接委托 {@link IdempotencyFingerprint}，避免第三份实现）。
     *
     * @param fingerprint 指纹输入
     * @return 64 位小写十六进制摘要
     * @throws NullPointerException {@code fingerprint} 为 {@code null}
     */
    public static String of(CommandFingerprint fingerprint) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        return IdempotencyFingerprint.of(
                fingerprint.type(), fingerprint.payload(), fingerprint.targets());
    }
}
