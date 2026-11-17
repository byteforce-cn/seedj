package cn.byteforce.seedj.starter.im;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.im.ImConversation;
import cn.byteforce.seedj.im.ImMember;
import cn.byteforce.seedj.im.spi.ImConversationStore;
import cn.byteforce.seedj.starter.im.protocol.Cmd;
import cn.byteforce.seedj.starter.im.protocol.body.TypingNotify;
import cn.byteforce.seedj.starter.im.protocol.body.TypingReq;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 输入中：只做节流与转发，不落库。
 *
 * <p>同一（会话，主体）在 {@link #THROTTLE} 内重复上报“开始输入”会被静默丢弃；停止事件不节流， 保证客户端一定能收起指示器。节流状态是进程内的，重启或多节点部署时最多多转发几次，
 * 对端按时间戳覆盖即可。
 *
 * @author ByteForce
 * @since 1.0.0
 */
final class ImTypingService {

    static final Duration THROTTLE = Duration.ofSeconds(2);

    private static final int MAX_TRACKED = 10_000;

    private final ImConversationService conversationService;

    private final ImConversationStore conversations;

    private final ImPushProvider push;

    private final Clock clock;

    private final ConcurrentHashMap<String, Long> lastTyping = new ConcurrentHashMap<>();

    ImTypingService(
            ImConversationService conversationService,
            ImConversationStore conversations,
            ImPushProvider push,
            Clock clock) {
        this.conversationService = Objects.requireNonNull(conversationService, "conversationService");
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.push = Objects.requireNonNull(push, "push");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 处理一次输入中上报。
     *
     * @param caller 调用方
     * @param request 上报
     */
    void typing(Caller caller, TypingReq request) {
        if (request == null) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD, "缺少输入中请求体");
        }
        ImConversation conversation =
                conversationService.requireMemberConversation(
                        caller, ImProtocolIds.conversation(request.conversationId()));
        String key = conversation.id() + "\u0000" + caller.id();
        long now = clock.millis();
        if (request.typing()) {
            Long previous = lastTyping.get(key);
            if (previous != null && now - previous < THROTTLE.toMillis()) {
                return;
            }
            if (lastTyping.size() > MAX_TRACKED) {
                lastTyping.clear();
            }
            lastTyping.put(key, now);
        } else {
            lastTyping.remove(key);
        }
        List<String> others =
                conversations.members(conversation.id()).stream()
                        .map(ImMember::subjectId)
                        .filter(subject -> !subject.equals(caller.id()))
                        .toList();
        if (!others.isEmpty()) {
            push.deliverMany(
                    others,
                    Cmd.TYPING_NOTIFY,
                    new TypingNotify(
                            conversation.id().toString(), caller.id(), request.typing(), now));
        }
    }
}
