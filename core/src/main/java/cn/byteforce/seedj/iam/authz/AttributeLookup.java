package cn.byteforce.seedj.iam.authz;

import java.util.Objects;

/**
 * 属性拉取结果：状态 + 可选快照。
 *
 * @param state 状态
 * @param snapshot 快照；missing / source_unavailable 时为 {@code null}
 * @author ByteForce
 * @since 1.0.0
 */
public record AttributeLookup(AttributeState state, AttributeSnapshot snapshot) {

    public AttributeLookup {
        Objects.requireNonNull(state, "state");
    }

    public static AttributeLookup missing() {
        return new AttributeLookup(AttributeState.MISSING, null);
    }

    public static AttributeLookup unavailable() {
        return new AttributeLookup(AttributeState.SOURCE_UNAVAILABLE, null);
    }
}
