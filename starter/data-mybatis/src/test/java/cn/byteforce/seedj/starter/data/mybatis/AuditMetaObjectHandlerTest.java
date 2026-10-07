package cn.byteforce.seedj.starter.data.mybatis;

import cn.byteforce.seedj.core.context.RequestContext;
import cn.byteforce.seedj.data.core.entity.BaseEntity;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AuditMetaObjectHandler} 审计填充契约测试（strict 模式：仅填充带注解字段）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class AuditMetaObjectHandlerTest {

    /**
     * 测试实体：继承审计字段。
     */
    static class OrderEntity extends BaseEntity {
    }

    @BeforeAll
    static void registerTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, OrderEntity.class);
    }

    @AfterAll
    static void unregisterTableInfo() {
        TableInfoHelper.remove(OrderEntity.class);
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void insertFillSetsTimesWithoutContext() {
        OrderEntity order = new OrderEntity();
        MetaObject metaObject = SystemMetaObject.forObject(order);

        new AuditMetaObjectHandler(true).insertFill(metaObject);

        assertThat(order.getCreateTime()).isNotNull();
        assertThat(order.getUpdateTime()).isNotNull();
        assertThat(order.getCreateBy()).isNull();
        assertThat(order.getUpdateBy()).isNull();
    }

    @Test
    void insertFillUsesUsernameFromRequestContext() {
        RequestContext.setUsername("alice");
        OrderEntity order = new OrderEntity();

        new AuditMetaObjectHandler(true).insertFill(SystemMetaObject.forObject(order));

        assertThat(order.getCreateBy()).isEqualTo("alice");
        assertThat(order.getUpdateBy()).isEqualTo("alice");
    }

    @Test
    void insertFillDoesNotOverrideExistingValues() {
        OrderEntity order = new OrderEntity();
        LocalDateTime preset = LocalDateTime.of(2020, 1, 1, 0, 0);
        order.setCreateTime(preset);

        new AuditMetaObjectHandler(true).insertFill(SystemMetaObject.forObject(order));

        assertThat(order.getCreateTime()).isEqualTo(preset);
    }

    @Test
    void updateFillOnlyTouchesUpdateFields() {
        RequestContext.setUsername("bob");
        OrderEntity order = new OrderEntity();

        new AuditMetaObjectHandler(true).updateFill(SystemMetaObject.forObject(order));

        assertThat(order.getUpdateTime()).isNotNull();
        assertThat(order.getUpdateBy()).isEqualTo("bob");
        assertThat(order.getCreateTime()).isNull();
    }

    @Test
    void requestContextCanBeDisabled() {
        RequestContext.setUsername("alice");
        OrderEntity order = new OrderEntity();

        new AuditMetaObjectHandler(false).insertFill(SystemMetaObject.forObject(order));

        assertThat(order.getCreateBy()).isNull();
        assertThat(order.getUpdateBy()).isNull();
    }
}
