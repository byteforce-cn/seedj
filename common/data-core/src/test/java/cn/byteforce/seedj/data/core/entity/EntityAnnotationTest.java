package cn.byteforce.seedj.data.core.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实体基类注解契约测试：主键、填充策略与逻辑删除标记不得回归。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class EntityAnnotationTest {

    /**
     * 测试实体：继承审计字段。
     */
    static class AuditEntity extends BaseEntity {
    }

    /**
     * 测试实体：追加逻辑删除字段。
     */
    static class DeletableEntity extends LogicDeletedEntity {
    }

    /**
     * 测试实体：追加租户字段。
     */
    static class TenantScopedEntity extends TenantEntity {
    }

    @Test
    void idIsPrimaryKey() throws Exception {
        Field id = BaseEntity.class.getDeclaredField("id");

        assertThat(id.getAnnotation(TableId.class)).isNotNull();
    }

    @Test
    void auditFieldsHaveFillStrategies() throws Exception {
        assertThat(fillOf(BaseEntity.class, "createTime")).isEqualTo(FieldFill.INSERT);
        assertThat(fillOf(BaseEntity.class, "createBy")).isEqualTo(FieldFill.INSERT);
        assertThat(fillOf(BaseEntity.class, "updateTime")).isEqualTo(FieldFill.INSERT_UPDATE);
        assertThat(fillOf(BaseEntity.class, "updateBy")).isEqualTo(FieldFill.INSERT_UPDATE);
    }

    @Test
    void logicDeletedFieldIsAnnotated() throws Exception {
        Field deleted = LogicDeletedEntity.class.getDeclaredField("deleted");

        assertThat(deleted.getAnnotation(TableLogic.class)).isNotNull();
    }

    @Test
    void tenantFieldIsFilledOnInsert() throws Exception {
        assertThat(fillOf(TenantEntity.class, "tenantId")).isEqualTo(FieldFill.INSERT);
    }

    @Test
    void gettersAndSettersWork() {
        TenantScopedEntity entity = new TenantScopedEntity();
        entity.setId(1L);
        entity.setTenantId(7L);
        entity.setCreateTime(java.time.LocalDateTime.of(2026, 1, 1, 0, 0));
        entity.setUpdateTime(java.time.LocalDateTime.of(2026, 1, 2, 0, 0));
        entity.setCreateBy("alice");
        entity.setUpdateBy("bob");

        assertThat(entity.getId()).isEqualTo(1L);
        assertThat(entity.getTenantId()).isEqualTo(7L);
        assertThat(entity.getCreateTime()).isEqualTo(java.time.LocalDateTime.of(2026, 1, 1, 0, 0));
        assertThat(entity.getUpdateTime()).isEqualTo(java.time.LocalDateTime.of(2026, 1, 2, 0, 0));
        assertThat(entity.getCreateBy()).isEqualTo("alice");
        assertThat(entity.getUpdateBy()).isEqualTo("bob");

        DeletableEntity deleted = new DeletableEntity();
        deleted.setDeleted(1);

        assertThat(deleted.getDeleted()).isEqualTo(1);
    }

    private static FieldFill fillOf(Class<?> type, String fieldName) throws Exception {
        return type.getDeclaredField(fieldName).getAnnotation(TableField.class).fill();
    }
}
