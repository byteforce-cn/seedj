package cn.byteforce.seedj.codegen;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 把一个 record 声明为业务对象的字段来源。
 *
 * <p>{@code seedj-codegen} 在编译期据此生成 {@code <Record>Schema}（{@code ObjectSchema}）、
 * OpenAPI 片段与迁移候选。record 的组件名按 {@code snake_case} 映射为字段名，类型按
 * {@link SeedjField} 文档中的规则映射为 {@link cn.byteforce.seedj.domain.FieldType}。</p>
 *
 * <p>约束：只能标注 record；字段名不得与双时间轴元字段冲突；同一对象在编译单元内只能声明一次。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface SeedjObject {

    /**
     * 对象类型（小写标识符），如 {@code employee}。
     *
     * @return 对象类型
     */
    String objectType();

    /**
     * 物理表名（小写标识符），如 {@code hr_employee}。
     *
     * @return 表名
     */
    String table();
}
