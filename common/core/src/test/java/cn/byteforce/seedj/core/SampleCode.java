package cn.byteforce.seedj.core;

import java.util.Objects;

/**
 * 测试用错误码枚举（code 与 ordinal 刻意不一致，用于识别按 ordinal 误映射的问题）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public enum SampleCode implements CodeEnum<Integer> {

    /** 成功样例。 */
    OK(10, "成功"),

    /** 失败样例。 */
    ERROR(20, "失败");

    private final Integer code;

    private final String label;

    SampleCode(Integer code, String label) {
        this.code = Objects.requireNonNull(code);
        this.label = label;
    }

    @Override
    public Integer getCode() {
        return code;
    }

    @Override
    public String getLabel() {
        return label;
    }
}
