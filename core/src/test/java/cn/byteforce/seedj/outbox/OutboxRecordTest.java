package cn.byteforce.seedj.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutboxRecordTest {

    @Test
    @DisplayName("of() 生成随机 messageId、版本 1、空 headers")
    void ofFillsDefaults() {
        OutboxRecord record =
                OutboxRecord.of(
                        "m-1",
                        "order.cancelled",
                        "agg-1",
                        "order.cancelled",
                        "{}".getBytes(StandardCharsets.UTF_8));

        assertThat(record.messageId()).isNotBlank();
        assertThat(record.destination()).isEqualTo("order.cancelled");
        assertThat(record.contentType()).isEqualTo("order.cancelled");
        assertThat(record.contentVersion()).isEqualTo(1);
        assertThat(record.headers()).isEmpty();
        assertThat(record.occurredAt()).isNotNull();
    }

    @Test
    @DisplayName("payload 不可变：入参与出参都不是内部数组")
    void payloadIsDefensive() {
        byte[] payload = "hi".getBytes(StandardCharsets.UTF_8);
        OutboxRecord record = OutboxRecord.of("m-1", "dest", "key", "type", payload);

        payload[0] = 'X';
        assertThat(new String(record.payload(), StandardCharsets.UTF_8)).isEqualTo("hi");

        record.payload()[0] = 'X';
        assertThat(new String(record.payload(), StandardCharsets.UTF_8)).isEqualTo("hi");
    }

    @Test
    @DisplayName("headers 不可变")
    void headersAreImmutable() {
        OutboxRecord record =
                new OutboxRecord(
                        "m1",
                        "dest",
                        "key",
                        "type",
                        1,
                        new byte[0],
                        Map.of("k", "v"),
                        Instant.now());

        assertThatThrownBy(() -> record.headers().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("equals/hashCode 按内容比较 payload")
    void equalityUsesPayloadContent() {
        Instant at = Instant.parse("2026-01-01T00:00:00Z");
        OutboxRecord a =
                new OutboxRecord("m1", "dest", "k", "type", 1, new byte[] {1, 2}, Map.of(), at);
        OutboxRecord b =
                new OutboxRecord("m1", "dest", "k", "type", 1, new byte[] {1, 2}, Map.of(), at);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }

    @Test
    @DisplayName("非法记录在构造期失败")
    void validation() {
        assertThatThrownBy(
                        () ->
                                new OutboxRecord(
                                        "m1",
                                        "dest",
                                        null,
                                        "type",
                                        0,
                                        new byte[0],
                                        null,
                                        Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new OutboxRecord(
                                        "m1", "dest", null, "type", 1, null, null, Instant.now()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () ->
                                new OutboxRecord(
                                        null,
                                        "dest",
                                        null,
                                        "type",
                                        1,
                                        new byte[0],
                                        null,
                                        Instant.now()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("toString 不输出 payload")
    void toStringRedacts() {
        OutboxRecord record =
                OutboxRecord.of(
                        "m-1", "dest", "k", "type", "secret".getBytes(StandardCharsets.UTF_8));
        assertThat(record.toString()).doesNotContain("secret").contains("dest");
    }
}
