package cn.byteforce.seedj.infra.jdbc.domain;

import cn.byteforce.seedj.core.Migration;
import cn.byteforce.seedj.infra.jdbc.MigrationSet;
import java.util.List;

/**
 * 双时间轴能力的共享数据库对象：扩展与域类型。
 *
 * <p>对象表与时点函数由各领域模块通过 {@link TemporalDdl} 创建，不在这里建表。 域类型无条件创建：迁移按 scope 只执行一次，重复创建视为迁移缺陷而不是静默跳过。
 *
 * @author ByteForce
 * @since 1.0.0
 */
final class TemporalMigrations {

    private TemporalMigrations() {}

    /**
     * 迁移声明。
     *
     * @return 能力名、scope、顺序与迁移列表
     */
    static MigrationSet migrationSet() {
        return new MigrationSet("postgres-temporal", "temporal", 120, all());
    }

    static List<Migration> all() {
        return List.of(
                new Migration(
                        1,
                        "temporal domains",
                        """
                CREATE EXTENSION IF NOT EXISTS btree_gist;

                CREATE DOMAIN eis_id AS UUID;

                CREATE DOMAIN eis_version AS BIGINT CHECK (VALUE >= 1);

                CREATE DOMAIN eis_timestamp AS TIMESTAMPTZ(6);
                """));
    }
}
