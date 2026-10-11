package cn.byteforce.seedj.starter.eis.core;

/**
 * 对象字段描述（名称 + 类型）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class EisObjectField {

    private final String name;

    private final EisFieldKind kind;

    /**
     * 构造字段描述。
     *
     * @param name 列名（{@code ^[a-z_][a-z0-9_]*$}）
     * @param kind 字段类型
     */
    public EisObjectField(String name, EisFieldKind kind) {
        this.name = name;
        this.kind = kind;
    }

    /**
     * 获取列名。
     *
     * @return 列名
     */
    public String getName() {
        return name;
    }

    /**
     * 获取类型。
     *
     * @return 字段类型
     */
    public EisFieldKind getKind() {
        return kind;
    }
}
