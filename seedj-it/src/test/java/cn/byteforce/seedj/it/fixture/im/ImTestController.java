package cn.byteforce.seedj.it.fixture.im;

import cn.byteforce.seedj.eventbus.EventMessage;
import cn.byteforce.seedj.eventbus.EventPublisher;
import cn.byteforce.seedj.eventbus.spi.EventBusTransport;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.infra.local.eventbus.FileEventBusTransport;
import cn.byteforce.seedj.outbox.spi.OutboxStore;
import cn.byteforce.seedj.transit.Ciphertext;
import cn.byteforce.seedj.transit.CreateKeyRequest;
import cn.byteforce.seedj.transit.DecryptRequest;
import cn.byteforce.seedj.transit.EncryptRequest;
import cn.byteforce.seedj.transit.KeyType;
import cn.byteforce.seedj.transit.TransitException;
import cn.byteforce.seedj.transit.TransitProvider;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅测试用的业务资源端点：
 *
 * <ul>
 *   <li>{@code /api/...}：资源服务器契约（JWT / scope）；
 *   <li>{@code /api/public/test/...}：基础设施 SPI 的端到端探测（eventbus / transit / id）。
 * </ul>
 */
@RestController
@RequestMapping
public class ImTestController {

    private final EventPublisher eventPublisher;
    private final ObjectProvider<OutboxStore> outboxStore;
    private final ObjectProvider<EventBusTransport> transport;
    private final JdbcTemplate jdbcTemplate;
    private final TransitProvider transit;
    private final IdGenerator<String> idGenerator;

    public ImTestController(
            EventPublisher eventPublisher,
            ObjectProvider<OutboxStore> outboxStore,
            ObjectProvider<EventBusTransport> transport,
            JdbcTemplate jdbcTemplate,
            TransitProvider transit,
            IdGenerator<String> idGenerator) {
        this.eventPublisher = eventPublisher;
        this.outboxStore = outboxStore;
        this.transport = transport;
        this.jdbcTemplate = jdbcTemplate;
        this.transit = transit;
        this.idGenerator = idGenerator;
    }

    // ==================== 资源服务器 ====================

    @GetMapping("/api/me")
    public Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
        return Map.of(
                "sub", jwt.getSubject(),
                "scope", jwt.getClaimAsStringList("scope"),
                "iss", jwt.getClaimAsString("iss"));
    }

    @GetMapping("/api/read")
    @PreAuthorize("hasAuthority('SCOPE_read')")
    public Map<String, Object> read() {
        return Map.of("ok", true);
    }

    // ==================== EventBus ====================

    /** 在事务内发布一条消息。重复传入同一 {@code eventId} 只落一行（outbox 幂等）。 */
    @PostMapping("/api/public/test/events")
    @Transactional
    public Map<String, Object> publishEvent(
            @RequestParam String topic,
            @RequestParam(required = false) String partitionKey,
            @RequestParam(required = false) String eventId,
            @RequestParam(defaultValue = "payload") String payload) {
        String id = eventId != null ? eventId : idGenerator.id();
        EventMessage message =
                new EventMessage(
                        id,
                        topic,
                        partitionKey,
                        topic,
                        1,
                        payload.getBytes(StandardCharsets.UTF_8),
                        Map.of("source", "it"),
                        Instant.now());
        eventPublisher.publish(message);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventId", id);
        result.put("outboxPresent", outboxStore.getIfAvailable() != null);
        result.put(
                "transport",
                transport.getIfAvailable() != null
                        ? transport.getIfAvailable().getClass().getSimpleName()
                        : "none");
        return result;
    }

    /** 查询 outbox 行状态；没有 outbox 时返回 {@code outbox=false}。 */
    @GetMapping("/api/public/test/events/{eventId}")
    public Map<String, Object> eventStatus(@PathVariable String eventId) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (outboxStore.getIfAvailable() == null) {
            result.put("outbox", false);
            return result;
        }
        var rows =
                jdbcTemplate.queryForList(
                        "SELECT status, attempts, sequence, lease_id FROM outbox WHERE message_id = ?",
                        eventId);
        result.put("outbox", true);
        result.put("count", rows.size());
        if (!rows.isEmpty()) {
            result.putAll(rows.get(0));
        }
        return result;
    }

    /** 磁盘队列目录（用于断言事件已落盘）。 */
    @GetMapping("/api/public/test/events/transport-dir")
    public Map<String, Object> transportDir() {
        EventBusTransport resolved = transport.getIfAvailable();
        if (resolved instanceof FileEventBusTransport file) {
            return Map.of("dir", file.baseDir().toString(), "type", "file");
        }
        return Map.of(
                "dir", "", "type", resolved == null ? "none" : resolved.getClass().getSimpleName());
    }

    // ==================== Transit ====================

    /** 完整走一遍：createKey → encrypt → decrypt → rotate → 旧密文仍可解密。 */
    @GetMapping("/api/public/test/transit/roundtrip")
    public Map<String, Object> transitRoundtrip() {
        String keyName = "it-transit-key";
        ensureKey(keyName);

        Ciphertext before = transit.crypto().encrypt(EncryptRequest.of(keyName, "hello"));
        String decrypted = transit.crypto().decrypt(DecryptRequest.of(keyName, before.getValue()));

        transit.keyAdmin().rotateKey(keyName);
        Ciphertext after = transit.crypto().encrypt(EncryptRequest.of(keyName, "hello"));
        String oldPlain = transit.crypto().decrypt(DecryptRequest.of(keyName, before.getValue()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("decrypted", decrypted);
        result.put("oldPlain", oldPlain);
        result.put("beforeVersion", before.getKeyVersion());
        result.put("afterVersion", after.getKeyVersion());
        result.put("latestVersion", transit.keyAdmin().readKey(keyName).getLatestVersion());
        return result;
    }

    /** 错误路径：对不存在的 key 加密必须给出错误码，而不是 500 无信息。 */
    @GetMapping("/api/public/test/transit/missing")
    public Map<String, Object> transitMissing() {
        try {
            transit.crypto().encrypt(EncryptRequest.of("no-such-key-" + UUID.randomUUID(), "x"));
            return Map.of("code", "NONE");
        } catch (TransitException e) {
            return Map.of("code", e.getErrorCode().name(), "retryable", e.isRetryable());
        }
    }

    private void ensureKey(String keyName) {
        try {
            transit.keyAdmin().readKey(keyName);
        } catch (TransitException e) {
            transit.keyAdmin().createKey(CreateKeyRequest.of(keyName, KeyType.AES256_GCM96));
        }
    }

    // ==================== ID ====================

    @GetMapping("/api/public/test/ids")
    public Map<String, Object> ids() {
        String first = idGenerator.id();
        String second = idGenerator.id();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", first);
        result.put("monotonic", Long.parseLong(second) > Long.parseLong(first));
        return result;
    }
}
