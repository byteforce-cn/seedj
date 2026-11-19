package cn.byteforce.seedj.starter.im;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
import cn.byteforce.seedj.im.ImConversation;
import cn.byteforce.seedj.im.ImConversationKind;
import cn.byteforce.seedj.im.ImMemberRole;
import cn.byteforce.seedj.im.ImMessage;
import cn.byteforce.seedj.im.ImMessageDraft;
import cn.byteforce.seedj.im.ImMessageKind;
import cn.byteforce.seedj.im.spi.ImConversationStore;
import cn.byteforce.seedj.im.spi.ImMessageStore;
import cn.byteforce.seedj.im.spi.ImSequenceAllocator;
import cn.byteforce.seedj.outbox.OutboxRecord;
import cn.byteforce.seedj.outbox.OutboxWriter;
import cn.byteforce.seedj.starter.im.protocol.Cmd;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * 系统通知发送入口：给单个用户发系统卡片或文本。
 *
 * <p>系统单聊直接走存储，不经业务会话策略（审批通知不能被「谁可以和谁建会话」拦截）；{@code clientMsgId=card_id} 让同一张卡重复发送幂等。卡片体不合法时降级为
 * {@code TEXT}，不丢消息；发送失败只 WARN，由审批 HTTP 端点兜底。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class ImSystemNotifier {

    private static final Logger LOG = LoggerFactory.getLogger(ImSystemNotifier.class);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String EVENT_TYPE = "im.message.sent";

    private final ImConversationStore conversations;

    private final ImMessageStore messages;

    private final ImSequenceAllocator numbers;

    private final ImPushProvider push;

    private final TransactionRunner transaction;

    private final OutboxWriter outbox;

    private final Clock clock;

    private final ImSystemProperties system;

    private final ImProperties properties;

    public ImSystemNotifier(
            ImConversationStore conversations,
            ImMessageStore messages,
            ImSequenceAllocator numbers,
            ImPushProvider push,
            TransactionRunner transaction,
            OutboxWriter outbox,
            Clock clock,
            ImSystemProperties system,
            ImProperties properties) {
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.numbers = Objects.requireNonNull(numbers, "numbers");
        this.push = Objects.requireNonNull(push, "push");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.system = Objects.requireNonNull(system, "system");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /**
     * 发送系统卡片；卡片体不合法时自动降级为文本。
     *
     * @param tenantId 租户
     * @param recipientId 收件人
     * @param card 卡片
     * @return 落库消息
     */
    public ImMessage sendCard(String tenantId, String recipientId, CardMessage card) {
        Objects.requireNonNull(card, "card");
        Map<String, Object> body;
        try {
            body = card.toBody();
            ImAttachments.validate(
                    ImMessageKind.CARD,
                    body,
                    properties.message().maxBodyBytes(),
                    properties.message().maxAttachmentBytes(),
                    JSON);
        } catch (IllegalArgumentException | ContractException invalid) {
            LOG.warn("卡片体不合法，降级为文本: {}", invalid.getMessage());
            return sendText(tenantId, recipientId, card.fallbackText());
        }
        return append(tenantId, recipientId, ImMessageKind.CARD, body, card.cardId());
    }

    /**
     * 发送系统文本。
     *
     * @param tenantId 租户
     * @param recipientId 收件人
     * @param text 文本
     * @return 落库消息
     */
    public ImMessage sendText(String tenantId, String recipientId, String text) {
        if (text == null || text.isBlank()) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD, "系统文本不能为空");
        }
        Map<String, Object> body = Map.of("text", text);
        ImAttachments.validate(
                ImMessageKind.TEXT,
                body,
                properties.message().maxBodyBytes(),
                properties.message().maxAttachmentBytes(),
                JSON);
        return append(tenantId, recipientId, ImMessageKind.TEXT, body, null);
    }

    private ImMessage append(
            String tenantId,
            String recipientId,
            ImMessageKind kind,
            Map<String, Object> body,
            String clientMsgId) {
        ImConversation conversation = systemConversation(tenantId, recipientId);
        long seq = numbers.next();
        ImMessageDraft draft =
                new ImMessageDraft(
                        UUID.randomUUID(),
                        tenantId,
                        conversation.id(),
                        seq,
                        system.subject(),
                        kind,
                        body,
                        clientMsgId);
        ImMessage stored =
                transaction.inTransaction(
                        () -> {
                            ImMessage appended = messages.append(draft);
                            if (appended.id().equals(draft.id())) {
                                outbox.append(event(conversation, appended));
                            }
                            return appended;
                        });
        if (stored.id().equals(draft.id())) {
            push.deliverMany(List.of(recipientId), Cmd.CHAT_DELIVER, ImDeliveries.of(stored));
        }
        return stored;
    }

    private ImConversation systemConversation(String tenantId, String recipientId) {
        String recipient = required(recipientId, "recipientId");
        String directKey = ImConversation.directKey(system.subject(), recipient);
        Optional<ImConversation> existing = conversations.findByDirectKey(tenantId, directKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            ImConversation created =
                    conversations.create(
                            new ImConversation(
                                    UUID.randomUUID(),
                                    tenantId,
                                    ImConversationKind.SINGLE,
                                    null,
                                    system.subject(),
                                    directKey,
                                    clock.now(),
                                    null));
            conversations.addMember(
                    created.id(), system.subject(), ImMemberRole.OWNER, system.subject());
            conversations.addMember(created.id(), recipient, ImMemberRole.MEMBER, system.subject());
            return conversations.find(created.id()).orElse(created);
        } catch (ContractException conflict) {
            // 并发创建：唯一约束兜底，重查一次。
            return conversations.findByDirectKey(tenantId, directKey).orElseThrow(() -> conflict);
        }
    }

    private OutboxRecord event(ImConversation conversation, ImMessage message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message_id", message.id().toString());
        payload.put("conversation_id", conversation.id().toString());
        payload.put("seq", message.seq());
        payload.put("sender_id", message.senderId());
        payload.put("kind", message.kind().name());
        payload.put("actor_id", system.subject());
        return OutboxRecord.of(
                message.id().toString(),
                EVENT_TYPE,
                conversation.id().toString(),
                "application/json",
                JSON.writeValueAsBytes(payload));
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD, name + " 不能为空");
        }
        return value;
    }
}
