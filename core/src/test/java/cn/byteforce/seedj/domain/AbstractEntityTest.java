package cn.byteforce.seedj.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 实体判等契约：<b>类型 + 标识</b>，缺一不可。
 *
 * <p>类型参与判等是 CQRS 的硬要求：{@code Order(1)} 与 {@code Invoice(1)} 落在 不同的流、不同的仓储、不同的投影行；如果只比较标识，二者会被
 * {@code Set}/{@code Map} 静默合并，读侧出现"串流"而没有任何编译期或运行期提示。
 */
class AbstractEntityTest {

    private static final class Order extends AbstractEntity<Long> {
        private Order(Long id) {
            super(id);
        }
    }

    private static final class Invoice extends AbstractEntity<Long> {
        private Invoice(Long id) {
            super(id);
        }
    }

    @Test
    @DisplayName("同类同标识相等，哈希一致")
    void sameTypeSameIdIsEqual() {
        Order a = new Order(1L);
        Order b = new Order(1L);

        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
    }

    @Test
    @DisplayName("同标识不同类型不相等")
    void sameIdDifferentTypeIsNotEqual() {
        Order order = new Order(1L);
        Invoice invoice = new Invoice(1L);

        assertThat(order).isNotEqualTo(invoice);
        assertThat(invoice).isNotEqualTo(order);
    }

    @Test
    @DisplayName("同类不同标识不相等")
    void sameTypeDifferentIdIsNotEqual() {
        assertThat(new Order(1L)).isNotEqualTo(new Order(2L));
    }

    @Test
    @DisplayName("与 null 和其它类型比较返回 false，不抛异常")
    void comparesSafelyWithOtherObjects() {
        Order order = new Order(1L);

        assertThat(order).isNotEqualTo(null);
        assertThat(order).isNotEqualTo("1");
    }

    @Test
    @DisplayName("标识为 null 时构造期失败")
    void rejectsNullId() {
        assertThatThrownBy(() -> new Order(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("entity id must not be null");
    }

    @Test
    @DisplayName("同一实例恒等")
    void reflexiveEquality() {
        Order order = new Order(7L);

        assertThat(order).isSameAs(order);
        assertThat(order.equals(order)).isTrue();
        assertThat(order.hashCode()).isEqualTo(new Order(7L).hashCode());
    }
}
