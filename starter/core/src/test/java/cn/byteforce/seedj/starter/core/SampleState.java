package cn.byteforce.seedj.starter.core;

import cn.byteforce.seedj.core.CodeEnum;

/**
 * 测试用状态枚举：ordinal（0/1）与 code（100/200）刻意错开，
 * 用于捕获「按 ordinal 静默映射」的回归。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public enum SampleState implements CodeEnum<Integer> {

    /** 已创建。 */
    CREATED(100, "已创建"),

    /** 已撤销。 */
    REVOKED(200, "已撤销");

    private final Integer code;

    private final String label;

    SampleState(Integer code, String label) {
        this.code = code;
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
