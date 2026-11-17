package cn.byteforce.seedj.starter.im;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.im.ImConversation;
import cn.byteforce.seedj.im.ImConversationKind;
import cn.byteforce.seedj.im.ImMember;
import cn.byteforce.seedj.im.spi.ImConversationStore;
import cn.byteforce.seedj.starter.im.protocol.Cmd;
import cn.byteforce.seedj.starter.im.protocol.body.PresenceReq;
import cn.byteforce.seedj.starter.im.protocol.body.PresenceResp;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 在线状态：本节点 {@link ChannelRegistry} 的视图，变化时向单聊对端广播。
 *
 * <p>多节点部署时，状态查询只回答本节点；变化通知经 {@link ImPushProvider}（coord 投递时跨节点）。
 * 这是刻意的取舍：把在线状态做成全局一致需要心跳级复制，而「查不到就按离线处理」不会破坏业务。
 *
 * <p>查询只返回与调用方同会话的 subject，避免把任意用户的在线状态暴露成公开接口。
 *
 * @author ByteForce
 * @since 1.0.0
 */
final class ImPresenceService implements ImPresenceListener {

    private static final int MAX_SUBJECTS = 200;

    private final ChannelRegistry registry;

    private final ImConversationStore conversations;

    private final ImPushProvider push;

    ImPresenceService(
            ChannelRegistry registry, ImConversationStore conversations, ImPushProvider push) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.push = Objects.requireNonNull(push, "push");
    }

    /**
     * 查询在线状态。
     *
     * @param caller 调用方
     * @param request 查询请求
     * @return 在线状态
     */
    PresenceResp snapshot(Caller caller, PresenceReq request) {
        List<String> requested =
                request == null
                        ? List.of()
                        : new ArrayList<>(new LinkedHashSet<>(request.subjectIds()));
        if (requested.isEmpty()) {
            return new PresenceResp(List.of());
        }
        if (requested.size() > MAX_SUBJECTS) {
            throw new ContractException(
                    ContractErrorCode.INVALID_PAYLOAD,
                    "一次最多查询 " + MAX_SUBJECTS + " 个 subject: " + requested.size());
        }
        Set<String> visible = visibleSubjects(caller);
        List<PresenceResp.Entry> entries = new ArrayList<>(requested.size());
        for (String subjectId : requested) {
            if (subjectId == null || subjectId.isBlank() || !visible.contains(subjectId)) {
                continue;
            }
            entries.add(entry(subjectId));
        }
        return new PresenceResp(entries);
    }

    @Override
    public void changed(String tenantId, String subjectId, boolean online) {
        if (subjectId == null || subjectId.isBlank()) {
            return;
        }
        PresenceResp.Entry entry = entry(subjectId);
        for (ImConversation conversation :
                conversations.conversationsOfSubject(tenantId, subjectId)) {
            if (conversation.kind() != ImConversationKind.SINGLE) {
                continue;
            }
            List<String> peers =
                    conversations.members(conversation.id()).stream()
                            .map(ImMember::subjectId)
                            .filter(peer -> !peer.equals(subjectId))
                            .toList();
            if (!peers.isEmpty()) {
                push.deliverMany(peers, Cmd.PRESENCE_REQ, entry);
            }
        }
    }

    private PresenceResp.Entry entry(String subjectId) {
        int devices = registry.count(subjectId);
        return new PresenceResp.Entry(subjectId, devices > 0, devices);
    }

    private Set<String> visibleSubjects(Caller caller) {
        Set<String> visible = new LinkedHashSet<>();
        for (ImConversation conversation :
                conversations.conversationsOfSubject(caller.tenantId(), caller.id())) {
            for (ImMember member : conversations.members(conversation.id())) {
                visible.add(member.subjectId());
            }
        }
        return visible;
    }
}
