package cn.byteforce.seedj.starter.im.protocol;

/** 协议命令字。编号与旧 IM 服务保持一致，客户端无需重新映射。 */
public final class Cmd {

    public static final byte LOGIN_REQ = 0x01;
    public static final byte LOGIN_RESP = 0x02;
    public static final byte HEARTBEAT_PING = 0x03;
    public static final byte HEARTBEAT_PONG = 0x04;
    public static final byte CHAT_SINGLE_REQ = 0x05;
    public static final byte CHAT_SINGLE_RESP = 0x06;
    public static final byte CHAT_GROUP_REQ = 0x07;
    public static final byte CHAT_GROUP_RESP = 0x08;
    public static final byte CHAT_DELIVER = 0x09;
    public static final byte ACK_REQ = 0x0A;
    public static final byte OFFLINE_PULL_REQ = 0x0B;
    public static final byte OFFLINE_PULL_RESP = 0x0C;
    public static final byte READ_RECEIPT = 0x0D;
    public static final byte SESSION_SYNC_REQ = 0x0E;
    public static final byte SESSION_SYNC_RESP = 0x0F;
    public static final byte RECALL_REQ = 0x10;
    public static final byte RECALL_RESP = 0x11;
    public static final byte EDIT_REQ = 0x12;
    public static final byte EDIT_RESP = 0x13;
    public static final byte CONVERSATION_LIST_REQ = 0x14;
    public static final byte CONVERSATION_LIST_RESP = 0x15;
    public static final byte MEMBER_LIST_REQ = 0x16;
    public static final byte MEMBER_LIST_RESP = 0x17;
    public static final byte TYPING = 0x18;
    public static final byte TYPING_NOTIFY = 0x19;
    public static final byte PRESENCE_REQ = 0x1A;
    public static final byte PRESENCE_RESP = 0x1B;
    public static final byte CARD_ACTION_REQ = 0x1C;
    public static final byte CARD_ACTION_RESP = 0x1D;
    public static final byte CARD_ACTION_NOTIFY = 0x1E;
    public static final byte ERROR = 0x7F;

    private Cmd() {}
}
