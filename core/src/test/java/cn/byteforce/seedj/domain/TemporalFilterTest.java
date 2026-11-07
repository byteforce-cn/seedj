package cn.byteforce.seedj.domain;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 过滤器 AST 的构造期校验与不可变性。 */
class TemporalFilterTest {

    @Test
    @DisplayName("所有比较节点保留字段与值")
    void comparisonNodesCarryValues() {
        assertThat(new TemporalFilter.Eq("name", "a").value()).isEqualTo("a");
        assertThat(new TemporalFilter.Ne("name", "a").value()).isEqualTo("a");
        assertThat(new TemporalFilter.Gt("n", 1).value()).isEqualTo(1);
        assertThat(new TemporalFilter.Gte("n", 1).value()).isEqualTo(1);
        assertThat(new TemporalFilter.Lt("n", 1).value()).isEqualTo(1);
        assertThat(new TemporalFilter.Lte("n", 1).value()).isEqualTo(1);
        assertThat(new TemporalFilter.Between("n", 1, 2).upper()).isEqualTo(2);
        assertThat(new TemporalFilter.Like("name", "a%").pattern()).isEqualTo("a%");
    }

    @Test
    @DisplayName("非法字段名在构造期失败")
    void illegalFieldIsRejected() {
        assertThatThrownBy(() -> new TemporalFilter.Eq("Bad", "x"))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.INVALID_PAYLOAD));
        assertThatThrownBy(() -> new TemporalFilter.Ne("1bad", "x"))
                .isInstanceOf(ContractException.class);
        assertThatThrownBy(() -> new TemporalFilter.In("bad-name", List.of("x")))
                .isInstanceOf(ContractException.class);
        assertThatThrownBy(() -> new TemporalFilter.Gt("bad field", 1))
                .isInstanceOf(ContractException.class);
        assertThatThrownBy(() -> new TemporalFilter.Like("Boundary", "x"))
                .isInstanceOf(ContractException.class);
    }

    @Test
    @DisplayName("In 丢弃 null 且上限 1000")
    void inNormalizesValues() {
        TemporalFilter.In in = new TemporalFilter.In("name", List.of("a", "b"));
        assertThat(in.values().toArray()).containsExactly("a", "b");

        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i <= ScopePredicates.MAX_IN_VALUES; i++) {
            tooMany.add("v" + i);
        }
        assertThatThrownBy(() -> new TemporalFilter.In("name", tooMany))
                .isInstanceOf(ContractException.class);
    }

    @Test
    @DisplayName("Like 模式不能为空")
    void likePatternIsRequired() {
        assertThatThrownBy(() -> new TemporalFilter.Like("name", " "))
                .isInstanceOf(ContractException.class);
    }

    @Test
    @DisplayName("比较值不能为 null")
    void comparisonValueIsRequired() {
        assertThatThrownBy(() -> new TemporalFilter.Gt("n", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TemporalFilter.Between("n", null, 2))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("And/Or/Not 拒绝 null 子节点")
    void compositeRejectsNull() {
        assertThatThrownBy(() -> new TemporalFilter.And(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TemporalFilter.And(java.util.Arrays.asList(
                (TemporalFilter) null)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TemporalFilter.Or(java.util.Arrays.asList(
                (TemporalFilter) null)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TemporalFilter.Not(null))
                .isInstanceOf(NullPointerException.class);
    }
}
