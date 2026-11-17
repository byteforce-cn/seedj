package cn.byteforce.seedj.starter.im;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.im.ImMessage;
import cn.byteforce.seedj.starter.im.protocol.Cmd;
import cn.byteforce.seedj.starter.im.protocol.body.AckReq;
import cn.byteforce.seedj.starter.im.protocol.body.ChatSendReq;
import cn.byteforce.seedj.starter.im.protocol.body.ChatSendResp;
import cn.byteforce.seedj.starter.im.protocol.body.OfflinePullReq;
import cn.byteforce.seedj.starter.im.protocol.body.ReadReceiptReq;
import cn.byteforce.seedj.starter.im.protocol.body.SessionSyncReq;

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
}
