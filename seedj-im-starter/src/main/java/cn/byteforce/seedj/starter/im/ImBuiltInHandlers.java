package cn.byteforce.seedj.starter.im;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.domain.Page;
import cn.byteforce.seedj.domain.PageRequest;
import cn.byteforce.seedj.im.ImConversation;
import cn.byteforce.seedj.im.ImMessage;
import cn.byteforce.seedj.starter.im.protocol.Cmd;
import cn.byteforce.seedj.starter.im.protocol.body.AckReq;
import cn.byteforce.seedj.starter.im.protocol.body.ChatSendReq;
import cn.byteforce.seedj.starter.im.protocol.body.ChatSendResp;
import cn.byteforce.seedj.starter.im.protocol.body.ConversationListReq;
import cn.byteforce.seedj.starter.im.protocol.body.ConversationListResp;
import cn.byteforce.seedj.starter.im.protocol.body.EditReq;
import cn.byteforce.seedj.starter.im.protocol.body.MemberListReq;
import cn.byteforce.seedj.starter.im.protocol.body.MemberListResp;
import cn.byteforce.seedj.starter.im.protocol.body.OfflinePullReq;
import cn.byteforce.seedj.starter.im.protocol.body.PresenceReq;
import cn.byteforce.seedj.starter.im.protocol.body.ReadReceiptReq;
import cn.byteforce.seedj.starter.im.protocol.body.RecallReq;
import cn.byteforce.seedj.starter.im.protocol.body.SessionSyncReq;
import cn.byteforce.seedj.starter.im.protocol.body.SessionSyncResp;
import cn.byteforce.seedj.starter.im.protocol.body.TypingReq;
import java.util.List;
import java.util.UUID;

/**
 * 内置命令字处理器：单聊发送、确认、已读、补拉、会话同步。
 *
 * <p>放在一个类里是因为它们共享同一个服务与同一条错误语义，拆成五个文件只会让「加一个命令字 要改几处」变多。
 *
 * @author ByteForce
 * @since 1.0.0
 */
final class ImBuiltInHandlers {

    private ImBuiltInHandlers() {}

    /** 单聊发送。 */
    static final class Send implements ImCommandHandler<ChatSendReq> {

        private final ImMessagingService messaging;

        Send(ImMessagingService messaging) {
            this.messaging = messaging;
        }

        @Override
        public byte cmd() {
            return Cmd.CHAT_SINGLE_REQ;
        }

        @Override
        public Class<ChatSendReq> bodyType() {
            return ChatSendReq.class;
        }

        @Override
        public ImReply handle(ImCallContext context, ChatSendReq body) {
            if (body == null) {
                throw new ContractException(ContractErrorCode.INVALID_PAYLOAD, "缺少发送请求体");
            }
            ImMessage message = messaging.send(context.caller(), body);
            return ImReply.of(
                    Cmd.CHAT_SINGLE_RESP,
                    new ChatSendResp(
                            message.id().toString(),
                            message.conversationId().toString(),
                            message.seq(),
                            message.createdAt().toEpochMilli()));
        }
    }

    /** 群聊发送。 */
    static final class SendGroup implements ImCommandHandler<ChatSendReq> {

        private final ImMessagingService messaging;

        SendGroup(ImMessagingService messaging) {
            this.messaging = messaging;
        }

        @Override
        public byte cmd() {
            return Cmd.CHAT_GROUP_REQ;
        }

        @Override
        public Class<ChatSendReq> bodyType() {
            return ChatSendReq.class;
        }

        @Override
        public ImReply handle(ImCallContext context, ChatSendReq body) {
            if (body == null) {
                throw new ContractException(ContractErrorCode.INVALID_PAYLOAD, "缺少发送请求体");
            }
            ImMessage message = messaging.sendGroup(context.caller(), body);
            return ImReply.of(
                    Cmd.CHAT_GROUP_RESP,
                    new ChatSendResp(
                            message.id().toString(),
                            message.conversationId().toString(),
                            message.seq(),
                            message.createdAt().toEpochMilli()));
        }
    }

    /** 客户端确认。 */
    static final class Ack implements ImCommandHandler<AckReq> {

        private final ImMessagingService messaging;

        Ack(ImMessagingService messaging) {
            this.messaging = messaging;
        }

        @Override
        public byte cmd() {
            return Cmd.ACK_REQ;
        }

        @Override
        public Class<AckReq> bodyType() {
            return AckReq.class;
        }

        @Override
        public ImReply handle(ImCallContext context, AckReq body) {
            messaging.ack(context.caller(), context.deviceId(), body);
            return null;
        }
    }

    /** 已读回执。 */
    static final class Read implements ImCommandHandler<ReadReceiptReq> {

        private final ImMessagingService messaging;

        Read(ImMessagingService messaging) {
            this.messaging = messaging;
        }

        @Override
        public byte cmd() {
            return Cmd.READ_RECEIPT;
        }

        @Override
        public Class<ReadReceiptReq> bodyType() {
            return ReadReceiptReq.class;
        }

        @Override
        public ImReply handle(ImCallContext context, ReadReceiptReq body) {
            messaging.read(context.caller(), body);
            return null;
        }
    }

    /** 增量补拉。 */
    static final class Pull implements ImCommandHandler<OfflinePullReq> {

        private final ImMessagingService messaging;

        Pull(ImMessagingService messaging) {
            this.messaging = messaging;
        }

        @Override
        public byte cmd() {
            return Cmd.OFFLINE_PULL_REQ;
        }

        @Override
        public Class<OfflinePullReq> bodyType() {
            return OfflinePullReq.class;
        }

        @Override
        public ImReply handle(ImCallContext context, OfflinePullReq body) {
            return ImReply.of(Cmd.OFFLINE_PULL_RESP, messaging.pull(context.caller(), body));
        }
    }

    /** 会话同步。 */
    static final class Sync implements ImCommandHandler<SessionSyncReq> {

        private final ImMessagingService messaging;

        Sync(ImMessagingService messaging) {
            this.messaging = messaging;
        }

        @Override
        public byte cmd() {
            return Cmd.SESSION_SYNC_REQ;
        }

        @Override
        public Class<SessionSyncReq> bodyType() {
            return SessionSyncReq.class;
        }

        @Override
        public ImReply handle(ImCallContext context, SessionSyncReq body) {
            return ImReply.of(
                    Cmd.SESSION_SYNC_RESP,
                    messaging.sync(
                            context.caller(),
                            context.deviceId(),
                            body == null ? null : body.sinceSeq()));
        }
    }

    /** 撤回。 */
    static final class Recall implements ImCommandHandler<RecallReq> {

        private final ImMessagingService messaging;

        Recall(ImMessagingService messaging) {
            this.messaging = messaging;
        }

        @Override
        public byte cmd() {
            return Cmd.RECALL_REQ;
        }

        @Override
        public Class<RecallReq> bodyType() {
            return RecallReq.class;
        }

        @Override
        public ImReply handle(ImCallContext context, RecallReq body) {
            ImMessage message = messaging.recall(context.caller(), body);
            return ImReply.of(Cmd.RECALL_RESP, sent(message));
        }
    }

    /** 编辑。 */
    static final class Edit implements ImCommandHandler<EditReq> {

        private final ImMessagingService messaging;

        Edit(ImMessagingService messaging) {
            this.messaging = messaging;
        }

        @Override
        public byte cmd() {
            return Cmd.EDIT_REQ;
        }

        @Override
        public Class<EditReq> bodyType() {
            return EditReq.class;
        }

        @Override
        public ImReply handle(ImCallContext context, EditReq body) {
            ImMessage message = messaging.edit(context.caller(), body);
            return ImReply.of(Cmd.EDIT_RESP, sent(message));
        }
    }

    private static ChatSendResp sent(ImMessage message) {
        return new ChatSendResp(
                message.id().toString(),
                message.conversationId().toString(),
                message.seq(),
                message.createdAt().toEpochMilli());
    }

    /** 会话列表。 */
    static final class Conversations implements ImCommandHandler<ConversationListReq> {

        private final ImConversationService conversations;

        private final ImMessagingService messaging;

        Conversations(ImConversationService conversations, ImMessagingService messaging) {
            this.conversations = conversations;
            this.messaging = messaging;
        }

        @Override
        public byte cmd() {
            return Cmd.CONVERSATION_LIST_REQ;
        }

        @Override
        public Class<ConversationListReq> bodyType() {
            return ConversationListReq.class;
        }

        @Override
        public ImReply handle(ImCallContext context, ConversationListReq body) {
            int page = body == null || body.page() == null ? 1 : body.page();
            int size = body == null || body.size() == null ? 20 : body.size();
            Page<ImConversation> result =
                    conversations.list(context.caller(), new PageRequest(page, size));
            List<SessionSyncResp.ConversationView> views =
                    result.items().stream()
                            .map(conversation -> messaging.view(context.caller(), conversation))
                            .toList();
            return ImReply.of(
                    Cmd.CONVERSATION_LIST_RESP,
                    new ConversationListResp(views, page, size, result.total()));
        }
    }

    /** 成员列表。 */
    static final class Members implements ImCommandHandler<MemberListReq> {

        private final ImConversationService conversations;

        private final ImDisplayNameResolver names;

        Members(ImConversationService conversations, ImDisplayNameResolver names) {
            this.conversations = conversations;
            this.names = names;
        }

        @Override
        public byte cmd() {
            return Cmd.MEMBER_LIST_REQ;
        }

        @Override
        public Class<MemberListReq> bodyType() {
            return MemberListReq.class;
        }

        @Override
        public ImReply handle(ImCallContext context, MemberListReq body) {
            if (body == null) {
                throw new ContractException(ContractErrorCode.INVALID_PAYLOAD, "缺少成员列表请求体");
            }
            UUID conversationId = ImProtocolIds.conversation(body.conversationId());
            List<MemberListResp.MemberView> views =
                    conversations.members(context.caller(), conversationId, body.atSeq()).stream()
                            .map(
                                    member ->
                                            new MemberListResp.MemberView(
                                                    member.subjectId(),
                                                    member.role().name(),
                                                    member.joinedSeq(),
                                                    member.joinedAt().toEpochMilli(),
                                                    names.displayName(member.subjectId())))
                            .toList();
            return ImReply.of(
                    Cmd.MEMBER_LIST_RESP, new MemberListResp(conversationId.toString(), views));
        }
    }

    /** 输入中。 */
    static final class Typing implements ImCommandHandler<TypingReq> {

        private final ImTypingService typing;

        Typing(ImTypingService typing) {
            this.typing = typing;
        }

        @Override
        public byte cmd() {
            return Cmd.TYPING;
        }

        @Override
        public Class<TypingReq> bodyType() {
            return TypingReq.class;
        }

        @Override
        public ImReply handle(ImCallContext context, TypingReq body) {
            typing.typing(context.caller(), body);
            return null;
        }
    }

    /** 在线状态。 */
    static final class Presence implements ImCommandHandler<PresenceReq> {

        private final ImPresenceService presence;

        Presence(ImPresenceService presence) {
            this.presence = presence;
        }

        @Override
        public byte cmd() {
            return Cmd.PRESENCE_REQ;
        }

        @Override
        public Class<PresenceReq> bodyType() {
            return PresenceReq.class;
        }

        @Override
        public ImReply handle(ImCallContext context, PresenceReq body) {
            return ImReply.of(Cmd.PRESENCE_RESP, presence.snapshot(context.caller(), body));
        }
    }
}
