package cn.byteforce.seedj.infra.local.im;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.im.ImMessage;
import cn.byteforce.seedj.im.ImMessageDraft;
import cn.byteforce.seedj.im.spi.ImMessageStore;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 内存消息存储：单实例开发与测试用，重启即丢。
 *
 * <p>顺序按 {@code seq} 排序而不是插入顺序：内存实现不保证写入顺序与序号顺序一致，这与 PostgreSQL 的行为一致（全局序列可能先分配后提交）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class InMemoryImMessageStore implements ImMessageStore {

    private static final Comparator<ImMessage> BY_SEQ =
            Comparator.comparingLong(ImMessage::seq).thenComparing(ImMessage::id);

    private final Clock clock;
    private final List<ImMessage> messages = new ArrayList<>();
    private final Map<String, ImMessage> byClientMsgId = new HashMap<>();
    private final Set<String> seqs = new HashSet<>();

    public InMemoryImMessageStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized ImMessage append(ImMessageDraft draft) {
        if (draft.clientMsgId() != null) {
            ImMessage existing = byClientMsgId.get(clientKey(draft));
            if (existing != null) {
                return existing;
            }
        }
        String seqKey = draft.conversationId() + "\u0000" + draft.seq();
        if (!seqs.add(seqKey)) {
            throw new ContractException(
                    ContractErrorCode.VERSION_CONFLICT,
                    "sequence already used in conversation: " + draft.seq());
        }
        ImMessage message =
                new ImMessage(
                        draft.id(),
                        draft.tenantId(),
                        draft.conversationId(),
                        draft.seq(),
                        draft.senderId(),
                        draft.kind(),
                        draft.body(),
                        draft.clientMsgId(),
                        clock.instant(),
                        null,
                        null);
        messages.add(message);
        if (draft.clientMsgId() != null) {
            byClientMsgId.put(clientKey(draft), message);
        }
        return message;
    }

    @Override
    public synchronized Optional<ImMessage> find(UUID id) {
        return messages.stream().filter(message -> message.id().equals(id)).findFirst();
    }

    @Override
    public synchronized Optional<ImMessage> findByClientMsgId(
            UUID conversationId, String senderId, String clientMsgId) {
        if (clientMsgId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                byClientMsgId.get(clientKey(conversationId, senderId, clientMsgId)));
    }

    @Override
    public synchronized List<ImMessage> page(UUID conversationId, long afterSeq, int limit) {
        return messages.stream()
                .filter(message -> message.conversationId().equals(conversationId))
                .filter(message -> message.seq() > afterSeq)
                .sorted(BY_SEQ)
                .limit(requireLimit(limit))
                .toList();
    }

    @Override
    public synchronized List<ImMessage> sync(List<UUID> conversationIds, long afterSeq, int limit) {
        if (conversationIds == null || conversationIds.isEmpty()) {
            return List.of();
        }
        Set<UUID> wanted = new HashSet<>(conversationIds);
        return messages.stream()
                .filter(message -> wanted.contains(message.conversationId()))
                .filter(message -> message.seq() > afterSeq)
                .sorted(BY_SEQ)
                .limit(requireLimit(limit))
                .toList();
    }

    @Override
    public synchronized long maxSeq(UUID conversationId) {
        return messages.stream()
                .filter(message -> message.conversationId().equals(conversationId))
                .mapToLong(ImMessage::seq)
                .max()
                .orElse(0L);
    }

    @Override
    public synchronized long countAfter(UUID conversationId, long afterSeq) {
        return messages.stream()
                .filter(message -> message.conversationId().equals(conversationId))
                .filter(message -> message.seq() > afterSeq)
                .count();
    }

    @Override
    public synchronized ImMessage edit(UUID id, Map<String, Object> body) {
        ImMessage current = require(id);
        if (current.recalledAt() != null) {
            throw new ContractException(ContractErrorCode.COMMAND_REJECTED, "已撤回的消息不能编辑: " + id);
        }
        ImMessage updated =
                new ImMessage(
                        current.id(),
                        current.tenantId(),
                        current.conversationId(),
                        current.seq(),
                        current.senderId(),
                        current.kind(),
                        body,
                        current.clientMsgId(),
                        current.createdAt(),
                        clock.instant(),
                        null);
        replace(updated);
        return updated;
    }

    @Override
    public synchronized ImMessage recall(UUID id) {
        ImMessage current = require(id);
        if (current.recalledAt() != null) {
            return current;
        }
        ImMessage updated =
                new ImMessage(
                        current.id(),
                        current.tenantId(),
                        current.conversationId(),
                        current.seq(),
                        current.senderId(),
                        current.kind(),
                        current.body(),
                        current.clientMsgId(),
                        current.createdAt(),
                        current.editedAt(),
                        clock.instant());
        replace(updated);
        return updated;
    }

    @Override
    public synchronized int deleteBefore(Instant cutoff, int limit) {
        if (cutoff == null) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD, "cutoff 不能为空");
        }
        int bounded = requireLimit(limit);
        List<ImMessage> removed = new ArrayList<>();
        Iterator<ImMessage> iterator = messages.iterator();
        while (iterator.hasNext() && removed.size() < bounded) {
            ImMessage message = iterator.next();
            if (message.createdAt().isBefore(cutoff)) {
                iterator.remove();
                removed.add(message);
            }
        }
        for (ImMessage message : removed) {
            seqs.remove(seqKey(message.conversationId(), message.seq()));
            if (message.clientMsgId() != null) {
                byClientMsgId.remove(
                        clientKey(
                                message.conversationId(),
                                message.senderId(),
                                message.clientMsgId()));
            }
        }
        return removed.size();
    }

    private ImMessage require(UUID id) {
        return messages.stream()
                .filter(message -> message.id().equals(id))
                .findFirst()
                .orElseThrow(
                        () ->
                                new ContractException(
                                        ContractErrorCode.OBJECT_NOT_FOUND,
                                        "message not found: " + id));
    }

    private void replace(ImMessage updated) {
        for (int index = 0; index < messages.size(); index++) {
            if (messages.get(index).id().equals(updated.id())) {
                messages.set(index, updated);
                if (updated.clientMsgId() != null) {
                    byClientMsgId.put(
                            clientKey(
                                    updated.conversationId(),
                                    updated.senderId(),
                                    updated.clientMsgId()),
                            updated);
                }
                return;
            }
        }
    }

    private static String seqKey(UUID conversationId, long seq) {
        return conversationId + "\u0000" + seq;
    }

    /** 测试辅助：当前保存的消息条数。 */
    public synchronized int size() {
        return messages.size();
    }

    private static int requireLimit(int limit) {
        if (limit <= 0) {
            throw new ContractException(
                    ContractErrorCode.INVALID_PAYLOAD, "limit must be positive: " + limit);
        }
        return limit;
    }

    private static String clientKey(ImMessageDraft draft) {
        return clientKey(draft.conversationId(), draft.senderId(), draft.clientMsgId());
    }

    private static String clientKey(UUID conversationId, String senderId, String clientMsgId) {
        return conversationId + "\u0000" + senderId + "\u0000" + clientMsgId;
    }
}
