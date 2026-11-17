package cn.byteforce.seedj.starter.im;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
import cn.byteforce.seedj.im.ImConversation;
import cn.byteforce.seedj.im.ImConversationKind;
import cn.byteforce.seedj.im.ImMember;
import cn.byteforce.seedj.im.ImMessage;
import cn.byteforce.seedj.im.ImMessageDraft;
import cn.byteforce.seedj.im.ImMessageKind;
import cn.byteforce.seedj.im.spi.ImConversationStore;
import cn.byteforce.seedj.im.spi.ImCursorStore;
import cn.byteforce.seedj.im.spi.ImMessageStore;
import cn.byteforce.seedj.im.spi.ImSequenceAllocator;
import cn.byteforce.seedj.outbox.OutboxRecord;
import cn.byteforce.seedj.outbox.OutboxWriter;
import cn.byteforce.seedj.starter.im.protocol.Cmd;
import cn.byteforce.seedj.starter.im.protocol.body.AckReq;
import cn.byteforce.seedj.starter.im.protocol.body.ChatDeliver;
import cn.byteforce.seedj.starter.im.protocol.body.ChatSendReq;
import cn.byteforce.seedj.starter.im.protocol.body.EditNotify;
import cn.byteforce.seedj.starter.im.protocol.body.EditReq;
import cn.byteforce.seedj.starter.im.protocol.body.OfflinePullReq;
import cn.byteforce.seedj.starter.im.protocol.body.OfflinePullResp;
import cn.byteforce.seedj.starter.im.protocol.body.ReadReceiptReq;
import cn.byteforce.seedj.starter.im.protocol.body.RecallNotify;
import cn.byteforce.seedj.starter.im.protocol.body.RecallReq;
import cn.byteforce.seedj.starter.im.protocol.body.SessionSyncResp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * 消息服务：发送、确认、已读、增量补拉、会话同步。
 *
 * <p>一条消息的写入顺序固定：成员校验 → 取号 → 插消息 → 写 outbox（同事务）→ 提交后推送。 outbox 里只有元数据，正文不进事件总线。
 *
 * <p>推送是尽力而为的：连接不可写就跳过，客户端下次 {@code OFFLINE_PULL} 会补上。序号来自全局序列， 单调但会话内可能不连续。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class ImMessagingService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String EVENT_TYPE = "im.message.sent";

    private static final String EDITED_EVENT = "im.message.edited";

    private static final String RECALLED_EVENT = "im.message.recalled";

    private final ImConversationService conversationService;

    private final ImConversationStore conversations;

    private final ImMessageStore messages;

    private final ImCursorStore cursors;

    private final ImSequenceAllocator numbers;

    private final ImPushProvider push;

    private final ImConversationPolicy policy;

    private final TransactionRunner transaction;

    private final OutboxWriter outbox;

    private final ImProperties properties;

    /**
     * 构造服务。
     *
     * @param conversationService 会话服务
     * @param conversations 会话存储
     * @param messages 消息存储
     * @param cursors 游标存储
     * @param numbers 序号分配器
     * @param push 投递端口
     * @param policy 会话治理策略
     * @param transaction 事务边界
     * @param outbox outbox 写入端口；没有 outbox 时用 {@link OutboxWriter#discard()}
     * @param properties IM 配置
     */
    public ImMessagingService(
            ImConversationService conversationService,
            ImConversationStore conversations,
            ImMessageStore messages,
            ImCursorStore cursors,
            ImSequenceAllocator numbers,
            ImPushProvider push,
            ImConversationPolicy policy,
            TransactionRunner transaction,
            OutboxWriter outbox,
            ImProperties properties) {
        this.conversationService =
                Objects.requireNonNull(conversationService, "conversationService");
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.cursors = Objects.requireNonNull(cursors, "cursors");
        this.numbers = Objects.requireNonNull(numbers, "numbers");
        this.push = Objects.requireNonNull(push, "push");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /**
     * 发送一条消息。
     *
     * @param caller 调用方
     * @param request 发送请求
     * @return 落库后的消息（幂等命中时是既有消息）
     */
    public ImMessage send(Caller caller, ChatSendReq request) {
        return append(caller, request, null);
    }

    /**
     * 在群里发送一条消息。
     *
     * @param caller 调用方
     * @param request 发送请求（必须带 {@code conversation_id}）
     * @return 落库后的消息
     */
    public ImMessage sendGroup(Caller caller, ChatSendReq request) {
        return append(caller, request, ImConversationKind.GROUP);
    }

    /**
     * 撤回一条消息：写 {@code recalled_at}，并向会话成员广播撤回通知。
     *
     * <p>已撤回的消息原样返回，不重复广播——重试撤回是幂等的。
     *
     * @param caller 调用方
     * @param request 撤回请求
     * @return 撤回后的消息
     */
    public ImMessage recall(Caller caller, RecallReq request) {
        if (request == null) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD, "缺少撤回请求体");
        }
        UUID conversationId = ImProtocolIds.conversation(request.conversationId());
        ImConversation conversation =
                conversationService.requireMemberConversation(caller, conversationId);
        ImMessage message = requireMessage(caller, conversation, request.messageId());
        ImMember member =
                conversations
                        .member(conversationId, caller.id())
                        .orElseThrow(
                                () -> new ContractException(ContractErrorCode.FORBIDDEN, "不在该会话中"));
        if (!policy.canRecall(caller, conversation, message, member)) {
            throw new ContractException(ContractErrorCode.FORBIDDEN, "不允许撤回该消息");
        }
        if (message.recalledAt() != null) {
            return message;
        }
        ImMessage recalled =
                transaction.inTransaction(
                        () -> {
                            ImMessage updated = messages.recall(message.id());
                            outbox.append(
                                    event(
                                            RECALLED_EVENT,
                                            UUID.randomUUID().toString(),
                                            conversation,
                                            updated,
                                            caller.id()));
                            return updated;
                        });
        broadcast(
                conversation,
                Cmd.RECALL_REQ,
                new RecallNotify(
                        conversation.id().toString(),
                        recalled.id().toString(),
                        recalled.seq(),
                        caller.id(),
                        recalled.recalledAt().toEpochMilli()));
        return recalled;
    }

    /**
     * 编辑一条消息：只有发送者可以改自己的消息，已撤回的消息不可编辑。
     *
     * @param caller 调用方
     * @param request 编辑请求
     * @return 编辑后的消息
     */
    public ImMessage edit(Caller caller, EditReq request) {
        if (request == null) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD, "缺少编辑请求体");
        }
        UUID conversationId = ImProtocolIds.conversation(request.conversationId());
        ImConversation conversation =
                conversationService.requireMemberConversation(caller, conversationId);
        ImMessage message = requireMessage(caller, conversation, request.messageId());
        if (!message.senderId().equals(caller.id())) {
            throw new ContractException(ContractErrorCode.FORBIDDEN, "只能编辑自己的消息");
        }
        validateBody(message.kind(), request.body());
        ImMessage edited =
                transaction.inTransaction(
                        () -> {
                            ImMessage updated = messages.edit(message.id(), request.body());
                            outbox.append(
                                    event(
                                            EDITED_EVENT,
                                            UUID.randomUUID().toString(),
                                            conversation,
                                            updated,
                                            caller.id()));
                            return updated;
                        });
        broadcast(
                conversation,
                Cmd.EDIT_REQ,
                new EditNotify(
                        conversation.id().toString(),
                        edited.id().toString(),
                        edited.seq(),
                        edited.senderId(),
                        edited.body(),
                        edited.editedAt().toEpochMilli()));
        return edited;
    }

    private ImMessage append(Caller caller, ChatSendReq request, ImConversationKind required) {
        if (request == null) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD, "缺少发送请求体");
        }
        ImConversation conversation = resolveConversation(caller, request, required);
        if (!policy.canSend(caller, conversation)) {
            throw new ContractException(ContractErrorCode.FORBIDDEN, "不允许在该会话发送");
        }
        ImMessageKind kind = parseKind(request.kind());
        Map<String, Object> body = request.body();
        validateBody(kind, body);

        ContractException lastConflict = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            long seq = numbers.next();
            ImMessageDraft draft =
                    new ImMessageDraft(
                            UUID.randomUUID(),
                            caller.tenantId(),
                            conversation.id(),
                            seq,
                            caller.id(),
                            kind,
                            body,
                            request.clientMsgId());
            try {
                ImMessage stored =
                        transaction.inTransaction(
                                () -> {
                                    ImMessage appended = messages.append(draft);
                                    if (appended.id().equals(draft.id())) {
                                        outbox.append(
                                                event(
                                                        EVENT_TYPE,
                                                        appended.id().toString(),
                                                        conversation,
                                                        appended,
                                                        caller.id()));
                                    }
                                    return appended;
                                });
                if (stored.id().equals(draft.id())) {
                    deliver(conversation, stored, caller.id());
                }
                return stored;
            } catch (ContractException conflict) {
                if (conflict.code() != ContractErrorCode.VERSION_CONFLICT) {
                    throw conflict;
                }
                lastConflict = conflict;
            }
        }
        throw lastConflict == null
                ? new ContractException(ContractErrorCode.VERSION_CONFLICT, "序号冲突")
                : lastConflict;
    }

    /**
     * 客户端确认已收到。
     *
     * @param caller 调用方
     * @param deviceId 设备
     * @param request 确认请求
     * @return 生效后的同步游标
     */
    public long ack(Caller caller, String deviceId, AckReq request) {
        if (request == null) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD, "缺少确认请求体");
        }
        UUID conversationId = ImProtocolIds.conversation(request.conversationId());
        conversationService.requireMemberConversation(caller, conversationId);
        return cursors.advanceSync(
                caller.tenantId(), caller.id(), ImProtocolIds.device(deviceId), request.seq());
    }

    /**
     * 上报已读，并向同会话其它成员转发。
     *
     * @param caller 调用方
     * @param request 已读请求
     * @return 生效后的已读序号
     */
    public long read(Caller caller, ReadReceiptReq request) {
        if (request == null) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD, "缺少已读请求体");
        }
        UUID conversationId = ImProtocolIds.conversation(request.conversationId());
        conversationService.requireMemberConversation(caller, conversationId);
        long effective = cursors.advanceRead(conversationId, caller.id(), request.seq());
        for (ImMember member : conversations.members(conversationId)) {
            if (!member.subjectId().equals(caller.id())) {
                push.deliver(
                        member.subjectId(),
                        Cmd.READ_RECEIPT,
                        new ReadReceiptReq(request.conversationId(), effective));
            }
        }
        return effective;
    }

    /**
     * 增量补拉：调用方所属会话里序号大于游标的消息。
     *
     * @param caller 调用方
     * @param request 补拉请求
     * @return 一页消息
     */
    public OfflinePullResp pull(Caller caller, OfflinePullReq request) {
        long cursor = request == null || request.cursor() == null ? 0L : request.cursor();
        int limit = limit(request == null ? null : request.limit());
        List<UUID> ids =
                conversations.conversationsOfSubject(caller.tenantId(), caller.id()).stream()
                        .map(ImConversation::id)
                        .toList();
        List<ImMessage> page = messages.sync(ids, cursor, limit);
        List<ChatDeliver> delivered = new ArrayList<>(page.size());
        for (ImMessage message : page) {
            delivered.add(toDeliver(message));
        }
        long nextCursor = page.isEmpty() ? cursor : page.get(page.size() - 1).seq();
        return new OfflinePullResp(delivered, nextCursor, page.size() == limit);
    }

    /**
     * 会话同步：会话快照 + 已读游标 + 建议补拉游标。
     *
     * @param caller 调用方
     * @param deviceId 设备
     * @param sinceSeq 客户端旧游标
     * @return 同步结果
     */
    public SessionSyncResp sync(Caller caller, String deviceId, Long sinceSeq) {
        String device = ImProtocolIds.device(deviceId);
        List<ImConversation> convs =
                conversations.conversationsOfSubject(caller.tenantId(), caller.id());
        List<SessionSyncResp.ConversationView> views = new ArrayList<>(convs.size());
        List<SessionSyncResp.ReadCursorView> readCursors = new ArrayList<>(convs.size());
        for (ImConversation conversation : convs) {
            long lastSeq = messages.maxSeq(conversation.id());
            long readSeq = cursors.readSeq(conversation.id(), caller.id());
            views.add(view(conversation, lastSeq, readSeq));
            readCursors.add(
                    new SessionSyncResp.ReadCursorView(conversation.id().toString(), readSeq));
        }
        long stored = cursors.syncSeq(caller.tenantId(), caller.id(), device);
        long next = stored > 0 ? stored : (sinceSeq == null ? 0L : sinceSeq);
        return new SessionSyncResp(views, readCursors, next);
    }

    /**
     * 单个会话的视图，供 HTTP 端点复用。
     *
     * @param caller 调用方
     * @param conversation 会话
     * @return 会话视图
     */
    public SessionSyncResp.ConversationView view(Caller caller, ImConversation conversation) {
        return view(
                conversation,
                messages.maxSeq(conversation.id()),
                cursors.readSeq(conversation.id(), caller.id()));
    }

    /**
     * 会话视图，供 HTTP 端点复用。
     *
     * @param caller 调用方
     * @return 会话视图列表
     */
    public List<SessionSyncResp.ConversationView> conversationViews(Caller caller) {
        List<ImConversation> convs =
                conversations.conversationsOfSubject(caller.tenantId(), caller.id());
        List<SessionSyncResp.ConversationView> views = new ArrayList<>(convs.size());
        for (ImConversation conversation : convs) {
            views.add(
                    view(
                            conversation,
                            messages.maxSeq(conversation.id()),
                            cursors.readSeq(conversation.id(), caller.id())));
        }
        return views;
    }

    /**
     * 把消息投影成协议视图。
     *
     * @param message 消息
     * @return 投递视图
     */
    public ChatDeliver toDeliver(ImMessage message) {
        return ImDeliveries.of(message);
    }

    private ImConversation resolveConversation(
            Caller caller, ChatSendReq request, ImConversationKind required) {
        if (request.conversationId() != null && !request.conversationId().isBlank()) {
            ImConversation conversation =
                    conversationService.requireMemberConversation(
                            caller, ImProtocolIds.conversation(request.conversationId()));
            if (required != null && conversation.kind() != required) {
                throw new ContractException(
                        ContractErrorCode.INVALID_PAYLOAD, "该命令只接受 " + required + " 会话");
            }
            return conversation;
        }
        if (required == ImConversationKind.GROUP) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD, "群聊必须给 conversation_id");
        }
        if (request.peerId() == null || request.peerId().isBlank()) {
            throw new ContractException(
                    ContractErrorCode.INVALID_PAYLOAD, "conversation_id 与 peer_id 至少给一个");
        }
        return conversationService.createSingle(caller, request.peerId());
    }

    private void deliver(ImConversation conversation, ImMessage message, String senderId) {
        List<String> recipients = new ArrayList<>();
        for (ImMember member : conversations.members(conversation.id())) {
            if (!member.subjectId().equals(senderId)) {
                recipients.add(member.subjectId());
            }
        }
        if (!recipients.isEmpty()) {
            push.deliverMany(recipients, Cmd.CHAT_DELIVER, toDeliver(message));
        }
    }

    private void validateBody(ImMessageKind kind, Map<String, Object> body) {
        ImAttachments.validate(
                kind,
                body,
                properties.message().maxBodyBytes(),
                properties.message().maxAttachmentBytes(),
                JSON);
    }

    private ImMessage requireMessage(Caller caller, ImConversation conversation, String messageId) {
        UUID id = ImProtocolIds.message(messageId);
        ImMessage message =
                messages.find(id)
                        .orElseThrow(
                                () ->
                                        new ContractException(
                                                ContractErrorCode.OBJECT_NOT_FOUND,
                                                "消息不存在: " + messageId));
        if (!message.conversationId().equals(conversation.id())) {
            throw new ContractException(
                    ContractErrorCode.OBJECT_NOT_FOUND, "消息不存在于该会话: " + messageId);
        }
        if (!message.tenantId().equals(caller.tenantId())) {
            throw new ContractException(ContractErrorCode.FORBIDDEN, "跨租户消息不可访问");
        }
        return message;
    }

    private void broadcast(ImConversation conversation, byte cmd, Object body) {
        List<String> subjects =
                conversations.members(conversation.id()).stream().map(ImMember::subjectId).toList();
        if (!subjects.isEmpty()) {
            push.deliverMany(subjects, cmd, body);
        }
    }

    private int limit(Integer requested) {
        int configured = properties.sync().pageSize();
        if (requested == null || requested <= 0) {
            return configured;
        }
        return Math.min(requested, configured);
    }

    private OutboxRecord event(
            String type,
            String outboxId,
            ImConversation conversation,
            ImMessage message,
            String actorId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message_id", message.id().toString());
        payload.put("conversation_id", conversation.id().toString());
        payload.put("seq", message.seq());
        payload.put("sender_id", message.senderId());
        payload.put("kind", message.kind().name());
        payload.put("actor_id", actorId);
        return OutboxRecord.of(
                outboxId,
                type,
                conversation.id().toString(),
                "application/json",
                JSON.writeValueAsBytes(payload));
    }

    private SessionSyncResp.ConversationView view(
            ImConversation conversation, long lastSeq, long readSeq) {
        long unread = messages.countAfter(conversation.id(), readSeq);
        return new SessionSyncResp.ConversationView(
                conversation.id().toString(),
                conversation.kind().name(),
                conversation.title(),
                conversation.ownerId(),
                lastSeq,
                unread,
                conversation.updatedAt().toEpochMilli());
    }

    private static ImMessageKind parseKind(String kind) {
        if (kind == null || kind.isBlank()) {
            return ImMessageKind.TEXT;
        }
        try {
            return ImMessageKind.valueOf(kind.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD, "未知消息类型: " + kind);
        }
    }

    /**
     * outbox 事件类型；宿主订阅时按这个名字过滤。
     *
     * @return 事件类型
     */
    public static String eventType() {
        return EVENT_TYPE;
    }
}
