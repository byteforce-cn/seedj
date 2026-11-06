package cn.byteforce.seedj.infra.jdbc.clock;

import cn.byteforce.seedj.core.Migration;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * PG 时钟适配器迁移：HLC 节点状态表与全局序列锚点。
 *
 * <p>{@code sys_} 前缀沿用既有产品表约定；DDL 单独放在资源中，便于同样的脚本
 * 在部署侧直接审阅。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class ClockMigrations {

    /** 迁移 scope。 */
    public static final String SCOPE = "clock";

    private static final String SCHEMA_RESOURCE =
            "/cn/byteforce/seedj/infra/jdbc/clock/schema.sql";

    private ClockMigrations() {}

    /**
     * 全部迁移。
     *
     * @return 迁移列表
     */
    public static List<Migration> all() {
        return List.of(new Migration(1, "clock state", schemaSql()));
    }

    private static String schemaSql() {
        try (InputStream in = ClockMigrations.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource: " + SCHEMA_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException("cannot read " + SCHEMA_RESOURCE, error);
        }
    }
}
