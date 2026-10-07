package cn.byteforce.seedj.starter.data.mybatis;

import cn.byteforce.seedj.data.core.page.PageQuery;
import cn.byteforce.seedj.data.core.page.PageResult;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PageUtils} 转换契约测试：参数归一化与结果映射。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class PageUtilsTest {

    @Test
    void toPageNormalizesLowerBounds() {
        PageQuery query = new PageQuery();
        query.setPageNum(0);
        query.setPageSize(-5);

        Page<String> page = PageUtils.toPage(query);

        assertThat(page.getCurrent()).isEqualTo(1);
        assertThat(page.getSize()).isEqualTo(1);
    }

    @Test
    void toPageKeepsValidValues() {
        PageQuery query = new PageQuery();
        query.setPageNum(3);
        query.setPageSize(20);

        Page<String> page = PageUtils.toPage(query);

        assertThat(page.getCurrent()).isEqualTo(3);
        assertThat(page.getSize()).isEqualTo(20);
    }

    @Test
    void toResultMapsMybatisPage() {
        Page<String> page = new Page<>(2, 10);
        page.setRecords(List.of("a", "b"));
        page.setTotal(21);

        PageResult<String> result = PageUtils.toResult(page);

        assertThat(result.getRecords()).containsExactly("a", "b");
        assertThat(result.getTotal()).isEqualTo(21);
        assertThat(result.getPageNum()).isEqualTo(2);
        assertThat(result.getPageSize()).isEqualTo(10);
        assertThat(result.getPages()).isEqualTo(3);
    }
}
