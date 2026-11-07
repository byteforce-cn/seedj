package cn.byteforce.seedj.infra.local.eventbus;

import cn.byteforce.seedj.eventbus.EventBusErrorCode;
import cn.byteforce.seedj.eventbus.EventBusException;
import cn.byteforce.seedj.eventbus.EventMessage;
import cn.byteforce.seedj.eventbus.spi.DeadLetter;
import cn.byteforce.seedj.eventbus.spi.DeliveryFailure;
import cn.byteforce.seedj.eventbus.spi.EventBusTransport;
import cn.byteforce.seedj.eventbus.spi.EventConsumerStore;
import cn.byteforce.seedj.eventbus.spi.PollRequest;
import cn.byteforce.seedj.eventbus.spi.ReceivedEvent;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.infra.local.id.LocalStringIdGenerator;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 磁盘队列（同时支持发布与带消费者组的消费）。
 *
 * <pre>
 * &lt;base-dir&gt;/&lt;topic&gt;/&lt;epochMillis&gt;-&lt;eventId&gt;.payload
 * &lt;base-dir&gt;/&lt;topic&gt;/&lt;epochMillis&gt;-&lt;eventId&gt;.meta
 * &lt;base-dir&gt;/&lt;topic&gt;/.groups/&lt;group&gt;/acks/&lt;base&gt;.ack
 * &lt;base-dir&gt;/&lt;topic&gt;/.groups/&lt;group&gt;/claims/&lt;base&gt;.&lt;deliveryId&gt;.&lt;untilMillis&gt;.&lt;attempts&gt;
 * &lt;base-dir&gt;/&lt;topic&gt;/.groups/&lt;group&gt;/dlq/&lt;base&gt;.&lt;deliveryId&gt;.meta
 * </pre>
 *
 * <p><b>消费者组</b>：ack/claim 都按 group 隔离。同一 group 内用 {@code CREATE_NEW} 原子创建 claim 文件，跨进程、同 JVM
 * 多实例都只有一个能抢到；租约到期后其他实例 可以接管。不同 group 互不影响。
 *
 * <p><b>完成判定</b>：{@code .meta} 文件存在即表示该事件已完整写入（先写 payload，再写 meta）。
 */
public class FileEventBusTransport implements EventBusTransport, EventConsumerStore {

    private static final Logger log = LoggerFactory.getLogger(FileEventBusTransport.class);

    private static final String META = ".meta";
    private static final String PAYLOAD = ".payload";
    private static final String ACK = ".ack";

    private final Path baseDir;
    private final IdGenerator<String> idGenerator;
    private volatile boolean closed;

    public FileEventBusTransport(Path baseDir) {
        this(baseDir, new LocalStringIdGenerator());
    }

    public FileEventBusTransport(Path baseDir, IdGenerator<String> idGenerator) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
        this.idGenerator = idGenerator;
    }

    public Path baseDir() {
        return baseDir;
    }

    // ==================== 发布侧 ====================

    @Override
    public CompletionStage<Void> send(EventMessage message) {
        if (closed) {
            return CompletableFuture.failedFuture(
                    new EventBusException(
                            EventBusErrorCode.PROVIDER_CLOSED, "transport is closed"));
        }
        try {
            Path topicDir = ensureTopicDir(message.topic());
            String baseName = message.occurredAt().toEpochMilli() + "-" + safeId(message.eventId());

            Path payload = topicDir.resolve(baseName + PAYLOAD);
            Files.write(
                    payload,
                    message.payload(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);

            Properties meta = new Properties();
            meta.setProperty("eventId", message.eventId());
            meta.setProperty("topic", message.topic());
            if (message.partitionKey() != null) {
                meta.setProperty("partitionKey", message.partitionKey());
            }
            meta.setProperty("eventType", message.eventType());
            meta.setProperty("eventVersion", Integer.toString(message.eventVersion()));
            meta.setProperty("occurredAt", message.occurredAt().toString());
            meta.setProperty("payloadFile", payload.getFileName().toString());
            meta.setProperty("payloadSha256", sha256(message.payload()));
            message.headers().forEach((k, v) -> meta.setProperty("header." + k, v));

            // 先写临时文件再原子移动：消费方看到 .meta 时内容一定完整。
            Path metaFile = topicDir.resolve(baseName + META);
            Path metaTemp = topicDir.resolve(baseName + META + ".tmp");
            try (var out =
                    Files.newOutputStream(
                            metaTemp,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE)) {
                meta.store(out, "seedj eventbus message");
            }
            Files.move(
                    metaTemp,
                    metaFile,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return CompletableFuture.completedFuture(null);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(
                    new EventBusException(
                            EventBusErrorCode.SEND_FAILED,
                            "failed to write event to " + baseDir,
                            e));
        }
    }

    @Override
    public void ensureTopic(String topic) {
        try {
            ensureTopicDir(topic);
        } catch (IOException e) {
            throw new EventBusException(
                    EventBusErrorCode.TRANSPORT_UNAVAILABLE,
                    "cannot create topic directory for " + topic,
                    e);
        }
    }

    @Override
    public boolean topicExists(String topic) {
        return Files.isDirectory(topicDir(topic));
    }

    // ==================== 消费侧 ====================

    @Override
    public List<ReceivedEvent> poll(PollRequest request) {
        requireOpen();
        List<ReceivedEvent> result = new ArrayList<>();
        Instant now = Instant.now();
        for (String topic : request.topics()) {
            Path topicDir = topicDir(topic);
            if (!Files.isDirectory(topicDir)) {
                continue;
            }
            Path groupDir = groupDir(topicDir, request.group());
            try {
                ensureGroupDirs(groupDir);
            } catch (IOException e) {
                throw new EventBusException(
                        EventBusErrorCode.TRANSPORT_UNAVAILABLE,
                        "cannot create consumer group directory for " + request.group(),
                        e);
            }
            for (String baseName : listMessageBaseNames(topicDir)) {
                if (result.size() >= request.limit()) {
                    return result;
                }
                if (isAcked(groupDir, baseName)) {
                    continue;
                }
                ReceivedEvent claimed =
                        tryClaim(
                                topicDir,
                                groupDir,
                                baseName,
                                request.group(),
                                request.lease(),
                                now);
                if (claimed != null) {
                    result.add(claimed);
                }
            }
        }
        return result;
    }

    @Override
    public void ack(ReceivedEvent event) {
        requireOpen();
        Path topicDir = topicDir(event.message().topic());
        Path groupDir = groupDir(topicDir, event.group());
        Path claim = findClaim(groupDir, event);
        if (claim == null) {
            if (isAcked(groupDir, baseNameOf(event.message()))) {
                return;
            }
            throw leaseLost("ack", event);
        }
        String baseName = claimBaseName(claim);
        try {
            Files.deleteIfExists(claim);
            Path ackFile = groupDir.resolve("acks").resolve(baseName + ACK);
            Files.createFile(ackFile);
        } catch (FileAlreadyExistsException ignored) {
            // 重复 ack：幂等
        } catch (IOException e) {
            throw new EventBusException(
                    EventBusErrorCode.TRANSPORT_UNAVAILABLE,
                    "cannot ack " + event.message().eventId(),
                    e);
        }
    }

    @Override
    public void retry(ReceivedEvent event, Duration delay, DeliveryFailure failure) {
        requireOpen();
        Path topicDir = topicDir(event.message().topic());
        Path groupDir = groupDir(topicDir, event.group());
        Path claim = findClaim(groupDir, event);
        if (claim == null) {
            throw leaseLost("retry", event);
        }
        try {
            if (delay == null || delay.isZero() || delay.isNegative()) {
                Files.deleteIfExists(claim);
                return;
            }
            // 先创建延迟 claim，再删除旧 claim：中间态对 poll 始终是“已领取”，不会重复投递。
            String baseName = claimBaseName(claim);
            Path delayed =
                    claimPath(
                            groupDir,
                            baseName,
                            idGenerator.id(),
                            Instant.now().plus(delay),
                            event.attempts());
            Files.createFile(delayed);
            Files.deleteIfExists(claim);
        } catch (IOException e) {
            throw new EventBusException(
                    EventBusErrorCode.TRANSPORT_UNAVAILABLE,
                    "cannot retry " + event.message().eventId(),
                    e);
        }
    }

    @Override
    public void deadLetter(ReceivedEvent event, DeliveryFailure failure) {
        requireOpen();
        Path topicDir = topicDir(event.message().topic());
        Path groupDir = groupDir(topicDir, event.group());
        Path claim = findClaim(groupDir, event);
        if (claim == null) {
            throw leaseLost("dead-letter", event);
        }
        String baseName = claimBaseName(claim);
        try {
            copyToDeadLetter(topicDir, groupDir, baseName, event, failure);
            Path ackFile = groupDir.resolve("acks").resolve(baseName + ACK);
            try {
                Files.createFile(ackFile);
            } catch (FileAlreadyExistsException ignored) {
                // 幂等
            }
            Files.deleteIfExists(claim);
        } catch (IOException e) {
            throw new EventBusException(
                    EventBusErrorCode.TRANSPORT_UNAVAILABLE,
                    "cannot dead-letter " + event.message().eventId(),
                    e);
        }
    }

    @Override
    public List<DeadLetter> deadLetters(String topic, String group, int limit) {
        Path topicDir = topicDir(topic);
        Path dlqDir = groupDir(topicDir, group).resolve("dlq");
        if (!Files.isDirectory(dlqDir) || limit <= 0) {
            return List.of();
        }
        List<DeadLetter> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(dlqDir)) {
            files.filter(path -> path.getFileName().toString().endsWith(META))
                    .sorted()
                    .limit(limit)
                    .forEach(
                            meta -> {
                                DeadLetter deadLetter = readDeadLetter(meta);
                                if (deadLetter != null) {
                                    result.add(deadLetter);
                                }
                            });
        } catch (IOException e) {
            throw new EventBusException(
                    EventBusErrorCode.TRANSPORT_UNAVAILABLE,
                    "cannot list dead letters for " + topic,
                    e);
        }
        return result;
    }

    @Override
    public void close() {
        closed = true;
    }

    // ==================== claim / ack 辅助 ====================

    private ReceivedEvent tryClaim(
            Path topicDir,
            Path groupDir,
            String baseName,
            String group,
            Duration lease,
            Instant now) {
        Path claimsDir = groupDir.resolve("claims");
        int previousAttempts = cleanAndInspectClaims(claimsDir, baseName, now);
        if (previousAttempts < 0) {
            return null; // 有未过期 claim
        }
        EventMessage message = readMessage(topicDir, baseName);
        if (message == null) {
            return null;
        }
        int attempts = previousAttempts + 1;
        String deliveryId = idGenerator.id();
        Instant leaseUntil = now.plus(lease);
        Path claim = claimPath(groupDir, baseName, deliveryId, leaseUntil, attempts);
        try {
            Files.createFile(claim);
        } catch (FileAlreadyExistsException e) {
            return null; // 另一个实例赢得了这次领取
        } catch (IOException e) {
            throw new EventBusException(
                    EventBusErrorCode.TRANSPORT_UNAVAILABLE, "cannot claim " + baseName, e);
        }
        return new ReceivedEvent(deliveryId, group, message, attempts, leaseUntil);
    }

    /** 清理过期 claim 并返回历史最大 attempts；存在未过期 claim 时返回 -1。 */
    private int cleanAndInspectClaims(Path claimsDir, String baseName, Instant now) {
        int maxAttempts = 0;
        boolean live = false;
        List<Path> claims = listClaims(claimsDir, baseName);
        for (Path claim : claims) {
            Claim parsed = parseClaim(claim);
            if (parsed == null) {
                continue;
            }
            if (parsed.leaseUntil().isAfter(now)) {
                live = true;
            } else {
                try {
                    Files.deleteIfExists(claim);
                } catch (IOException e) {
                    log.warn("cannot delete expired claim {}: {}", claim, e.getMessage());
                }
            }
            maxAttempts = Math.max(maxAttempts, parsed.attempts());
        }
        return live ? -1 : maxAttempts;
    }

    private Path findClaim(Path groupDir, ReceivedEvent event) {
        for (Path claim : listClaims(groupDir.resolve("claims"), baseNameOf(event.message()))) {
            Claim parsed = parseClaim(claim);
            if (parsed != null && parsed.deliveryId().equals(event.deliveryId())) {
                return claim;
            }
        }
        return null;
    }

    private static Path claimPath(
            Path groupDir, String baseName, String deliveryId, Instant until, int attempts) {
        return groupDir.resolve("claims")
                .resolve(baseName + "." + deliveryId + "." + until.toEpochMilli() + "." + attempts);
    }

    private static String claimBaseName(Path claim) {
        Claim parsed = parseClaim(claim);
        if (parsed == null) {
            throw new EventBusException(
                    EventBusErrorCode.LEASE_LOST, "unreadable claim file: " + claim.getFileName());
        }
        return parsed.baseName();
    }

    private static Claim parseClaim(Path claim) {
        String name = claim.getFileName().toString();
        String[] parts = name.split("\\.");
        if (parts.length != 4) {
            return null;
        }
        try {
            return new Claim(
                    parts[0],
                    parts[1],
                    Instant.ofEpochMilli(Long.parseLong(parts[2])),
                    Integer.parseInt(parts[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<Path> listClaims(Path claimsDir, String baseName) {
        if (!Files.isDirectory(claimsDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(claimsDir)) {
            return files.filter(path -> path.getFileName().toString().startsWith(baseName + "."))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new EventBusException(
                    EventBusErrorCode.TRANSPORT_UNAVAILABLE,
                    "cannot list claims in " + claimsDir,
                    e);
        }
    }

    private record Claim(String baseName, String deliveryId, Instant leaseUntil, int attempts) {}

    // ==================== 文件布局 / 消息读写 ====================

    private Path topicDir(String topic) {
        return baseDir.resolve(safeTopic(topic));
    }

    private Path ensureTopicDir(String topic) throws IOException {
        return Files.createDirectories(topicDir(topic));
    }

    private static Path groupDir(Path topicDir, String group) {
        return topicDir.resolve(".groups").resolve(safeGroup(group));
    }

    private static void ensureGroupDirs(Path groupDir) throws IOException {
        Files.createDirectories(groupDir.resolve("acks"));
        Files.createDirectories(groupDir.resolve("claims"));
        Files.createDirectories(groupDir.resolve("dlq"));
    }

    private static List<String> listMessageBaseNames(Path topicDir) {
        try (Stream<Path> files = Files.list(topicDir)) {
            return files.filter(path -> path.getFileName().toString().endsWith(META))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(FileEventBusTransport::baseName)
                    .toList();
        } catch (IOException e) {
            throw new EventBusException(
                    EventBusErrorCode.TRANSPORT_UNAVAILABLE,
                    "cannot list messages in " + topicDir,
                    e);
        }
    }

    private static String baseName(Path metaFile) {
        String name = metaFile.getFileName().toString();
        return name.endsWith(META) ? name.substring(0, name.length() - META.length()) : name;
    }

    private static String baseNameOf(EventMessage message) {
        return message.occurredAt().toEpochMilli() + "-" + safeId(message.eventId());
    }

    private static boolean isAcked(Path groupDir, String baseName) {
        return Files.exists(groupDir.resolve("acks").resolve(baseName + ACK));
    }

    private EventMessage readMessage(Path topicDir, String baseName) {
        Path metaFile = topicDir.resolve(baseName + META);
        if (!Files.isRegularFile(metaFile)) {
            return null;
        }
        try {
            Properties meta = load(metaFile);
            String payloadName = meta.getProperty("payloadFile", baseName + PAYLOAD);
            Path payload = topicDir.resolve(payloadName).normalize();
            if (!payload.startsWith(topicDir) || !Files.isRegularFile(payload)) {
                return null;
            }
            String partitionKey = meta.getProperty("partitionKey");
            if (partitionKey == null || "null".equals(partitionKey)) {
                partitionKey = null;
            }
            Map<String, String> headers = new java.util.LinkedHashMap<>();
            for (String name : meta.stringPropertyNames()) {
                if (name.startsWith("header.")) {
                    headers.put(name.substring("header.".length()), meta.getProperty(name));
                }
            }
            return new EventMessage(
                    meta.getProperty("eventId"),
                    meta.getProperty("topic"),
                    partitionKey,
                    meta.getProperty("eventType"),
                    Integer.parseInt(meta.getProperty("eventVersion", "1")),
                    Files.readAllBytes(payload),
                    headers,
                    Instant.parse(meta.getProperty("occurredAt")));
        } catch (IOException | RuntimeException e) {
            log.warn(
                    "skip unreadable event {}/{}: {}",
                    topicDir.getFileName(),
                    baseName,
                    e.getMessage());
            return null;
        }
    }

    private void copyToDeadLetter(
            Path topicDir,
            Path groupDir,
            String baseName,
            ReceivedEvent event,
            DeliveryFailure failure)
            throws IOException {
        Path dlqDir = groupDir.resolve("dlq");
        Files.createDirectories(dlqDir);
        String id = baseName + "." + event.deliveryId();

        Path sourceMeta = topicDir.resolve(baseName + META);
        Path sourcePayload = topicDir.resolve(baseName + PAYLOAD);
        Files.copy(
                sourcePayload,
                dlqDir.resolve(id + PAYLOAD),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        Properties meta = load(sourceMeta);
        meta.setProperty("deadLetter.id", id);
        meta.setProperty("deadLetter.group", event.group());
        meta.setProperty("deadLetter.failureType", failure.errorType());
        meta.setProperty(
                "deadLetter.failureMessage",
                failure.errorMessage() == null ? "" : failure.errorMessage());
        meta.setProperty("deadLetter.retryable", Boolean.toString(failure.retryable()));
        meta.setProperty("deadLetter.deadLetteredAt", Instant.now().toString());
        meta.setProperty("deadLetter.payloadFile", id + PAYLOAD);
        Path targetMeta = dlqDir.resolve(id + META);
        try (var out =
                Files.newOutputStream(
                        targetMeta,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)) {
            meta.store(out, "seedj eventbus dead letter");
        }
    }

    private DeadLetter readDeadLetter(Path metaFile) {
        try {
            Properties meta = load(metaFile);
            Path payload =
                    metaFile.getParent().resolve(meta.getProperty("deadLetter.payloadFile", ""));
            if (!Files.isRegularFile(payload)) {
                return null;
            }
            EventMessage message =
                    new EventMessage(
                            meta.getProperty("eventId"),
                            meta.getProperty("topic"),
                            "null".equals(meta.getProperty("partitionKey"))
                                    ? null
                                    : meta.getProperty("partitionKey"),
                            meta.getProperty("eventType"),
                            Integer.parseInt(meta.getProperty("eventVersion", "1")),
                            Files.readAllBytes(payload),
                            Map.of(),
                            Instant.parse(meta.getProperty("occurredAt")));
            DeliveryFailure failure =
                    new DeliveryFailure(
                            meta.getProperty("deadLetter.failureType"),
                            meta.getProperty("deadLetter.failureMessage"),
                            Boolean.parseBoolean(
                                    meta.getProperty("deadLetter.retryable", "false")));
            return new DeadLetter(
                    meta.getProperty("deadLetter.id"),
                    message.topic(),
                    meta.getProperty("deadLetter.group"),
                    message,
                    failure,
                    Instant.parse(meta.getProperty("deadLetter.deadLetteredAt")));
        } catch (IOException | RuntimeException e) {
            log.warn("skip unreadable dead letter {}: {}", metaFile, e.getMessage());
            return null;
        }
    }

    private static Properties load(Path file) throws IOException {
        Properties properties = new Properties();
        try (var in = Files.newInputStream(file)) {
            properties.load(in);
        }
        return properties;
    }

    // ==================== 公共工具 ====================

    private void requireOpen() {
        if (closed) {
            throw new EventBusException(EventBusErrorCode.PROVIDER_CLOSED, "consumer is closed");
        }
    }

    private static EventBusException leaseLost(String action, ReceivedEvent event) {
        return new EventBusException(
                EventBusErrorCode.LEASE_LOST,
                "lease lost, cannot " + action + ": " + event.message().eventId());
    }

    static String safeTopic(String topic) {
        String safe = topic.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isEmpty()) {
            return "_";
        }
        // 防止 "." / ".." 被当成目录项
        return safe.startsWith(".") ? "_" + safe : safe;
    }

    static String safeGroup(String group) {
        return safeTopic(group);
    }

    private static String safeId(String eventId) {
        return eventId.replaceAll("[^A-Za-z0-9-]", "_");
    }

    private static String sha256(byte[] data) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(data);
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
