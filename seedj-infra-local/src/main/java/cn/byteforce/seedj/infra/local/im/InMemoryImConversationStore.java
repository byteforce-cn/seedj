package cn.byteforce.seedj.infra.local.im;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.domain.Page;
import cn.byteforce.seedj.domain.PageRequest;
import cn.byteforce.seedj.im.ImConversation;
import cn.byteforce.seedj.im.ImMember;
import cn.byteforce.seedj.im.ImMemberRole;
import cn.byteforce.seedj.im.spi.ImConversationStore;
import cn.byteforce.seedj.im.spi.ImSequenceAllocator;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存会话存储：单实例开发与测试用，重启即丢。
 *
 * <p>成员事件只在 PostgreSQL 实现里落表；内存实现仍然为每条变更消耗一个序号，保证两套实现的 序号推进行为一致（契约测试用序号对比来发现偏差）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class InMemoryImConversationStore implements ImConversationStore {

    private static final Comparator<ImConversation> BY_RECENCY =
            Comparator.comparing(ImConversation::updatedAt)
                    .thenComparing(ImConversation::id)
                    .reversed();

    private final ImSequenceAllocator numbers;
    private final Clock clock;
    private final Map<UUID, ImConversation> conversations = new ConcurrentHashMap<>();
    private final Map<String, UUID> byDirectKey = new ConcurrentHashMap<>();
    private final Map<UUID, List<ImMember>> tenures = new ConcurrentHashMap<>();

    public InMemoryImConversationStore(ImSequenceAllocator numbers, Clock clock) {
        this.numbers = numbers;
        this.clock = clock;
    }

    @Override
    public synchronized ImConversation create(ImConversation conversation) {
        if (conversation.directKey() != null) {
            UUID existing =
                    byDirectKey.putIfAbsent(
                            key(conversation.tenantId(), conversation.directKey()),
                            conversation.id());
            if (existing != null) {
                throw new ContractException(
                        ContractErrorCode.IDEMPOTENCY_CONFLICT,
                        "conversation already exists for direct key: " + conversation.directKey());
            }
        }
        if (conversations.putIfAbsent(conversation.id(), conversation) != null) {
            throw new ContractException(
                    ContractErrorCode.IDEMPOTENCY_CONFLICT,
                    "conversation id already exists: " + conversation.id());
        }
        numbers.next();
        return conversation;
    }

    @Override
    public Optional<ImConversation> find(UUID id) {
        return Optional.ofNullable(id == null ? null : conversations.get(id));
    }

    @Override
    public Optional<ImConversation> findByDirectKey(String tenantId, String directKey) {
        if (directKey == null) {
            return Optional.empty();
        }
        UUID id = byDirectKey.get(key(tenantId, directKey));
        return find(id);
    }

    @Override
    public Page<ImConversation> pageOfSubject(String tenantId, String subjectId, PageRequest page) {
        List<ImConversation> all = conversationsOfSubject(tenantId, subjectId);
        int from = (int) Math.min(page.offset(), all.size());
        int to = Math.min(from + page.size(), all.size());
        return new Page<>(List.copyOf(all.subList(from, to)), all.size(), page.page(), page.size());
    }

    @Override
    public List<ImConversation> conversationsOfSubject(String tenantId, String subjectId) {
        Map<UUID, ImConversation> found = new LinkedHashMap<>();
        for (Map.Entry<UUID, List<ImMember>> entry : tenures.entrySet()) {
            boolean active = false;
            for (ImMember tenure : entry.getValue()) {
                if (tenure.subjectId().equals(subjectId) && tenure.active()) {
                    active = true;
                    break;
                }
            }
            if (!active) {
                continue;
            }
            ImConversation conversation = conversations.get(entry.getKey());
            if (conversation != null && conversation.tenantId().equals(tenantId)) {
                found.put(conversation.id(), conversation);
            }
        }
        return found.values().stream().sorted(BY_RECENCY).toList();
    }

    @Override
    public synchronized void rename(UUID conversationId, String title, String actorId) {
        ImConversation current = require(conversationId);
        conversations.put(
                conversationId,
                new ImConversation(
                        current.id(),
                        current.tenantId(),
                        current.kind(),
                        title,
                        current.ownerId(),
                        current.directKey(),
                        current.createdAt(),
                        clock.instant()));
        numbers.next();
    }

    @Override
    public synchronized void addMember(
            UUID conversationId, String subjectId, ImMemberRole role, String actorId) {
        require(conversationId);
        List<ImMember> list = tenures.computeIfAbsent(conversationId, key -> new ArrayList<>());
        if (list.stream().anyMatch(m -> m.subjectId().equals(subjectId) && m.active())) {
            return;
        }
        list.add(
                new ImMember(
                        conversationId,
                        subjectId,
                        numbers.next(),
                        null,
                        role,
                        null,
                        clock.instant()));
        numbers.next();
        touch(conversationId);
    }

    @Override
    public synchronized void removeMember(UUID conversationId, String subjectId, String actorId) {
        require(conversationId);
        List<ImMember> list = tenures.getOrDefault(conversationId, new ArrayList<>());
        for (int i = 0; i < list.size(); i++) {
            ImMember tenure = list.get(i);
            if (tenure.subjectId().equals(subjectId) && tenure.active()) {
                list.set(
                        i,
                        new ImMember(
                                tenure.conversationId(),
                                tenure.subjectId(),
                                tenure.joinedSeq(),
                                numbers.next(),
                                tenure.role(),
                                tenure.mutedUntil(),
                                tenure.joinedAt()));
                numbers.next();
                touch(conversationId);
                return;
            }
        }
    }

    @Override
    public List<ImMember> members(UUID conversationId) {
        return tenures.getOrDefault(conversationId, List.of()).stream()
                .filter(ImMember::active)
                .toList();
    }

    @Override
    public List<ImMember> membersAt(UUID conversationId, long seq) {
        return tenures.getOrDefault(conversationId, List.of()).stream()
                .filter(tenure -> tenure.activeAt(seq))
                .toList();
    }

    @Override
    public Optional<ImMember> member(UUID conversationId, String subjectId) {
        return tenures.getOrDefault(conversationId, List.of()).stream()
                .filter(tenure -> tenure.active() && tenure.subjectId().equals(subjectId))
                .findFirst();
    }

    private ImConversation require(UUID conversationId) {
        ImConversation conversation =
                conversationId == null ? null : conversations.get(conversationId);
        if (conversation == null) {
            throw new ContractException(
                    ContractErrorCode.OBJECT_NOT_FOUND,
                    "conversation not found: " + conversationId);
        }
        return conversation;
    }

    private void touch(UUID conversationId) {
        ImConversation current = conversations.get(conversationId);
        if (current == null) {
            return;
        }
        conversations.put(
                conversationId,
                new ImConversation(
                        current.id(),
                        current.tenantId(),
                        current.kind(),
                        current.title(),
                        current.ownerId(),
                        current.directKey(),
                        current.createdAt(),
                        clock.instant()));
    }

    private static String key(String tenantId, String directKey) {
        return tenantId + "\u0000" + directKey;
    }
}
