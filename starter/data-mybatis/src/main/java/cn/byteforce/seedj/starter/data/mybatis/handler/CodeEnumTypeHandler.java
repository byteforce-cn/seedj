
package cn.byteforce.seedj.starter.data.mybatis.handler;

import cn.byteforce.seedj.core.CodeEnum;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;


/**
 * CodeEnum ↔ 数据库列的通用 TypeHandler：写入枚举 {@code code}、按 {@code code} 反查枚举；
 * 未知值 fail-fast（{@link IllegalArgumentException}），不做 ordinal 兜底。
 *
 * <p>读取侧先做精确匹配（{@code code.equals(value)}），再对数值型 code 做一次
 * 数值等价容错（如 JDBC 驱动把 INT 列返回为 {@code Long}）——数值等价视为命中，
 * 不改变「未知 code 必须报错」的语义。</p>
 *
 * @param <E> CodeEnum 枚举类型
 * @author ByteForce
 * @since 1.0.0
 */
public class CodeEnumTypeHandler<E extends Enum<E> & CodeEnum<?>> extends BaseTypeHandler<E> {

    private final Class<E> type;

    /** code → 枚举的精确索引（构造期建一次，避免逐行线性扫描）。 */
    private final Map<Object, E> byCode;

    /**
     * 构造 TypeHandler。
     *
     * @param type 枚举类型
     */
    public CodeEnumTypeHandler(Class<E> type) {
        if (type == null) {
            throw new IllegalArgumentException("Type argument cannot be null");
        }
        this.type = type;
        Map<Object, E> index = new HashMap<>();
        for (E e : type.getEnumConstants()) {
            index.put(e.getCode(), e);
        }
        this.byCode = Collections.unmodifiableMap(index);
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, E parameter,
                                    JdbcType jdbcType) throws SQLException {
        Object code = parameter.getCode();
        if (jdbcType == null) {
            ps.setObject(i, code);
        } else {
            ps.setObject(i, code, jdbcType.TYPE_CODE);
        }
    }

    @Override
    public E getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return convert(rs.getObject(columnName));
    }

    @Override
    public E getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return convert(rs.getObject(columnIndex));
    }

    @Override
    public E getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return convert(cs.getObject(columnIndex));
    }

    private E convert(Object value) {
        if (value == null) {
            return null;
        }
        E exact = byCode.get(value);
        if (exact != null) {
            return exact;
        }
        // 数值类型漂移容错：JDBC 返回类型（Long/BigDecimal 等）与 code 类型（Integer）不一致时按数值等价命中
        if (value instanceof Number number) {
            for (E e : type.getEnumConstants()) {
                if (e.getCode() instanceof Number code
                        && code.longValue() == number.longValue()) {
                    return e;
                }
            }
        }
        throw new IllegalArgumentException(
                "无法将 [" + value + "] 转换为枚举 " + type.getSimpleName());
    }
}