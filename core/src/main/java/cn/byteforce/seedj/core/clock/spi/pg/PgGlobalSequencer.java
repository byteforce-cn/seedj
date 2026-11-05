package cn.byteforce.seedj.core.clock.spi.pg;

import cn.byteforce.seedj.core.clock.spi.GlobalSequencer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * PG 参考实现：全局序列锚点。
 */
public final class PgGlobalSequencer implements GlobalSequencer {

    private static final String NEXT_SQL = "SELECT nextval('sys_global_seq')";

    private final DataSource dataSource;

    public PgGlobalSequencer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public long next() {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(NEXT_SQL);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("nextval sys_global_seq failed", e);
        }
    }
}
