package cn.byteforce.seedj.starter.eis.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * EIS 命令运行时：写路径的唯一入口（幂等 → 授权钩子 → 规则链 → 处理器 → 事件 →
 * 审计 → 封口，全部在同一 PostgreSQL 事务内）。
 *
 * <p><b>语义与参考内核逐项对齐</b>（{@code eis/tools/lib/kernel.py}）：</p>
 * <ul>
 *   <li>幂等：同键同内容 → 返回原终态（replay）；同键不同内容 → {@code EIS-409-001}
 *       冲突（返回首次命令 ID）；指纹 = SHA-256（{@link CanonicalJson#pythonDumps}）；</li>
 *   <li>拒绝命令以 {@code rejected} 落库（原因 + 错误码），不写事件、不入审计链
 *       （核心 04：受理才入链）；</li>
 *   <li>接受命令：事件（occurred_at = recorded_at = 命令时刻）、审计（动作 = 命令类型，
 *       资源 = 首事件主体域，载荷 = {@code {command_id, event_ids}}）、outbox 同事务写入；</li>
 *   <li>审计序列由锚行串行分配（见 {@code starter-eis-audit}）；审计是必配能力，
 *       缺失时运行时不装配。</li>
 * </ul>
 *
 * <p><b>提交后动作</b>：outbox 记录经 {@link EisOutboxDispatcher} 分发（默认进程内、
 * fail-open），完整事件再经 {@link EisNotifyHook} 通知（缓存失效/唤醒脉冲语义）。</p>
 *
 * <p><b>授权缺省 fail-closed</b>：自动配置保证传入的 {@link EisAuthorizer} 为非空的有效决策器——
 * 应用未注册授权器且未显式开启 {@code byteforce.eis.core.allow-all-authorizer} 时应用启动失败；
 * 构造函数保留可空签名（{@code null} 等同全部放行）仅供内部与单元测试直接构造使用，
 * 生产路径必须由自动配置完成上述 fail-closed 判定。</p>
 *
 * <p><b>为什么是 final</b>：构造期对处理器重复注册做 fail-fast 校验（构造中抛异常），
 * final 化使其不可被继承，消除 Finalizer 攻击面（SpotBugs CT_CONSTRUCTOR_THROW）；
 * 应用通过覆盖 {@code EisCommandRuntime} Bean 定制，而非继承本类。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class EisCommandRuntime {

    private static final Logger log = LoggerFactory.getLogger(EisCommandRuntime.class);

    private static final Pattern COMMAND_TYPE = Pattern.compile("^[a-z][a-z0-9_.]*$");

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final EisClock clock;

    private final EisObjectTypeRegistry objectTypes;

    private final EisObjectRepository objects;

    private final EisCommandStore commands;

    private final EisEventStore events;

    private final EisAuditAppender audit;

    private final EisAuthorizer authorizer;

    private final Map<String, EisCommandHandler> handlers;

    private final List<EisRule> rules;

    private final EisOutboxDispatcher dispatcher;

    private final EisNotifyHook notifyHook;

    private final TransactionTemplate transactionTemplate;

    /**
     * 构造运行时。
     *
     * @param clock         EIS 时钟
     * @param objectTypes   对象类型注册表
     * @param objects       对象存储
     * @param commands      命令存储
     * @param events        事件与 outbox 存储
     * @param audit         审计追加器（必配）
     * @param authorizer    授权决策器；{@code null} 等同全部放行（仅内部/测试直接构造时使用，
     *                      生产路径由自动配置保证非空，见类注释）
     * @param handlerList   命令处理器集合
     * @param ruleList      规则集合（按注入顺序执行）
     * @param dispatcher    outbox 分发器
     * @param notifyHook    失效/唤醒钩子
     * @param transactionManager 事务管理器（阶段边界 = 单 PG 事务）
     */
    public EisCommandRuntime(EisClock clock, EisObjectTypeRegistry objectTypes, EisObjectRepository objects,
                             EisCommandStore commands, EisEventStore events, EisAuditAppender audit,
                             EisAuthorizer authorizer, List<EisCommandHandler> handlerList,
                             List<EisRule> ruleList, EisOutboxDispatcher dispatcher, EisNotifyHook notifyHook,
                             PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.objectTypes = objectTypes;
        this.objects = objects;
        this.commands = commands;
        this.events = events;
        this.audit = audit;
        this.authorizer = authorizer;
        this.rules = ruleList == null ? List.of() : List.copyOf(ruleList);
        this.dispatcher = dispatcher;
        this.notifyHook = notifyHook;
        Map<String, EisCommandHandler> byType = new LinkedHashMap<>();
        if (handlerList != null) {
            for (EisCommandHandler handler : handlerList) {
                for (String type : handler.commandTypes()) {
                    if (byType.put(type, handler) != null) {
                        throw new IllegalStateException("命令类型重复注册: " + type);
                    }
                }
            }
        }
        this.handlers = Map.copyOf(byType);
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 提交命令。
     *
     * @param request 命令请求
     * @return 受理结果（accepted / rejected / conflict）
     * @throws EisRequestException 报文级错误（EIS-400-001 等）
     */
    public EisCommandResult submit(EisCommandRequest request) {
        validate(request);
        String fingerprint = computeFingerprint(request);
        Optional<EisCommandStore.EisIdempotencyRecord> existing =
                commands.findIdempotency(request.getIdempotencyKey());
        if (existing.isPresent()) {
            return resolveIdempotent(existing.get(), fingerprint);
        }
        EisCommandResult result = transactionTemplate.execute(status -> doSubmit(request, fingerprint));
        if (result == null) {
            throw new IllegalStateException("事务模板未返回受理结果");
        }
        return result;
    }

    /**
     * 查询命令终态（GET /commands/{id}）。
     *
     * @param commandId 命令 ID
     * @return 命令记录；不存在为空
     */
    public Optional<EisCommandStore.EisStoredCommand> findCommand(UUID commandId) {
        return commands.findCommand(commandId);
    }

    private EisCommandResult doSubmit(EisCommandRequest request, String fingerprint) {
        UUID commandId = UuidV7.generate(clock);
        Instant now = clock.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        EisCommandHandler handler = handlers.get(request.getCommandType());
        JsonNode storedPayload = handler == null ? request.getPayload() : handler.storedPayload(request.getPayload());

        boolean reserved = commands.insertIdempotencyIfAbsent(request.getIdempotencyKey(), fingerprint, commandId);
        if (!reserved) {
            Optional<EisCommandStore.EisIdempotencyRecord> winner =
                    commands.findIdempotency(request.getIdempotencyKey());
            if (winner.isEmpty()) {
                throw new IllegalStateException("幂等占位冲突但既有记录不可见: " + request.getIdempotencyKey());
            }
            return resolveIdempotent(winner.get(), fingerprint);
        }
        commands.insertCommand(commandId, request.getCommandType(), now, request.getActor(), request.getTargets(),
                storedPayload, request.getIdempotencyKey());

        if (handler == null) {
            return reject(commandId, "EIS-400-001", "未知命令类型: " + request.getCommandType(), null);
        }
        // authorizer 可空仅兼容内部/测试直接构造；自动配置路径已 fail-closed 保证非空
        if (authorizer != null) {
            EisAuthorizer.EisAuthorizationDecision decision = authorizer.check(
                    new EisAuthorizer.EisAuthorizationRequest(request.getActor(), request.getCommandType(), now));
            if (!decision.isAllowed()) {
                return reject(commandId, "EIS-403-001",
                        decision.getReason() == null ? "授权拒绝" : decision.getReason(), decision.getReasonCode());
            }
        }
        EisCommandContext context = new EisCommandContext(commandId, request.getCommandType(), request.getActor(),
                request.getPayload(), now, clock, objects, objectTypes);
        try {
            for (EisRule rule : rules) {
                if (rule.commandTypes().contains(request.getCommandType())) {
                    rule.enforce(context);
                }
            }
        } catch (EisCommandRejectedException rejected) {
            return reject(commandId, rejected.getCode(), rejected.getMessage(), rejected.getReasonCode(),
                    rejected.getDetails());
        }

        EisCommandHandlerResult handled;
        try {
            handled = handler.handle(context);
        } catch (EisCommandRejectedException rejected) {
            return reject(commandId, rejected.getCode(), rejected.getMessage(), rejected.getReasonCode(),
                    rejected.getDetails());
        }

        List<EisEvent> committed = materialize(handled.getEvents(), request.getActor(), now);
        for (EisEvent event : committed) {
            events.insert(event);
        }
        List<UUID> eventIds = committed.stream().map(EisEvent::getId).toList();
        commands.markAccepted(commandId, eventIds, handled.getCreated());

        audit.append(new EisAuditAppender.EisAuditAppendRequest(request.getCommandType(), request.getActor(),
                auditResourceType(committed), auditResourceId(committed, commandId),
                auditPayload(commandId, eventIds)));

        List<EisOutboxDispatcher.EisOutboxEntry> outboxEntries = new ArrayList<>();
        for (EisEvent event : committed) {
            EisOutboxDispatcher.EisOutboxEntry entry = new EisOutboxDispatcher.EisOutboxEntry(
                    UuidV7.generate(clock), event.getId(), topicOf(event.getType()), event.getPayload(), now);
            events.insertOutbox(entry);
            outboxEntries.add(entry);
        }

        registerAfterCommit(outboxEntries, committed);
        return EisCommandResult.accepted(commandId, eventIds, handled.getCreated());
    }

    private EisCommandResult reject(UUID commandId, String code, String reason, String reasonCode) {
        return reject(commandId, code, reason, reasonCode, List.of());
    }

    private EisCommandResult reject(UUID commandId, String code, String reason, String reasonCode,
                                    List<EisCommandRejectionDetail> details) {
        commands.markRejected(commandId, code, reason);
        return EisCommandResult.rejected(commandId, code, reason, reasonCode, details);
    }

    private EisCommandResult resolveIdempotent(EisCommandStore.EisIdempotencyRecord record, String fingerprint) {
        if (!record.getFingerprint().equals(fingerprint)) {
            return EisCommandResult.conflict(record.getCommandId());
        }
        EisCommandStore.EisStoredCommand command = commands.findCommand(record.getCommandId())
                .orElseThrow(() -> new IllegalStateException("幂等记录指向缺失命令: " + record.getCommandId()));
        if (EisCommandStatus.ACCEPTED.getCode().equals(command.getStatus())) {
            return EisCommandResult.replay(EisCommandStatus.ACCEPTED.getCode(), command.getId(),
                    command.getEventIds(), null, null, null, command.getCreated());
        }
        return EisCommandResult.replay(EisCommandStatus.REJECTED.getCode(), command.getId(), List.of(),
                command.getRejectionCode(), command.getRejectionReason(), null);
    }

    private List<EisEvent> materialize(List<EisEventDraft> drafts, UUID actor, Instant now) {
        List<EisEvent> materialized = new ArrayList<>();
        for (EisEventDraft draft : drafts) {
            UUID eventId = UuidV7.generate(clock);
            materialized.add(new EisEvent(eventId, draft.getType(), now, now, actor, draft.getSubjects(),
                    draft.getPayload() == null ? MAPPER.createObjectNode() : draft.getPayload(),
                    eventId + ":rule:0", 1));
        }
        return materialized;
    }

    private void registerAfterCommit(List<EisOutboxDispatcher.EisOutboxEntry> outboxEntries,
                                     List<EisEvent> committed) {
        if (outboxEntries.isEmpty() && committed.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (EisOutboxDispatcher.EisOutboxEntry entry : outboxEntries) {
                    try {
                        dispatcher.dispatch(entry);
                        events.markDispatched(entry.getId(), clock.now());
                    } catch (RuntimeException ex) {
                        log.warn("outbox 分发失败（fail-open，记录保留待重放）: id={}, topic={}",
                                entry.getId(), entry.getTopic(), ex);
                    }
                }
                if (!committed.isEmpty()) {
                    try {
                        notifyHook.onEvents(committed);
                    } catch (RuntimeException ex) {
                        log.warn("失效/唤醒钩子异常（fail-open）: {}", ex.getMessage());
                    }
                }
            }
        });
    }

    /**
     * 幂等指纹：SHA-256（pythonDumps 规范化的 {@code {payload, targets, type}}）。
     *
     * @param request 命令请求
     * @return 16 进制指纹
     */
    static String computeFingerprint(EisCommandRequest request) {
        ObjectNode root = MAPPER.createObjectNode();
        root.set("payload", request.getPayload());
        ArrayNode targets = root.putArray("targets");
        for (UUID target : request.getTargets()) {
            targets.add(target.toString());
        }
        root.put("type", request.getCommandType());
        return EisHashes.sha256Hex(CanonicalJson.pythonDumps(root));
    }

    private static String auditResourceType(List<EisEvent> committed) {
        if (!committed.isEmpty() && !committed.get(0).getSubjects().isEmpty()) {
            String firstType = committed.get(0).getType();
            int dot = firstType.indexOf('.');
            return dot > 0 ? firstType.substring(0, dot) : firstType;
        }
        return "command";
    }

    private static UUID auditResourceId(List<EisEvent> committed, UUID commandId) {
        if (!committed.isEmpty() && !committed.get(0).getSubjects().isEmpty()) {
            return committed.get(0).getSubjects().get(0);
        }
        return commandId;
    }

    private JsonNode auditPayload(UUID commandId, List<UUID> eventIds) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("command_id", commandId.toString());
        ArrayNode ids = payload.putArray("event_ids");
        for (UUID eventId : eventIds) {
            ids.add(eventId.toString());
        }
        return payload;
    }

    private static String topicOf(String eventType) {
        int dot = eventType.indexOf('.');
        return (dot > 0 ? eventType.substring(0, dot) : eventType) + ".events";
    }

    private static void validate(EisCommandRequest request) {
        if (request == null) {
            throw EisRequestException.invalidPayload("命令请求为空");
        }
        if (request.getCommandType() == null || !COMMAND_TYPE.matcher(request.getCommandType()).matches()) {
            throw EisRequestException.invalidPayload("命令类型非法: " + request.getCommandType());
        }
        if (request.getPayload() == null || !request.getPayload().isObject()) {
            throw EisRequestException.invalidPayload("payload 必须为 JSON 对象");
        }
        if (request.getIdempotencyKey() == null || request.getIdempotencyKey().isBlank()) {
            throw EisRequestException.invalidPayload("缺少 idempotency_key");
        }
        if (request.getActor() == null) {
            throw EisRequestException.unauthenticated();
        }
    }
}
