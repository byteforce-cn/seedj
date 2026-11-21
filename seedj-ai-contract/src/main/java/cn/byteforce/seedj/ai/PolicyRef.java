package cn.byteforce.seedj.ai;

import java.util.List;
import java.util.Objects;

/**
 * 策略引用：这一步被哪个策略包、哪一版规则判定过。
 *
 * <p>策略判定结果与命令执行分开记录：策略可以异步、可以拒绝、也可以在 T0 完全不参与。T0 用 {@link #none()}
 * 显式声明“未评估”，让后续接入策略时能区分“没评估”和“评估通过”。{@code bundleVersion} 与 {@code epoch}
 * 允许缺省——策略引擎可能只给其中一项，缺失时守卫按“无法确认新鲜度”处理。
 *
 * @param engine 策略引擎名
 * @param bundle 策略包名；{@code null} 表示未绑定策略包
 * @param bundleVersion 策略包版本；{@code null} 表示引擎未提供
 * @param epoch 策略纪元/快照序号；{@code null} 表示引擎未提供
 * @param verdict 判定结论（如 {@code allow} / {@code deny} / {@code not_evaluated}）
 * @param ruleIds 命中的规则标识；{@code null} 视为空列表
 * @param explainRef 判定解释的引用；{@code null} 表示无解释
 * @author ByteForce
 * @since 1.0.0
 */
public record PolicyRef(
        String engine,
        String bundle,
        Long bundleVersion,
        Long epoch,
        String verdict,
        List<String> ruleIds,
        String explainRef) {

    /**
     * 校验引擎名并归一化规则列表。
     *
     * @throws NullPointerException {@code engine} 为 {@code null}
     * @throws IllegalArgumentException {@code engine} 为空白
     */
    public PolicyRef {
        Objects.requireNonNull(engine, "engine");
        if (engine.isBlank()) {
            throw new IllegalArgumentException("engine must not be blank");
        }
        ruleIds = ruleIds == null ? List.of() : List.copyOf(ruleIds);
    }

    /**
     * T0 的“未评估”策略引用：显式声明策略层未参与这一步。
     *
     * @return {@code engine=none}、{@code verdict=not_evaluated} 的策略引用
     */
    public static PolicyRef none() {
        return new PolicyRef("none", null, null, null, "not_evaluated", List.of(), null);
    }
}
