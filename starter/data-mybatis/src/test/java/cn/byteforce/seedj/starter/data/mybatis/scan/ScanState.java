package cn.byteforce.seedj.starter.data.mybatis.scan;

import cn.byteforce.seedj.core.CodeEnum;

/**
 * 扫描用测试枚举（位于独立子包，验证 TypeHandler 自动注册）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public enum ScanState implements CodeEnum<Integer> {

    /** 开启。 */
    ON(1, "开启"),

    /** 关闭。 */
    OFF(2, "关闭");

    private final Integer code;

    private final String label;

    ScanState(Integer code, String label) {
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
