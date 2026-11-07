package cn.byteforce.seedj.infra.jdbc.cqrs;

import cn.byteforce.seedj.core.Migration;
import java.util.List;

/**
 * 事件流表的版本化迁移。
 *
 * <p>每条流是 {@code (aggregate_type, stream_key)}，版本从 1 开始连续递增； {@code position}
 * 是全局写入顺序，留给后续投影/订阅复用，不参与流版本。 事件 payload 是不透明字节，序列化格式由 {@code EventPayloadSerializer} 决定。
 *
 * @author ByteForce
 * @since 1.0.0
 */
final class EventMigrations {

    private EventMigrations() {}

    static List<Migration> all() {
        return List.of(v1());
    }

    static Migration v1() {
        return new Migration(
                1,
                "event stream table",
                """
                CREATE TABLE IF NOT EXISTS event_stream (
                    position       BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                    aggregate_type VARCHAR(128) NOT NULL,
                    stream_key     TEXT         NOT NULL,
                    version        BIGINT       NOT NULL,
                    event_type     VARCHAR(255) NOT NULL,
                    event_version  INT          NOT NULL,
                    payload        BYTEA        NOT NULL,
                    recorded_at    TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
                    CONSTRAINT uq_event_stream_version
                        UNIQUE (aggregate_type, stream_key, version),
                    CONSTRAINT ck_event_stream_version CHECK (version >= 1),
                    CONSTRAINT ck_event_stream_event_version CHECK (event_version >= 1),
                    CONSTRAINT ck_event_stream_type CHECK (length(aggregate_type) > 0),
                    CONSTRAINT ck_event_stream_key CHECK (length(stream_key) > 0)
                );

                CREATE INDEX IF NOT EXISTS idx_event_stream_type_recorded
                    ON event_stream (aggregate_type, recorded_at);
                """,
                """
                DROP TABLE IF EXISTS event_stream;
                """);
    }
}
