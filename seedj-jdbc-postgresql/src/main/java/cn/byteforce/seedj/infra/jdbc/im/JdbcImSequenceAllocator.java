package cn.byteforce.seedj.infra.jdbc.im;

import cn.byteforce.seedj.im.spi.ImSequenceAllocator;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PostgreSQL 全局序号：直接读 {@code im_seq}。
 *
 * <p>序列不是事务性的：回滚会留下空洞，这正是契约允许的语义。{@code CACHE 50} 让每个节点预取一段， 因此跨节点看到的顺序仍然单调，但不保证连续。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class JdbcImSequenceAllocator implements ImSequenceAllocator {

    private final JdbcTemplate jdbc;

    /**
     * 构造分配器。
     *
     * @param jdbc JDBC 模板
     */
    public JdbcImSequenceAllocator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long next() {
        Long value = jdbc.queryForObject("SELECT nextval('im_seq')", Long.class);
        if (value == null) {
            throw new IllegalStateException("im_seq returned no value");
        }
        return value;
    }
}
