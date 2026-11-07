package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.cqrs.spi.CommandStore;
import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.AppendResult;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.ObjectVersion;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.domain.ScopePredicates;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.domain.TemporalStore;
import cn.byteforce.seedj.domain.event.CommandEvent;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.lock.LockLease;
import cn.byteforce.seedj.lock.LockProvider;
import cn.byteforce.seedj.outbox.OutboxRecord;
import cn.byteforce.seedj.outbox.OutboxWriter;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 命令运行时：幂等、解码、校验、锁、事务、写范围、事件与终态的协调点。
 *
 * <p>顺序是契约的一部分：</p>
 * <ol>
 *   <li>按幂等键回读；命中则回放/冲突，不解码、不调用 {@code targetId}；</li>
 *   <li>解码 → 校验 → {@code targetId}；</li>
 *   <li>写入 {@code PENDING} 命令记录；</li>
 *   <li>获取对象锁；</li>
 *   <li>事务内：读当前行 → 写范围检查 → {@code decide} → 结果字段写范围检查 →
 *       追加 → 事件写 outbox → 命令终态；</li>
 *   <li>业务拒绝写拒绝终态；技术故障保持 {@code PENDING} 并释放幂等键。</li>
 * </ol>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class CommandRuntime {

    private final Options options;

    /**
     * 构造运行时。
     *
     * @param options 协调器依赖
     */
    public CommandRuntime(Options options) {
        this.options = Objects.requireNonNull(options, "options");
    }

    /**
     * 执行一次命令。
     *
     * @param submission   命令提交
     * @param registration 命令注册项
     * @param caller       调用方
     * @param writeScope   写范围
     * @return 受理结果
     */
    public CommandReceipt execute(CommandSubmission submission,
                                  CommandRegistration registration, Caller caller,
                                  ScopePredicate writeScope) {
        Objects.requireNonNull(submission, "submission");
        Objects.requireNonNull(registration, "registration");
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(writeScope, "writeScope");
        CommandDescriptor descriptor = registration.descriptor();
        ObjectSchema schema = registration.schema();
        String objectType = requireObjectType(descriptor.objectType());
        String fingerprint = IdempotencyFingerprint.of(submission);

        Optional<CommandRecord> existing = options.commandStore()
                .findByIdempotencyKey(submission.issuer(), submission.idempotencyKey());
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }

        requireKnownPayloadFields(submission.payload(), schema, registration.commandFields());
        Object command = decode(submission.payload(), descriptor);
        validate(descriptor, command);
        UUID targetId = requireTargetId(registration.handler(), command);

        Instant now = options.clock().now();
        Map<String, Object> storedPayload = storedPayload(submission.payload(), schema,
                registration.redactedFields(), descriptor.redactor());
        CommandRecord pending = CommandRecord.pending(options.idGenerator().id(), submission.type(),
                now, submission.issuer(), storedPayload, submission.idempotencyKey(),
                fingerprint, now, now.plus(options.pendingLease()));
        if (!options.commandStore().insert(pending)) {
            CommandRecord winner = options.commandStore()
                    .findByIdempotencyKey(submission.issuer(), submission.idempotencyKey())
                    .orElseThrow(() -> new IllegalStateException(
                            "idempotency key conflicted but no record is readable: "
                                    + submission.idempotencyKey()));
            return replayOrConflict(winner, fingerprint);
        }

        try {
            String lockKey = objectType + ':' + targetId;
            try (LockLease ignored = options.lockProvider().acquire(lockKey, options.lockTtl())) {
                try {
                    CommandRecord accepted = options.transactionRunner().inTransaction(() -> {
                        Optional<TemporalRow> current = options.temporalStore()
                                .current(objectType, targetId);
                        requireCreateTargetMissing(registration, current, objectType, targetId);
                        requireWriteScope(current, writeScope, schema, objectType);
                        Instant appendNow = options.clock().now();
                        CommandContext context = new CommandContext(pending.id(), targetId,
                                current, appendNow, submission.issuer(), caller, writeScope,
                                schema, options.idGenerator());
                        AppendRequest request = handler(registration).decide(command, context);
                        if (request == null) {
                            throw new IllegalStateException(
                                    "command handler returned a null append request");
                        }
                        requireSchemaFields(request, schema);
                        requireResultScope(request, writeScope, schema, appendNow,
                                submission.issuer());
                        AppendResult result = options.temporalStore().append(request);
                        List<CommandEvent> events = handler(registration)
                                .events(command, result, context);
                        requireDeclaredEvents(events, registration.emits());
                        events = filterEvents(events, schema);
                        List<UUID> eventIds = writeEvents(events);
                        CommandRecord record = pending.accepted(result.appended().version().id(),
                                result.appended().version().version(), eventIds);
                        options.temporalAudit().accepted(record, descriptor.objectType(), events);
                        options.commandStore().complete(record);
                        return record;
                    });
                    return CommandReceipt.from(accepted);
                } catch (ContractException error) {
                    throw reject(pending, error);
                } catch (IllegalArgumentException error) {
                    throw reject(pending, invalidPayload(error));
                }
            }
        } catch (ContractException error) {
            throw error;
        } catch (RuntimeException error) {
            // 技术故障没有终态：释放幂等键，让同一命令可以安全重试（EIS-503-001）。
            options.commandStore().abandon(pending.id());
            throw error;
        }
    }

    /**
     * 分配一个新标识（事件等）。
     *
     * @return UUIDv7
     */
    public UUID newId() {
        return options.idGenerator().id();
    }

    /**
     * 当前系统时间。
     *
     * @return UTC 微秒时间
     */
    public Instant now() {
        return options.clock().now();
    }

    @SuppressWarnings("unchecked")
    private static CommandHandler<Object> handler(CommandRegistration registration) {
        return (CommandHandler<Object>) registration.handler();
    }

    private Object decode(Map<String, Object> payload, CommandDescriptor descriptor) {
        try {
            return options.jsonMapper().convertValue(payload, descriptor.payloadType());
        } catch (RuntimeException error) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                    "invalid payload for " + descriptor.type(),
                    Map.of("command_type", descriptor.type()), null);
        }
    }

    /**
     * 拒绝 schema 与命令专用字段之外的载荷键。
     *
     * <p>Jackson 默认丢弃未知属性，PostgreSQL 也只写 {@code table.columns()} 里的列：
     * 两者叠加会让拼错的字段静默消失。这里在解码前显式失败（400）。</p>
     */
    private static void requireKnownPayloadFields(Map<String, Object> payload,
                                                  ObjectSchema schema,
                                                  Set<String> commandFields) {
        Set<String> unknown = new java.util.TreeSet<>(payload.keySet());
        unknown.removeAll(schema.fieldNames());
        unknown.removeAll(commandFields);
        if (!unknown.isEmpty()) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                    "unknown fields for " + schema.objectType() + ": " + unknown);
        }
    }

    /**
     * 处理器产出的持久化字段必须完全落在 schema 内，并满足必填与类型约束。
     *
     * <p>此前未知字段会被 JDBC 层静默丢弃，导致“命令成功、数据缺失”；现在失败关闭。</p>
     */
    private static void requireSchemaFields(AppendRequest request, ObjectSchema schema) {
        schema.validate(request.fields());
    }

    private static void validate(CommandDescriptor descriptor, Object command) {
        try {
            descriptor.validator().validate(command);
        } catch (ContractException error) {
            throw error;
        } catch (IllegalArgumentException error) {
            throw invalidPayload(error);
        }
    }

    @SuppressWarnings("unchecked")
    private static UUID requireTargetId(CommandHandler<?> handler, Object command) {
        UUID targetId;
        try {
            targetId = ((CommandHandler<Object>) handler).targetId(command);
        } catch (RuntimeException error) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                    "command target is required");
        }
        if (targetId == null) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                    "command target is required");
        }
        return targetId;
    }

    private static void requireCreateTargetMissing(CommandRegistration registration,
                                                   Optional<TemporalRow> current,
                                                   String objectType, UUID targetId) {
        if (registration.creates() && current.isPresent()) {
            throw new ContractException(ContractErrorCode.COMMAND_REJECTED,
                    "object already exists: " + objectType + ":" + targetId,
                    Map.of("object_type", objectType, "id", targetId.toString()), null);
        }
    }

    private static void requireWriteScope(Optional<TemporalRow> current,
                                          ScopePredicate writeScope, ObjectSchema schema,
                                          String objectType) {
        if (current.isPresent()
                && !ScopePredicates.matches(current.get(), writeScope, schema)) {
            throw new ContractException(ContractErrorCode.FORBIDDEN,
                    "object is outside write scope: " + objectType);
        }
    }

    private static void requireResultScope(AppendRequest request, ScopePredicate writeScope,
                                           ObjectSchema schema, Instant now, UUID actor) {
        ObjectVersion version = new ObjectVersion(request.id(), 1, request.validFrom(),
                request.validTo(), now, null, actor, now, null, null);
        TemporalRow synthetic = new TemporalRow(schema.objectType(), version, request.fields());
        if (!ScopePredicates.matches(synthetic, writeScope, schema)) {
            throw new ContractException(ContractErrorCode.FORBIDDEN,
                    "command result is outside write scope: " + schema.objectType());
        }
    }

    private CommandReceipt replayOrConflict(CommandRecord existing, String fingerprint) {
        if (!existing.payloadFingerprint().equals(fingerprint)) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("idempotency_key", existing.idempotencyKey());
            details.put("original_command_id", existing.id().toString());
            details.put("original_result", originalResult(existing));
            throw new ContractException(ContractErrorCode.IDEMPOTENCY_CONFLICT,
                    "idempotency key already used with different content", details, null);
        }
        return CommandReceipt.from(existing);
    }

    private static Map<String, Object> originalResult(CommandRecord record) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", record.status().wireName());
        result.put("event_ids", record.eventIds().stream().map(UUID::toString).toList());
        if (record.locator() != null) {
            result.put("locator", record.locator().toString());
        }
        if (record.version() != null) {
            result.put("version", record.version());
        }
        if (record.rejectionCode() != null) {
            result.put("code", record.rejectionCode());
        }
        if (record.rejectionReason() != null) {
            result.put("reason", record.rejectionReason());
        }
        return result;
    }

    /**
     * 写入命令记录前的载荷收敛：自定义 redactor 先执行，随后无条件剔除 schema writeOnly
     * 与声明为 redacted 的命令专用字段。业务代码不需要为每条命令手工记住要删哪些键。
     */
    private static Map<String, Object> storedPayload(Map<String, Object> payload,
                                                     ObjectSchema schema,
                                                     Set<String> redactedFields,
                                                     CommandPayloadRedactor redactor) {
        Map<String, Object> redacted = new LinkedHashMap<>(redactor.redact(payload));
        redacted.keySet().removeAll(schema.writeOnlyFields());
        redacted.keySet().removeAll(redactedFields);
        return redacted;
    }

    private static void requireDeclaredEvents(List<CommandEvent> events, List<String> emits) {
        if (emits.isEmpty() || events == null) {
            return;
        }
        for (CommandEvent event : events) {
            if (!emits.contains(event.type())) {
                throw new IllegalStateException(
                        "undeclared command event: " + event.type() + "; declared " + emits);
            }
        }
    }

    private static List<CommandEvent> filterEvents(List<CommandEvent> events,
                                                   ObjectSchema schema) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        List<CommandEvent> filtered = new ArrayList<>(events.size());
        for (CommandEvent event : events) {
            filtered.add(filterEvent(event, schema));
        }
        return List.copyOf(filtered);
    }

    private List<UUID> writeEvents(List<CommandEvent> events) {
        if (events.isEmpty()) {
            return List.of();
        }
        Set<String> keys = new HashSet<>();
        List<UUID> eventIds = new ArrayList<>(events.size());
        for (CommandEvent event : events) {
            if (!keys.add(event.idempotencyKey())) {
                throw new IllegalStateException(
                        "duplicate event idempotency key: " + event.idempotencyKey());
            }
            writeOutbox(event);
            eventIds.add(event.id());
        }
        return List.copyOf(eventIds);
    }

    private static CommandEvent filterEvent(CommandEvent event, ObjectSchema schema) {
        Map<String, Object> filtered = schema.eventPayload(event.payload());
        if (filtered.equals(event.payload())) {
            return event;
        }
        return new CommandEvent(event.id(), event.type(), event.occurredAt(),
                event.recordedAt(), event.actor(), event.subjects(), filtered,
                event.idempotencyKey(), event.version());
    }

    private void writeOutbox(CommandEvent event) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", event.id().toString());
        body.put("type", event.type());
        body.put("occurred_at", event.occurredAt().toString());
        body.put("recorded_at", event.recordedAt().toString());
        body.put("actor", event.actor().toString());
        body.put("subjects", event.subjects().stream().map(UUID::toString).toList());
        body.put("payload", event.payload());
        body.put("idempotency_key", event.idempotencyKey());
        body.put("version", event.version());
        String partitionKey = event.subjects().isEmpty()
                ? null : event.subjects().get(0).toString();
        OutboxRecord record = new OutboxRecord(event.id().toString(), event.type(), partitionKey,
                "eis://core/event/v1", event.version(),
                options.jsonMapper().writeValueAsBytes(body),
                Map.of("event_type", event.type()), event.occurredAt());
        options.outboxWriter().append(record);
    }

    private ContractException reject(CommandRecord pending, ContractException error) {
        ContractException rejection = withCommandId(error, pending.id());
        try {
            options.commandStore().complete(pending.rejected(error.code(), error.reason()));
        } catch (RuntimeException persistence) {
            rejection.addSuppressed(persistence);
        }
        return rejection;
    }

    private static ContractException withCommandId(ContractException error, UUID commandId) {
        Map<String, Object> details = new LinkedHashMap<>(error.details());
        details.put("command_id", commandId.toString());
        return new ContractException(error.code(), error.reason(), details, error.reasonCode());
    }

    private static ContractException invalidPayload(IllegalArgumentException error) {
        String message = error.getMessage() == null ? "invalid command payload" : error.getMessage();
        return new ContractException(ContractErrorCode.INVALID_PAYLOAD, message);
    }

    private static String requireObjectType(String objectType) {
        if (objectType == null || objectType.isBlank()) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                    "command object type is required");
        }
        return objectType;
    }

    /**
     * 运行时依赖。
     *
     * @param transactionRunner 事务边界，空则直通
     * @param lockProvider      锁，空则不加锁
     * @param lockTtl           锁租约，空则 30 秒
     * @param clock             取时入口
     * @param idGenerator       命令/事件标识生成器
     * @param commandStore      命令记录存储
     * @param temporalStore     对象存储
     * @param outboxWriter      outbox 写入器；不得为空（没有 outbox 时显式使用
     *                          {@link OutboxWriter#discard()}）
     * @param temporalAudit     临时命令审计 seam；不得为空（无审计时显式使用
     *                          {@link TemporalCommandAudit#noop()}）
     * @param jsonMapper        命令解码/outbox 序列化
     * @param pendingLease      PENDING 租约时长
     */
    public record Options(TransactionRunner transactionRunner, LockProvider lockProvider,
                          Duration lockTtl, Clock clock, IdGenerator<UUID> idGenerator,
                          CommandStore commandStore, TemporalStore temporalStore,
                          OutboxWriter outboxWriter, TemporalCommandAudit temporalAudit,
                          JsonMapper jsonMapper, Duration pendingLease) {

        /**
         * 兼容既有装配：默认 JsonMapper 与 60 秒租约。
         */
        public Options(TransactionRunner transactionRunner, LockProvider lockProvider,
                       Duration lockTtl, Clock clock, IdGenerator<UUID> idGenerator,
                       CommandStore commandStore, TemporalStore temporalStore,
                       OutboxWriter outboxWriter, TemporalCommandAudit temporalAudit) {
            this(transactionRunner, lockProvider, lockTtl, clock, idGenerator, commandStore,
                    temporalStore, outboxWriter, temporalAudit, JsonMapper.builder().build(),
                    Duration.ofSeconds(60));
        }

        /**
         * 兼容既有装配：默认 60 秒租约。
         */
        public Options(TransactionRunner transactionRunner, LockProvider lockProvider,
                       Duration lockTtl, Clock clock, IdGenerator<UUID> idGenerator,
                       CommandStore commandStore, TemporalStore temporalStore,
                       OutboxWriter outboxWriter, TemporalCommandAudit temporalAudit,
                       JsonMapper jsonMapper) {
            this(transactionRunner, lockProvider, lockTtl, clock, idGenerator, commandStore,
                    temporalStore, outboxWriter, temporalAudit, jsonMapper,
                    Duration.ofSeconds(60));
        }

        /**
         * 测试装配：直通事务、禁用锁、丢弃 outbox、无审计。
         *
         * @param clock     取时入口
         * @param ids       标识生成器
         * @param commands  命令记录存储
         * @param objects   对象存储
         * @return 测试运行时依赖
         */
        public static Options forTests(Clock clock, IdGenerator<UUID> ids,
                                       CommandStore commands, TemporalStore objects) {
            return new Options(TransactionRunner.direct(), LockProvider.disabled(),
                    Duration.ofSeconds(30), clock, ids, commands, objects,
                    OutboxWriter.discard(), TemporalCommandAudit.noop());
        }

        public Options {
            transactionRunner = transactionRunner == null
                    ? TransactionRunner.direct() : transactionRunner;
            lockProvider = lockProvider == null ? LockProvider.disabled() : lockProvider;
            lockTtl = lockTtl == null ? Duration.ofSeconds(30) : lockTtl;
            Objects.requireNonNull(clock, "clock");
            Objects.requireNonNull(idGenerator, "idGenerator");
            Objects.requireNonNull(commandStore, "commandStore");
            Objects.requireNonNull(temporalStore, "temporalStore");
            Objects.requireNonNull(outboxWriter, "outboxWriter");
            Objects.requireNonNull(temporalAudit, "temporalAudit");
            jsonMapper = jsonMapper == null ? JsonMapper.builder().build() : jsonMapper;
            pendingLease = pendingLease == null ? Duration.ofSeconds(60) : pendingLease;
            if (lockTtl.isZero() || lockTtl.isNegative()) {
                throw new IllegalArgumentException("lockTtl must be positive");
            }
            if (pendingLease.isZero() || pendingLease.isNegative()) {
                throw new IllegalArgumentException("pendingLease must be positive");
            }
        }
    }
}
