package cn.byteforce.seedj.core.clock.spi.pg;

import cn.byteforce.seedj.core.clock.HybridTimestamp;
import cn.byteforce.seedj.core.clock.spi.HybridStateStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * PG 参考实现：HLC 状态持久化。
 */
public final class PgHybridStateStore implements HybridStateStore {

    private static final String LOAD_SQL =
            "SELECT physical_micros, logical FROM sys_hybrid_state WHERE node_id = ?";

    private static final String SAVE_SQL =
            "INSERT INTO sys_hybrid_state(node_id, physical_micros, logical, updated_at) "
                    + "VALUES (?, ?, ?, now()) "
                    + "ON CONFLICT (node_id) DO UPDATE SET "
                    + "physical_micros = EXCLUDED.physical_micros, "
                    + "logical = EXCLUDED.logical, "
                    + "updated_at = now() "
                    + "WHERE (EXCLUDED.physical_micros, EXCLUDED.logical) "
                    + "    > (sys_hybrid_state.physical_micros, sys_hybrid_state.logical)";

    private final DataSource dataSource;

    public PgHybridStateStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public HybridTimestamp load(String nodeId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(LOAD_SQL)) {
            ps.setString(1, nodeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new HybridTimestamp(rs.getLong(1), rs.getLong(2));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("load hybrid state failed: " + nodeId, e);
        }
    }

    @Override
    public void save(String nodeId, HybridTimestamp state) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SAVE_SQL)) {
            ps.setString(1, nodeId);
            ps.setLong(2, state.physicalMicros());
            ps.setLong(3, state.logical());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("save hybrid state failed: " + nodeId, e);
        }
    }
}
