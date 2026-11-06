package cn.byteforce.seedj.infra.jdbc.lock;

import cn.byteforce.seedj.core.Migration;

import java.util.List;

/**
 * 数据库锁表迁移：租约表 + 过期扫描索引。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class PostgresLockMigrations {

    private PostgresLockMigrations() {}

    /**
     * 全部迁移。
     *
     * @return 迁移列表
     */
    public static List<Migration> all() {
        return List.of(new Migration(1, "create lock lease table", """
                CREATE TABLE seedj_lock_lease (
                    lock_key   VARCHAR(255) PRIMARY KEY,
                    owner      UUID         NOT NULL,
                    expires_at TIMESTAMPTZ  NOT NULL
                );
                CREATE INDEX idx_seedj_lock_lease_expires ON seedj_lock_lease(expires_at);
                """));
    }
}
