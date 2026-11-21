package cn.byteforce.seedj.ai;

import java.util.Objects;

/**
 * 决策所用模型的标识：哪个模型、哪版提示词、哪组参数。
 *
 * <p>只存标识与摘要，不存模型对象；{@code promptVer} 与 {@code paramsDigest} 让“同一模型为何给出不同判断”可复核。
 *
 * @param name 模型名
 * @param version 模型版本
 * @param promptVer 提示词版本；{@code null} 表示未登记
 * @param paramsDigest 生成参数（温度、top_p 等）的规范化摘要；{@code null} 表示未登记
 * @author ByteForce
 * @since 1.0.0
 */
public record ModelRef(String name, String version, String promptVer, String paramsDigest) {

    /**
     * 校验模型名与版本。
     *
     * @throws NullPointerException {@code name} 或 {@code version} 为 {@code null}
     * @throws IllegalArgumentException {@code name} 或 {@code version} 为空白
     */
    public ModelRef {
        name = requiredText(name, "name");
        version = requiredText(version, "version");
    }

    private static String requiredText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
