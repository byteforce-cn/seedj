package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.cqrs.spi.CommandStore;
import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.AppendResult;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 命令运行时：幂等、锁、事务与终态写入的唯一协调点。
 *
 * <p>顺序是契约的一部分：</p>
 * <ol>
 *   <li>按幂等键回读；命中则回放/冲突，不进入锁与事务；</li>
 *   <li>写入 {@code PENDING} 命令记录；</li>
 *   <li>业务校验（失败 → 事务外补写拒绝终态）；</li>
 *   <li>获取对象锁；</li>
 *   <li>事务内：读当前行 → 追加 → 事件写 outbox → 命令终态；</li>
 *   <li>事务失败：回滚对象/事件，事务外补写拒绝终态；技术故障保持
 *       {@code PENDING}，由调用方按可重试处理。</li>
 * </ol>
 *
 * <p>{@link Work} 由命令代理实现；本类不解释业务载荷。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class CommandRuntime {

    private static final JsonMapper JSON = JsonMapper.builder().build();

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
     * @param submission 命令提交
     * @param work       命令工作单元
     * @return 受理结果
     */
    public CommandReceipt execute(CommandSubmission submission, Work work) {
        Objects.requireNonNull(submission, "submission");
        Objects.requireNonNull(work, "work");

        String objectType = requireObjectType(work.objectType());
        UUID targetId = requireTargetId(work);
        String fingerprint = IdempotencyFingerprint.of(submission);

        Optional<CommandRecord> existing = options.commandStore()
                .findByIdempotencyKey(submission.idempotencyKey());
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }

        Instant now = options.clock().now();
        CommandRecord pending = CommandRecord.pending(options.idGenerator().id(), submission.type(),
                now, submission.issuer(), submission.payload(), submission.idempotencyKey(),
                fingerprint, now);
        if (!options.commandStore().insert(pending)) {
            CommandRecord winner = options.commandStore()
                    .findByIdempotencyKey(submission.idempotencyKey())
                    .orElseThrow(() -> new IllegalStateException(
                            "idempotency key conflicted but no record is readable: "
                                    + submission.idempotencyKey()));
            return replayOrConflict(winner, fingerprint);
        }

        try {
            work.validate();
        } catch (ContractException error) {
            throw reject(pending, error);
        } catch (IllegalArgumentException error) {
            throw reject(pending, invalidPayload(error));
        }

        String lockKey = objectType + ':' + targetId;
        try (LockLease ignored = options.lockProvider().acquire(lockKey, options.lockTtl())) {
            try {
                CommandRecord accepted = options.transactionRunner().inTransaction(() -> {
                    Optional<TemporalRow> current = options.temporalStore()
                            .current(objectType, targetId);
                    Instant appendNow = options.clock().now();
                    AppendRequest request = append(work, current, appendNow, submission.issuer());
                    AppendResult result = options.temporalStore().append(request);
                    List<CommandEvent> events = work.events(result, appendNow,
                            submission.issuer());
                    List<UUID> eventIds = writeEvents(events);
                    CommandRecord record = pending.accepted(result.appended().version().id(),
                            result.appended().version().version(), eventIds);
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

    private AppendRequest append(Work work, Optional<TemporalRow> current, Instant now,
                                 UUID actor) {
        try {
            AppendRequest request = work.append(current, now, actor);
            if (request == null) {
                throw new IllegalStateException("command work returned a null append request");
            }
            return request;
        } catch (IllegalArgumentException error) {
            throw invalidPayload(error);
        }
    }

    private List<UUID> writeEvents(List<CommandEvent> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        List<UUID> eventIds = new ArrayList<>(events.size());
        for (CommandEvent event : events) {
            writeOutbox(event);
            eventIds.add(event.id());
        }
        return List.copyOf(eventIds);
    }

    private void writeOutbox(CommandEvent event) {
        if (options.outboxWriter() == null) {
            return;
        }
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
                "eis://core/event/v1", event.version(), JSON.writeValueAsBytes(body),
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

    private static UUID requireTargetId(Work work) {
        UUID targetId;
        try {
            targetId = work.targetId();
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

    /**
     * 命令工作单元：命令代理把类型化命令适配成对象追加与事件。
     */
    public interface Work {

        /**
         * 对象类型（对象表注册名）。
         *
         * @return 对象类型
         */
        String objectType();

        /**
         * 目标对象 ID。
         *
         * @return 目标 ID
         */
        UUID targetId();

        /**
         * 进入锁与事务前的业务校验。
         */
        void validate();

        /**
         * 生成追加请求。
         *
         * @param current 当前行；创建时为空
         * @param now     本次追加的系统时间
         * @param actor   操作者
         * @return 追加请求
         */
        AppendRequest append(Optional<TemporalRow> current, Instant now, UUID actor);

        /**
         * 本次命令产生的事件。
         *
         * @param result 追加结果
         * @param now    追加使用的系统时间
         * @param actor  操作者
         * @return 事件列表，默认空
         */
        default List<CommandEvent> events(AppendResult result, Instant now, UUID actor) {
            return List.of();
        }
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
     * @param outboxWriter      outbox 写入器，可为 {@code null}（不投递事件）
     */
    public record Options(TransactionRunner transactionRunner, LockProvider lockProvider,
                          Duration lockTtl, Clock clock, IdGenerator<UUID> idGenerator,
                          CommandStore commandStore, TemporalStore temporalStore,
                          OutboxWriter outboxWriter) {

        public Options {
            transactionRunner = transactionRunner == null
                    ? TransactionRunner.direct() : transactionRunner;
            lockProvider = lockProvider == null ? LockProvider.disabled() : lockProvider;
            lockTtl = lockTtl == null ? Duration.ofSeconds(30) : lockTtl;
            Objects.requireNonNull(clock, "clock");
            Objects.requireNonNull(idGenerator, "idGenerator");
            Objects.requireNonNull(commandStore, "commandStore");
            Objects.requireNonNull(temporalStore, "temporalStore");
            if (lockTtl.isZero() || lockTtl.isNegative()) {
                throw new IllegalArgumentException("lockTtl must be positive");
            }
        }
    }
}
