package cn.byteforce.seedj.starter.im.web;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.CallerProvider;
import cn.byteforce.seedj.im.ImConversation;
import cn.byteforce.seedj.im.ImMemberRole;
import cn.byteforce.seedj.starter.im.ImConversationService;
import cn.byteforce.seedj.starter.im.ImMessagingService;
import cn.byteforce.seedj.starter.im.protocol.body.SessionSyncResp;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * IM 管理端点：建会话（单聊/群聊）、列会话、加人与退群。
 *
 * <p>授权完全交给 {@link ImConversationService}（租户、成员、{@code ImConversationPolicy}）； 端点本身只要求已认证——过滤器链的
 * {@code anyRequest().authenticated()} 已覆盖 {@code /im/**}。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@RestController
@RequestMapping("/im/conversations")
public class ImAdminController {

    private final CallerProvider callers;

    private final ImConversationService conversations;

    private final ImMessagingService messaging;

    /**
     * 构造端点。
     *
     * @param callers 调用方入口
     * @param conversations 会话服务
     * @param messaging 消息服务
     */
    public ImAdminController(
            CallerProvider callers,
            ImConversationService conversations,
            ImMessagingService messaging) {
        this.callers = callers;
        this.conversations = conversations;
        this.messaging = messaging;
    }

    /**
     * 建单聊或群聊。
     *
     * @param request 建会话请求
     * @return 会话视图
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionSyncResp.ConversationView create(@RequestBody CreateConversationRequest request) {
        Caller caller = caller();
        if (request == null) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD, "缺少请求体");
        }
        String kind = request.kind() == null ? "" : request.kind().toLowerCase(Locale.ROOT);
        ImConversation conversation =
                switch (kind) {
                    case "single" -> conversations.createSingle(caller, request.peerId());
                    case "group" ->
                            conversations.createGroup(caller, request.title(), request.memberIds());
                    default ->
                            throw new ContractException(
                                    ContractErrorCode.INVALID_PAYLOAD, "kind 只能是 single 或 group");
                };
        return messaging.view(caller, conversation);
    }

    /**
     * 列当前调用方的会话。
     *
     * @return 会话视图列表
     */
    @GetMapping
    public List<SessionSyncResp.ConversationView> list() {
        return messaging.conversationViews(caller());
    }

    /**
     * 加人。
     *
     * @param conversationId 会话标识
     * @param request 加人请求
     */
    @PostMapping("/{conversationId}/members")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addMember(
            @PathVariable String conversationId, @RequestBody AddMemberRequest request) {
        Caller caller = caller();
        if (request == null) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD, "缺少请求体");
        }
        conversations.addMember(
                caller, uuid(conversationId), request.subjectId(), role(request.role()));
    }

    /**
     * 退群或被移出。
     *
     * @param conversationId 会话标识
     * @param subjectId 要退出的 subject
     */
    @DeleteMapping("/{conversationId}/members/{subjectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable String conversationId, @PathVariable String subjectId) {
        conversations.removeMember(caller(), uuid(conversationId), subjectId);
    }

    private Caller caller() {
        return callers.find()
                .orElseThrow(
                        () -> new ContractException(ContractErrorCode.UNAUTHENTICATED, "缺少调用方身份"));
    }

    private static UUID uuid(String conversationId) {
        try {
            return UUID.fromString(conversationId);
        } catch (IllegalArgumentException malformed) {
            throw new ContractException(
                    ContractErrorCode.INVALID_PAYLOAD, "会话标识格式非法: " + conversationId);
        }
    }

    private static ImMemberRole role(String role) {
        if (role == null || role.isBlank()) {
            return ImMemberRole.MEMBER;
        }
        try {
            return ImMemberRole.valueOf(role.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD, "未知角色: " + role);
        }
    }
}
