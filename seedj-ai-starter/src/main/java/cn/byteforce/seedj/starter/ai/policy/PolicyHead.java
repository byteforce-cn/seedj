package cn.byteforce.seedj.starter.ai.policy;

import java.util.Objects;

/**
 * 策略包的当前 head：守卫用它判断 token 里的策略引用是否仍然新鲜。
 *
 * <p>{@code version} 是必填的；{@code epoch} 是可选的纪元/快照序号。引擎提供 {@code epoch} 时守卫要求 token 也带 {@code epoch}
 * 且相等——缺了就「无法确认新鲜度」，只能拒绝。
 *
 * @param engine 策略引擎名
 * @param bundle 策略包名
 * @param version 当前版本
 * @param epoch 当前纪元；{@code null} 表示引擎不提供
 * @author ByteForce
 * @since 1.0.0
 */
public record PolicyHead(String engine, String bundle, long version, Long epoch) {

    /**
     * 校验引擎/包名与版本。
     *
     * @throws NullPointerException {@code engine} 或 {@code bundle} 为 {@code null}
     * @throws IllegalArgumentException 名称为空白，或 {@code version} 为负
     */
    public PolicyHead {
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(bundle, "bundle");
        if (engine.isBlank() || bundle.isBlank()) {
            throw new IllegalArgumentException("engine and bundle must not be blank");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }
}
