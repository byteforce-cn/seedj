package cn.byteforce.seedj.starter.im;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.domain.Page;
import cn.byteforce.seedj.domain.PageRequest;
import cn.byteforce.seedj.im.ImConversation;
import cn.byteforce.seedj.im.ImConversationKind;
import cn.byteforce.seedj.im.ImMember;
import cn.byteforce.seedj.im.ImMemberRole;
import cn.byteforce.seedj.im.spi.ImConversationStore;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 会话服务：建单聊、建群、加人、退群、列会话。
 *
 * <p>租户隔离与成员校验都收在这里，socket 命令和 HTTP 端点共用同一条路径，避免出现「两个入口 两套规则」。单聊靠 {@link ImConversation#directKey}
 * 去重，并发建同一会话时唯一约束兜底。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class ImConversationService {

    private final ImConversationStore conversations;

    private final ImAuthorizationGate gate;

    private final ImConversationPolicy policy;

    private final Clock clock;

    /**
     * 构造服务。
     *
     * @param conversations 会话存储
     * @param gate 单聊授权闸门
     * @param policy 会话治理策略
     * @param clock 取时入口
     */
    public ImConversationService(
            ImConversationStore conversations,
            ImAuthorizationGate gate,
            ImConversationPolicy policy,
            Clock clock) {
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 建或取单聊会话。
     *
     * @param caller 调用方
     * @param peerId 对端 subject
     * @return 会话（已存在时直接返回）
     */
    public ImConversation createSingle(Caller caller, String peerId) {
        String subject = requireSubject(caller);
        String peer = requirePeer(peerId, subject);
        if (!gate.canMessage(caller, peer)) {
            throw new ContractException(ContractErrorCode.FORBIDDEN, "不允许与该用户建立会话");
        }
        String directKey = ImConversation.directKey(subject, peer);
        return conversations
                .findByDirectKey(caller.tenantId(), directKey)
                .orElseGet(() -> createSingleConversation(caller, subject, peer, directKey));
    }

    /**
     * 建群。
     *
     * @param caller 调用方
     * @param title 群名
     * @param memberIds 初始成员（调用方自动成为群主）
     * @return 会话
     */
    public ImConversation createGroup(Caller caller, String title, List<String> memberIds) {
        String owner = requireSubject(caller);
        if (!policy.canCreateGroup(caller)) {
            throw new ContractException(ContractErrorCode.FORBIDDEN, "不允许建群");
        }
        if (title == null || title.isBlank()) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD, "群名不能为空");
        }
        Set<String> members = new LinkedHashSet<>();
        members.add(owner);
        if (memberIds != null) {
            memberIds.stream().filter(id -> id != null && !id.isBlank()).forEach(members::add);
        }
        requireWithinMemberLimit(members.size());

        ImConversation conversation =
                new ImConversation(
                        UUID.randomUUID(),
                        caller.tenantId(),
                        ImConversationKind.GROUP,
                        title,
                        owner,
                        null,
                        clock.instant(),
                        null);
        conversations.create(conversation);
        for (String member : members) {
            conversations.addMember(
                    conversation.id(),
                    member,
                    member.equals(owner) ? ImMemberRole.OWNER : ImMemberRole.MEMBER,
                    owner);
        }
        return conversations.find(conversation.id()).orElse(conversation);
    }

    /**
     * 加人。
     *
     * @param caller 调用方
     * @param conversationId 会话标识
     * @param subjectId 被加入的 subject
     * @param role 角色；{@code null} 视为普通成员
     */
    public void addMember(Caller caller, UUID conversationId, String subjectId, ImMemberRole role) {
        ImConversation conversation = requireMemberConversation(caller, conversationId);
        if (!policy.canAddMember(caller, conversation, subjectId)) {
            throw new ContractException(ContractErrorCode.FORBIDDEN, "不允许加人");
        }
        requireWithinMemberLimit(conversations.members(conversationId).size() + 1);
        conversations.addMember(
                conversationId,
                requirePeer(subjectId, caller.id()),
                role == null ? ImMemberRole.MEMBER : role,
                caller.id());
    }

    /**
     * 退群或被移除。
     *
     * @param caller 调用方
     * @param conversationId 会话标识
     * @param subjectId 要退出的 subject
     */
    public void removeMember(Caller caller, UUID conversationId, String subjectId) {
        ImConversation conversation = requireMemberConversation(caller, conversationId);
        if (!policy.canRemoveMember(caller, conversation, subjectId)) {
            throw new ContractException(ContractErrorCode.FORBIDDEN, "不允许移出成员");
        }
        conversations.removeMember(conversationId, subjectId, caller.id());
    }

    /**
     * 改群名。
     *
     * @param caller 调用方
     * @param conversationId 会话标识
     * @param title 新群名
     */
    public void rename(Caller caller, UUID conversationId, String title) {
        ImConversation conversation = requireMemberConversation(caller, conversationId);
        if (!policy.canRename(caller, conversation)) {
            throw new ContractException(ContractErrorCode.FORBIDDEN, "不允许改群名");
        }
        if (title == null || title.isBlank()) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD, "群名不能为空");
        }
        conversations.rename(conversationId, title, caller.id());
    }

    /**
     * 调用方当前所属会话。
     *
     * @param caller 调用方
     * @param page 分页
     * @return 一页会话
     */
    public Page<ImConversation> list(Caller caller, PageRequest page) {
        return conversations.pageOfSubject(caller.tenantId(), caller.id(), page);
    }

    /**
     * 会话成员：当前任期或某个序号时刻的任期。
     *
     * @param caller 调用方；必须在会话中
     * @param conversationId 会话标识
     * @param atSeq 时点序号；{@code null} 表示当前成员
     * @return 成员列表
     */
    public List<ImMember> members(Caller caller, UUID conversationId, Long atSeq) {
        ImConversation conversation = requireMemberConversation(caller, conversationId);
        if (atSeq == null) {
            return conversations.members(conversation.id());
        }
        if (atSeq <= 0) {
            throw new ContractException(
                    ContractErrorCode.INVALID_PAYLOAD, "at_seq 必须是正数: " + atSeq);
        }
        return conversations.membersAt(conversation.id(), atSeq);
    }

    /**
     * 校验调用方属于该会话且租户一致，返回会话。
     *
     * @param caller 调用方
     * @param conversationId 会话标识
     * @return 会话
     */
    public ImConversation requireMemberConversation(Caller caller, UUID conversationId) {
        if (conversationId == null) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD, "会话标识不能为空");
        }
        ImConversation conversation =
                conversations
                        .find(conversationId)
                        .orElseThrow(
                                () ->
                                        new ContractException(
                                                ContractErrorCode.OBJECT_NOT_FOUND,
                                                "会话不存在: " + conversationId));
        if (!conversation.tenantId().equals(caller.tenantId())) {
            throw new ContractException(ContractErrorCode.FORBIDDEN, "跨租户会话不可访问");
        }
        boolean member =
                conversations
                        .member(conversationId, caller.id())
                        .map(ImMember::active)
                        .orElse(false);
        if (!member) {
            throw new ContractException(ContractErrorCode.FORBIDDEN, "不在该会话中");
        }
        return conversation;
    }

    private ImConversation createSingleConversation(
            Caller caller, String subject, String peer, String directKey) {
        ImConversation candidate =
                new ImConversation(
                        UUID.randomUUID(),
                        caller.tenantId(),
                        ImConversationKind.SINGLE,
                        null,
                        subject,
                        directKey,
                        clock.instant(),
                        null);
        try {
            conversations.create(candidate);
        } catch (ContractException conflict) {
            if (conflict.code() != ContractErrorCode.IDEMPOTENCY_CONFLICT) {
                throw conflict;
            }
            return conversations
                    .findByDirectKey(caller.tenantId(), directKey)
                    .orElseThrow(() -> conflict);
        }
        conversations.addMember(candidate.id(), subject, ImMemberRole.OWNER, subject);
        conversations.addMember(candidate.id(), peer, ImMemberRole.MEMBER, subject);
        return conversations.find(candidate.id()).orElse(candidate);
    }

    private void requireWithinMemberLimit(int size) {
        int limit = policy.maxMembers();
        if (limit > 0 && size > limit) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD, "会话成员数超过上限: " + limit);
        }
    }

    private static String requireSubject(Caller caller) {
        if (caller == null || caller.id().isBlank()) {
            throw new ContractException(ContractErrorCode.UNAUTHENTICATED, "缺少调用方身份");
        }
        return caller.id();
    }

    private static String requirePeer(String peerId, String selfId) {
        if (peerId == null || peerId.isBlank()) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD, "对象 subject 不能为空");
        }
        if (peerId.equals(selfId)) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD, "不能与自己建立会话");
        }
        return peerId;
    }

    /**
     * 会话成员的 subject 列表，供发送与推送使用。
     *
     * @param conversationId 会话标识
     * @return subject 列表
     */
    public List<String> memberSubjects(UUID conversationId) {
        List<String> subjects = new ArrayList<>();
        for (ImMember member : conversations.members(conversationId)) {
            subjects.add(member.subjectId());
        }
        return subjects;
    }
}
