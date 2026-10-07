package cn.byteforce.seedj.starter.data.mybatis;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MybatisBaselineProperties} 默认值与访问器契约测试。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class MybatisBaselinePropertiesTest {

    @Test
    void defaultsAreStable() {
        MybatisBaselineProperties properties = new MybatisBaselineProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getAudit().isEnabled()).isTrue();
        assertThat(properties.getAudit().isUseRequestContext()).isTrue();
        assertThat(properties.getPagination().isEnabled()).isTrue();
        assertThat(properties.getPagination().getMaxLimit()).isEqualTo(500L);
        assertThat(properties.getTenant().isEnabled()).isFalse();
        assertThat(properties.getTenant().getColumn()).isEqualTo("tenant_id");
        assertThat(properties.getTenant().getIgnoreTables()).isEmpty();
    }

    @Test
    void settersOverrideDefaults() {
        MybatisBaselineProperties properties = new MybatisBaselineProperties();
        properties.setEnabled(false);
        properties.getAudit().setEnabled(false);
        properties.getAudit().setUseRequestContext(false);
        properties.getPagination().setEnabled(false);
        properties.getPagination().setMaxLimit(100L);
        properties.getTenant().setEnabled(true);
        properties.getTenant().setColumn("tid");
        properties.getTenant().setIgnoreTables(Set.of("sys_dict"));

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getAudit().isEnabled()).isFalse();
        assertThat(properties.getAudit().isUseRequestContext()).isFalse();
        assertThat(properties.getPagination().isEnabled()).isFalse();
        assertThat(properties.getPagination().getMaxLimit()).isEqualTo(100L);
        assertThat(properties.getTenant().isEnabled()).isTrue();
        assertThat(properties.getTenant().getColumn()).isEqualTo("tid");
        assertThat(properties.getTenant().getIgnoreTables()).containsExactly("sys_dict");
    }
}
