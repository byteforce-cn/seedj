package cn.byteforce.seedj.codegen;

import cn.byteforce.seedj.domain.Privacy;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * record 组件的字段约束；未标注的组件按「可选、可读、可进事件」处理。
 *
 * <p>类型映射（由组件 Java 类型推导，不提供覆盖开关）：
 *
 * <ul>
 *   <li>{@code String}/{@code char}/{@code Character}/枚举 → {@code TEXT}
 *   <li>{@code int}/{@code Integer} → {@code INTEGER}；{@code long}/{@code Long} → {@code LONG}
 *   <li>{@code BigDecimal}/{@code double}/{@code float} 及其包装类型 → {@code DECIMAL}
 *   <li>{@code boolean}/{@code Boolean} → {@code BOOLEAN}
 *   <li>{@code LocalDate} → {@code DATE}；{@code Instant}/{@code OffsetDateTime} → {@code TIMESTAMP}
 *   <li>{@code UUID} → {@code UUID}
 * </ul>
 *
 * <p>字段名默认把 camelCase 组件名转成 snake_case；需要例外时用 {@link #name()}。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.METHOD})
public @interface SeedjField {

    /**
     * 显式字段名；空串表示由组件名推导。
     *
     * @return 字段名
     */
    String name() default "";

    /**
     * 是否必填。
     *
     * @return 必填为 {@code true}
     */
    boolean required() default false;

    /**
     * 是否写入专用（读投影与事件都不输出）。
     *
     * @return 写入专用为 {@code true}
     */
    boolean writeOnly() default false;

    /**
     * 是否允许作为列表过滤字段。
     *
     * @return 可过滤为 {@code true}
     */
    boolean filterable() default false;

    /**
     * 是否允许作为排序字段。
     *
     * @return 可排序为 {@code true}
     */
    boolean sortable() default false;

    /**
     * 是否生成当前状态部分索引。
     *
     * @return 需要索引为 {@code true}
     */
    boolean indexed() default false;

    /**
     * 隐私层级；敏感及以上默认不进入事件载荷。
     *
     * @return 隐私层级
     */
    Privacy privacy() default Privacy.PUBLIC;

    /**
     * 默认打码规则。
     *
     * @return 打码规则
     */
    MaskerName masker() default MaskerName.NONE;

    /**
     * 允许的取值（仅 TEXT）；空数组表示不限制。
     *
     * @return 枚举取值
     */
    String[] enumValues() default {};

    /**
     * 值必须匹配的正则（仅 TEXT）；空串表示不限制。
     *
     * <p>只支持 JSON Schema 与 POSIX 都兼容的子集：字符类、量词、锚点。
     *
     * @return 正则
     */
    String pattern() default "";

    /**
     * 最小字符数（仅 TEXT）；0 表示不限制。
     *
     * @return 最小长度
     */
    int minLength() default 0;

    /**
     * 最大字符数（仅 TEXT）；0 表示不限制。
     *
     * @return 最大长度
     */
    int maxLength() default 0;

    /**
     * 是否要求当前状态唯一（业务键，生成部分唯一索引）。
     *
     * @return 唯一为 {@code true}
     */
    boolean unique() default false;

    /**
     * 十进制总位数（仅 DECIMAL）；0 表示未声明（编译期拒绝）。
     *
     * @return 精度
     */
    int precision() default 0;

    /**
     * 小数位数（仅 DECIMAL）；precision 已声明时 0 表示整数。
     *
     * @return 标度
     */
    int scale() default 0;
}
