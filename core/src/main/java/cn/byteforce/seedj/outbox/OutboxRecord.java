package cn.byteforce.seedj.outbox;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * 一条待可靠投递的记录。
 *
 * <p>outbox 不解释 {@code payload}：它是不透明字节，序列化由生产者负责。
 * {@code destination} / {@code contentType} / {@code contentVersion} 的语义由
 * 投递侧的 {@link cn.byteforce.seedj.outbox.spi.OutboxSink} 解释。
 *
 * <p>{@code messageId} 是幂等键：同一个 {@code messageId} 重复写入 outbox 只落一行。
 *
 * <p>{@code sequence} 刻意<b>不在</b>这里：分区内顺序由
 * {@link cn.byteforce.seedj.outbox.spi.OutboxStore} 分配，生产者不需要（也无法）知道。
 */
public record OutboxRecord(
        String messageId,
        String destination,
        String partitionKey,
        String contentType,
        int contentVersion,
        byte[] payload,
        Map<String, String> headers,
        Instant occurredAt) {

    public OutboxRecord {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (contentVersion < 1) {
            throw new IllegalArgumentException("contentVersion >= 1");
        }
        payload = payload.clone();
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    /**
     * 最简单的记录：{@code messageId} 由调用方的 {@link cn.byteforce.seedj.id.IdGenerator}
     * 产生，{@code contentVersion=1}，空 headers。
     */
    public static OutboxRecord of(String messageId, String destination, String partitionKey,
                                  String contentType, byte[] payload) {
        return new OutboxRecord(messageId, destination, partitionKey,
                contentType, 1, payload, Map.of(), Instant.now());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OutboxRecord that)) {
            return false;
        }
        return contentVersion == that.contentVersion
                && Objects.equals(messageId, that.messageId)
                && Objects.equals(destination, that.destination)
                && Objects.equals(partitionKey, that.partitionKey)
                && Objects.equals(contentType, that.contentType)
                && Arrays.equals(payload, that.payload)
                && Objects.equals(headers, that.headers)
                && Objects.equals(occurredAt, that.occurredAt);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(messageId, destination, partitionKey, contentType,
                contentVersion, headers, occurredAt);
        return 31 * result + Arrays.hashCode(payload);
    }

    /** 脱敏：不输出 payload。 */
    @Override
    public String toString() {
        return "OutboxRecord{messageId='" + messageId + "', destination='" + destination
                + "', partitionKey='" + partitionKey + "', contentType='" + contentType
                + "', contentVersion=" + contentVersion + ", payloadBytes=" + payload.length
                + ", occurredAt=" + occurredAt + '}';
    }
}
