package cn.byteforce.seedj.starter.eis.domain;

import java.util.Objects;

/**
 * 以标识判等的实体基类。
 *
 * <p>两个实体相等当且仅当标识相等，与可变字段无关；这是事件溯源的前提——
 * 聚合在内存中的状态随事件变化，但它的身份不变。</p>
 *
 * <p>标识为 {@code null} 时构造期直接失败：没有身份的实体无法定位事件流，
 * 允许构造只会把错误推到保存阶段。</p>
 *
 * @param <ID> 标识类型
 * @author ByteForce
 * @since 1.0.0
 */
public abstract class AbstractEntity<ID> implements Identified<ID> {

    private final ID id;

    /**
     * 构造实体。
     *
     * @param id 标识，不允许为 {@code null}
     * @throws NullPointerException 标识为 {@code null}
     */
    protected AbstractEntity(ID id) {
        this.id = Objects.requireNonNull(id, "entity id must not be null");
    }

    /**
     * 返回标识。
     *
     * @return 标识
     */
    @Override
    public final ID getId() {
        return id;
    }

    /**
     * 按标识判等。
     *
     * @param other 待比较对象
     * @return 标识相等时为 {@code true}
     */
    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AbstractEntity<?> entity)) {
            return false;
        }
        return Objects.equals(id, entity.id);
    }

    /**
     * 返回标识的哈希码。
     *
     * @return 哈希码
     */
    @Override
    public final int hashCode() {
        return Objects.hashCode(id);
    }
}
