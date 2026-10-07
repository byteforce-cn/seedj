package cn.byteforce.seedj.data.core.page;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PageResult} 契约测试：总页数计算、空值防御与不可变记录集。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class PageResultTest {

    @Test
    void computesPagesByCeiling() {
        PageResult<String> result = PageResult.of(List.of("a", "b"), 21, 2, 10);

        assertThat(result.getTotal()).isEqualTo(21);
        assertThat(result.getPages()).isEqualTo(3);
        assertThat(result.getPageNum()).isEqualTo(2);
        assertThat(result.getPageSize()).isEqualTo(10);
        assertThat(result.getRecords()).containsExactly("a", "b");
    }

    @Test
    void exactDivisionProducesExactPages() {
        assertThat(PageResult.of(List.of(), 20, 1, 10).getPages()).isEqualTo(2);
    }

    @Test
    void defensiveAgainstNullsAndNegatives() {
        PageResult<String> result = PageResult.of(null, -5, 1, 0);

        assertThat(result.getRecords()).isEmpty();
        assertThat(result.getTotal()).isZero();
        assertThat(result.getPages()).isZero();
    }

    @Test
    void emptyFactoryBuildsZeroResult() {
        PageResult<String> result = PageResult.empty(1, 10);

        assertThat(result.getRecords()).isEmpty();
        assertThat(result.getTotal()).isZero();
        assertThat(result.getPages()).isZero();
    }

    @Test
    void recordsListIsImmutable() {
        PageResult<String> result = PageResult.of(List.of("a"), 1, 1, 10);

        assertThat(result.getRecords()).isUnmodifiable();
    }
}
