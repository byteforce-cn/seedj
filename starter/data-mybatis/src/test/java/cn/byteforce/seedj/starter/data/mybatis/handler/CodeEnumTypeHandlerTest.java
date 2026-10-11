package cn.byteforce.seedj.starter.data.mybatis.handler;

import cn.byteforce.seedj.starter.data.mybatis.scan.ScanState;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CodeEnumTypeHandler} 读写契约测试：code 列映射、空值与非法值语义。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class CodeEnumTypeHandlerTest {

    private final CodeEnumTypeHandler<ScanState> handler = new CodeEnumTypeHandler<>(ScanState.class);

    @Test
    void writesCodeToPreparedStatement() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);

        handler.setNonNullParameter(ps, 1, ScanState.ON, null);

        verify(ps).setObject(1, 1);
    }

    @Test
    void writesCodeWithJdbcType() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);

        handler.setNonNullParameter(ps, 2, ScanState.OFF, JdbcType.INTEGER);

        verify(ps).setObject(2, 2, JdbcType.INTEGER.TYPE_CODE);
    }

    @Test
    void readsEnumByCodeFromColumnName() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("state")).thenReturn(2);

        assertThat(handler.getNullableResult(rs, "state")).isEqualTo(ScanState.OFF);
    }

    @Test
    void readsEnumByCodeFromColumnIndex() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject(1)).thenReturn(1);

        assertThat(handler.getNullableResult(rs, 1)).isEqualTo(ScanState.ON);
    }

    @Test
    void numericTypeDriftMatchesByValue() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        // JDBC 驱动把 INT 列返回为 Long：与 Integer code 数值等价，应按 code 命中
        when(rs.getObject(1)).thenReturn(1L);

        assertThat(handler.getNullableResult(rs, 1)).isEqualTo(ScanState.ON);
    }

    @Test
    void readsEnumFromCallableStatement() throws Exception {
        CallableStatement cs = mock(CallableStatement.class);
        when(cs.getObject(1)).thenReturn(2);

        assertThat(handler.getNullableResult(cs, 1)).isEqualTo(ScanState.OFF);
    }

    @Test
    void nullValueStaysNull() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject(1)).thenReturn(null);

        assertThat(handler.getNullableResult(rs, 1)).isNull();
    }

    @Test
    void unknownCodeFailsFast() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject(1)).thenReturn(99);

        assertThatThrownBy(() -> handler.getNullableResult(rs, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ScanState");
    }
}
