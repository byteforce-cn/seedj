package cn.byteforce.seedj.data.core.page;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PageQuery} 默认值与访问器契约测试。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class PageQueryTest {

    @Test
    void defaultsAreStable() {
        PageQuery query = new PageQuery();

        assertThat(query.getPageNum()).isEqualTo(1);
        assertThat(query.getPageSize()).isEqualTo(10);
    }

    @Test
    void settersOverrideDefaults() {
        PageQuery query = new PageQuery();
        query.setPageNum(3);
        query.setPageSize(50);

        assertThat(query.getPageNum()).isEqualTo(3);
        assertThat(query.getPageSize()).isEqualTo(50);
    }
}
