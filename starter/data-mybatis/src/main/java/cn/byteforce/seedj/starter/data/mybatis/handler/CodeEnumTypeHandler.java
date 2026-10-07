
package cn.byteforce.seedj.starter.data.mybatis.handler;

import cn.byteforce.seedj.core.CodeEnum;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;


/**
 * CodeEnum ↔ 数据库列的通用 TypeHandler：写入枚举 {@code code}、按 {@code code} 反查枚举；
 * 未知值 fail-fast（{@link IllegalArgumentException}），不做 ordinal 兜底。
 *
 * @param <E> CodeEnum 枚举类型
 * @author ByteForce
 * @since 1.0.0
 */
public class CodeEnumTypeHandler<E extends Enum<E> & CodeEnum<?>> extends BaseTypeHandler<E> {

    private final Class<E> type;

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
        for (E e : type.getEnumConstants()) {
            if (value.equals(e.getCode())) {
                return e;
            }
        }
        throw new IllegalArgumentException(
                "无法将 [" + value + "] 转换为枚举 " + type.getSimpleName());
    }
}