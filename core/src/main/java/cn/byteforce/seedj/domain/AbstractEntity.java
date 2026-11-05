package cn.byteforce.seedj.domain;

import lombok.Getter;

import java.util.Objects;

/**
 * 以标识判等的实体基类。
 *
 * <p>两个实体相等当且仅当<b>类型相同且标识相等</b>，与可变字段无关。类型参与判等
 * 是刻意的：{@code Order(1)} 与 {@code Invoice(1)} 是两条不同的流、两个不同的仓储，
 * 不能因为标识相同就被 {@code Set}/{@code Map} 合并。</p>
 *
 * <p>标识为 {@code null} 时构造期直接失败：没有身份的实体无法定位事件流，
 * 允许构造只会把错误推到保存阶段。</p>
 *
 * @param <ID> 标识类型
 * @author ByteForce
 * @since 1.0.0
 */
public abstract class AbstractEntity<ID> {

    @Getter
    private final ID id;

    protected AbstractEntity(ID id) {
        this.id = Objects.requireNonNull(id, "entity id must not be null");
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        return Objects.equals(id, ((AbstractEntity<?>) o).id);
    }

    @Override
    public final int hashCode() {
        return Objects.hashCode(id);
    }
}