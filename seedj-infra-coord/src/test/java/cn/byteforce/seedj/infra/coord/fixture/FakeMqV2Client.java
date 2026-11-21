package cn.byteforce.seedj.infra.coord.fixture;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.coord.sdk.ErrorCode;
import cn.byteforce.coord.sdk.mq.v2.MqV2Ack;
import cn.byteforce.coord.sdk.mq.v2.MqV2AckResult;
import cn.byteforce.coord.sdk.mq.v2.MqV2Assignment;
import cn.byteforce.coord.sdk.mq.v2.MqV2BatchEntry;
import cn.byteforce.coord.sdk.mq.v2.MqV2Client;
import cn.byteforce.coord.sdk.mq.v2.MqV2CommittedOffset;
import cn.byteforce.coord.sdk.mq.v2.MqV2DeleteTopic;
import cn.byteforce.coord.sdk.mq.v2.MqV2GroupDescription;
import cn.byteforce.coord.sdk.mq.v2.MqV2Heartbeat;
import cn.byteforce.coord.sdk.mq.v2.MqV2JoinGroup;
import cn.byteforce.coord.sdk.mq.v2.MqV2Joined;
import cn.byteforce.coord.sdk.mq.v2.MqV2Leaders;
import cn.byteforce.coord.sdk.mq.v2.MqV2Message;
import cn.byteforce.coord.sdk.mq.v2.MqV2MoveToDlq;
import cn.byteforce.coord.sdk.mq.v2.MqV2MoveToDlqResult;
import cn.byteforce.coord.sdk.mq.v2.MqV2PartitionInfo;
import cn.byteforce.coord.sdk.mq.v2.MqV2Poll;
import cn.byteforce.coord.sdk.mq.v2.MqV2PollDlqResult;
import cn.byteforce.coord.sdk.mq.v2.MqV2PollResult;
import cn.byteforce.coord.sdk.mq.v2.MqV2Publish;
import cn.byteforce.coord.sdk.mq.v2.MqV2PublishResult;
import cn.byteforce.coord.sdk.mq.v2.MqV2TopicConfig;
import cn.byteforce.coord.sdk.mq.v2.MqV2TopicCreated;
import cn.byteforce.coord.sdk.mq.v2.MqV2TopicDeleted;
import cn.byteforce.coord.sdk.mq.v2.MqV2TopicDeletion;
import cn.byteforce.coord.sdk.mq.v2.MqV2TopicDescription;
import cn.byteforce.coord.sdk.mq.v2.MqV2TopicPartition;
import cn.byteforce.coord.sdk.mq.v2.MqV2TopicSummary;
import cn.byteforce.coord.sdk.mq.v2.MqV2TopicUpdated;
import cn.byteforce.coord.sdk.mq.v2.MqV2UpdateTopic;
import cn.byteforce.coord.sdk.mq.v2.Page;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** MQ v2 的内存替身：单成员分组、按 offset 读日志、ack 提交位点。 */
public final class FakeMqV2Client implements MqV2Client {

    private final Map<String, Topic> topics = new HashMap<>();
    private final Map<String, List<MqV2Message>> logs = new HashMap<>();
    private final Map<String, Map<String, Long>> commits = new HashMap<>();
    private final Map<String, Long> earliest = new HashMap<>();

    private long generation;
    private long memberSeq;
    private long epoch;
    private boolean down;

    /** 模拟 agent 不可达。 */
    public void down(boolean value) {
        down = value;
    }

    /** 清空 topic、日志与位点。 */
    public void reset() {
        topics.clear();
        logs.clear();
        commits.clear();
        earliest.clear();
    }

    /** 模拟 retention：把分区的可读起点前移到 {@code fromOffset}。 */
    public void retainFrom(String topic, int partition, long fromOffset) {
        earliest.put(slot(topic, partition), fromOffset);
    }

    public void createTopic(String topic, int partitions) {
        topics.put(topic, new Topic(topic, ++epoch, partitions));
    }

    /** 以 seedj 事件头发布一条消息，返回 append 后的 offset。 */
    public long send(
            String topic, int partition, String key, byte[] payload, Map<String, String> headers) {
        createIfMissing(topic);
        String slot = slot(topic, partition);
        List<MqV2Message> log = logs.computeIfAbsent(slot, ignored -> new ArrayList<>());
        long offset = log.size();
        log.add(
                new MqV2Message(
                        topic,
                        partition,
                        offset,
                        System.currentTimeMillis(),
                        key == null
                                ? new byte[0]
                                : key.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        payload,
                        headers,
                        "test",
                        "",
                        ""));
        return offset;
    }

    private void createIfMissing(String topic) {
        if (!topics.containsKey(topic)) {
            createTopic(topic, 1);
        }
    }

    private static String slot(String topic, int partition) {
        return topic + "\u0000" + partition;
    }

    private void failIfDown() {
        if (down) {
            throw new CoordException(ErrorCode.UNAVAILABLE, "fake coord agent is down");
        }
    }

    private Topic topic(String topic) {
        Topic meta = topics.get(topic);
        if (meta == null) {
            throw new CoordException(ErrorCode.TOPIC_NOT_FOUND, "topic not found: " + topic);
        }
        return meta;
    }

    @Override
    public MqV2TopicCreated createTopic(
            String topic, int partitions, String requestId, MqV2TopicConfig config) {
        failIfDown();
        if (topics.containsKey(topic)) {
            throw new CoordException(ErrorCode.TOPIC_EXISTS, "topic exists: " + topic);
        }
        createTopic(topic, partitions);
        return new MqV2TopicCreated(topic, 1, topics.get(topic).epoch(), partitions, null);
    }

    @Override
    public MqV2TopicDescription describeTopic(String topic) {
        failIfDown();
        Topic meta = topic(topic);
        return new MqV2TopicDescription(
                topic, 1, meta.epoch(), meta.partitions(), null, 0, partitions(topic, meta));
    }

    @Override
    public MqV2Leaders getTopicLeaders(String topic) {
        failIfDown();
        Topic meta = topic(topic);
        return new MqV2Leaders(topic, 1, meta.epoch(), partitions(topic, meta));
    }

    private List<MqV2PartitionInfo> partitions(String topic, Topic meta) {
        List<MqV2PartitionInfo> result = new ArrayList<>();
        for (int partition = 0; partition < meta.partitions(); partition++) {
            int size = logs.getOrDefault(slot(topic, partition), List.of()).size();
            result.add(new MqV2PartitionInfo(partition, partition, "local", 0, size, 0));
        }
        return result;
    }

    @Override
    public MqV2PublishResult publish(MqV2Publish publish) {
        failIfDown();
        createIfMissing(publish.topic());
        long offset =
                send(
                        publish.topic(),
                        publish.partition(),
                        publish.key() == null
                                ? null
                                : new String(
                                        publish.key(), java.nio.charset.StandardCharsets.UTF_8),
                        publish.payload(),
                        publish.headers());
        return new MqV2PublishResult(offset, System.currentTimeMillis(), false);
    }

    @Override
    public MqV2PollResult poll(MqV2Poll request) {
        failIfDown();
        Topic meta = topic(request.topic());
        if (request.topicEpoch() != 0 && request.topicEpoch() != meta.epoch()) {
            throw new CoordException(ErrorCode.TOPIC_EPOCH_STALE, "topic epoch is stale");
        }
        List<MqV2Message> log =
                logs.getOrDefault(slot(request.topic(), request.partition()), List.of());
        long earliest = earliestOffset(request.topic(), request.partition());
        long start = request.startOffset() == null ? earliest : request.startOffset();
        if (start < earliest) {
            throw new CoordException(ErrorCode.OFFSET_OUT_OF_RANGE, "offset below earliest");
        }
        int limit = request.maxMessages() > 0 ? request.maxMessages() : 100;
        List<MqV2Message> messages = new ArrayList<>();
        for (long offset = start; offset < log.size() && messages.size() < limit; offset++) {
            messages.add(log.get((int) offset));
        }
        long next = messages.isEmpty() ? start : messages.get(messages.size() - 1).offset() + 1;
        return new MqV2PollResult(messages, next, earliest, log.size());
    }

    @Override
    public MqV2Joined joinGroup(MqV2JoinGroup join) {
        failIfDown();
        for (String topic : join.topics()) {
            topic(topic);
        }
        generation++;
        String memberId = join.memberId().isEmpty() ? "member-" + (++memberSeq) : join.memberId();
        List<MqV2Assignment> assignments = new ArrayList<>();
        for (String topic : join.topics()) {
            Topic meta = topic(topic);
            for (int partition = 0; partition < meta.partitions(); partition++) {
                assignments.add(
                        new MqV2Assignment(
                                topic, partition, committed(join.group(), topic, partition)));
            }
        }
        return new MqV2Joined(memberId, generation, assignments, 60_000, 30_000);
    }

    private long earliestOffset(String topic, int partition) {
        return earliest.getOrDefault(slot(topic, partition), 0L);
    }

    private long committed(String group, String topic, int partition) {
        return commits.getOrDefault(group, Map.of()).getOrDefault(slot(topic, partition), 0L);
    }

    @Override
    public MqV2Heartbeat heartbeat(String group, String memberId, long generation) {
        failIfDown();
        return new MqV2Heartbeat(generation, false);
    }

    @Override
    public void leaveGroup(String group, String memberId, long generation) {
        failIfDown();
    }

    @Override
    public long ack(String group, String memberId, long generation, MqV2Ack ack) {
        failIfDown();
        long next = ack.offset() + 1;
        commits.computeIfAbsent(group, ignored -> new HashMap<>())
                .merge(slot(ack.topic(), ack.partition()), next, Math::max);
        return next;
    }

    @Override
    public List<MqV2CommittedOffset> getCommittedOffsets(
            String group, List<MqV2TopicPartition> partitions) {
        failIfDown();
        List<MqV2CommittedOffset> result = new ArrayList<>();
        for (MqV2TopicPartition partition : partitions) {
            long offset = committed(group, partition.topic(), partition.partition());
            result.add(
                    new MqV2CommittedOffset(
                            partition.topic(), partition.partition(), offset, offset > 0));
        }
        return result;
    }

    @Override
    public List<MqV2AckResult> ackBatch(
            String group, String memberId, long generation, List<MqV2Ack> acks) {
        List<MqV2AckResult> result = new ArrayList<>();
        for (MqV2Ack ack : acks) {
            long next = ack(group, memberId, generation, ack);
            result.add(new MqV2AckResult(ack.topic(), ack.partition(), next));
        }
        return result;
    }

    @Override
    public List<MqV2PublishResult> publishBatch(
            String topic, int partition, long topicEpoch, List<MqV2BatchEntry> entries) {
        throw new UnsupportedOperationException("publishBatch is not used by seedj");
    }

    @Override
    public MqV2TopicUpdated updateTopic(MqV2UpdateTopic update) {
        throw new UnsupportedOperationException("updateTopic is not used by seedj");
    }

    @Override
    public MqV2TopicDeleted deleteTopic(MqV2DeleteTopic delete) {
        throw new UnsupportedOperationException("deleteTopic is not used by seedj");
    }

    @Override
    public MqV2TopicDeletion getTopicDeletion(String deleteId) {
        throw new UnsupportedOperationException("getTopicDeletion is not used by seedj");
    }

    @Override
    public MqV2GroupDescription describeGroup(String group) {
        throw new UnsupportedOperationException("describeGroup is not used by seedj");
    }

    @Override
    public Page<String> listGroupsPage(String prefix, int pageSize, String pageToken) {
        throw new UnsupportedOperationException("listGroupsPage is not used by seedj");
    }

    @Override
    public List<MqV2TopicSummary> listTopics(String prefix, int pageSize, String pageToken) {
        throw new UnsupportedOperationException("listTopics is not used by seedj");
    }

    @Override
    public Page<MqV2TopicSummary> listTopicsPage(String prefix, int pageSize, String pageToken) {
        throw new UnsupportedOperationException("listTopicsPage is not used by seedj");
    }

    @Override
    public MqV2MoveToDlqResult moveToDlq(MqV2MoveToDlq move) {
        throw new UnsupportedOperationException("moveToDlq is not used by seedj");
    }

    @Override
    public MqV2PollDlqResult pollDlq(
            String topic, int partition, long topicEpoch, Long startOffset, int maxMessages) {
        throw new UnsupportedOperationException("pollDlq is not used by seedj");
    }

    @Override
    public MqV2PollDlqResult pollDlq(
            String topic,
            int partition,
            long topicEpoch,
            Long startOffset,
            int maxMessages,
            String consumerGroup) {
        return pollDlq(topic, partition, topicEpoch, startOffset, maxMessages);
    }

    private record Topic(String name, long epoch, int partitions) {
        Topic {
            Objects.requireNonNull(name, "name");
        }
    }
}
