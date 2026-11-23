package cn.byteforce.seedj.infra.coord.workflow;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.coord.sdk.kv.KvClient;
import cn.byteforce.seedj.workflow.spi.WorkflowProviderException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * coord KV sidecar：存 coord 不建模、但 seedj 契约需要的补充事实。
 *
 * <p>键空间 {@code <coordNamespace>:workflow:sidecar:<instanceId>:...}。coord 命令是权威，
 * sidecar 只是审计/读面补充：读失败（坏 JSON）忽略并 WARN；{@code formData} 是例外——
 * 写失败必须拒绝动作，否则表单数据静默丢失。
 *
 * <p>无 TTL：coord KV 当前没有 TTL 面，保留策略由运维负责。
 */
final class CoordWorkflowSidecar {

    private static final Logger log = LoggerFactory.getLogger(CoordWorkflowSidecar.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final KvClient kv;
    private final String namespace;

    CoordWorkflowSidecar(KvClient kv, String namespace) {
        this.kv = kv;
        this.namespace = namespace;
    }

    void putForm(
            String instanceId,
            String roundId,
            String actionId,
            Instant at,
            Map<String, String> formData) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("at", at == null ? Instant.now().toEpochMilli() : at.toEpochMilli());
        value.put("formData", new TreeMap<>(formData));
        try {
            kv.put(bytes(formKey(instanceId, roundId, actionId)), MAPPER.writeValueAsBytes(value));
        } catch (CoordException e) {
            throw new WorkflowProviderException(
                    "cannot persist form data for action " + actionId + ": " + e.getMessage(), e);
        }
    }

    /** 同一 round 的多个 action 按写入时间合并（后者覆盖前者同名字段）。 */
    Map<String, String> form(String instanceId, String roundId) {
        String prefix = formKey(instanceId, roundId, "");
        List<Map.Entry<Long, Map<String, String>>> entries = new ArrayList<>();
        for (KvClient.KeyValue kvEntry : scan(prefix)) {
            try {
                JsonNode node = MAPPER.readTree(kvEntry.value());
                Map<String, String> form = new TreeMap<>();
                JsonNode data = node.get("formData");
                if (data != null && data.isObject()) {
                    for (var field : data.properties()) {
                        JsonNode fieldValue = field.getValue();
                        if (fieldValue != null && !fieldValue.isNull()) {
                            form.put(field.getKey(), fieldValue.stringValue());
                        }
                    }
                }
                entries.add(Map.entry(node.path("at").asLong(), form));
            } catch (RuntimeException e) {
                log.warn("ignoring corrupt coord workflow sidecar form entry: {}", e.getMessage());
            }
        }
        entries.sort(Map.Entry.comparingByKey());
        Map<String, String> merged = new LinkedHashMap<>();
        for (var entry : entries) {
            merged.putAll(entry.getValue());
        }
        return merged;
    }

    void markRemoved(String instanceId, String roundId, List<String> principals) {
        for (String principal : principals) {
            try {
                kv.put(bytes(removedKey(instanceId, roundId, principal)), bytes("1"));
            } catch (CoordException e) {
                log.warn("cannot persist removed-assignee marker: {}", e.getMessage());
            }
        }
    }

    boolean removed(String instanceId, String roundId, String principal) {
        try {
            byte[] key = bytes(removedKey(instanceId, roundId, principal));
            return !kv.range(key, prefixEnd(key), 1, 0, false, false).kvs().isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private String removedKey(String instanceId, String roundId, String principal) {
        return instancePrefix(instanceId) + ":removed:" + roundId + ":" + principal;
    }

    void putAction(String instanceId, String actionId, Map<String, String> extras) {
        try {
            byte[] encoded = MAPPER.writeValueAsBytes(new TreeMap<>(extras));
            if (System.getProperty("workflow.debug") != null) {
                System.out.println(
                        "DEBUG putAction " + actionId + " -> " + new String(encoded, StandardCharsets.UTF_8));
            }
            kv.put(bytes(actionKey(instanceId, actionId)), encoded);
        } catch (CoordException e) {
            // coord 命令才是权威；审计补充写失败不阻断动作。
            log.warn("cannot persist action extras for {}: {}", actionId, e.getMessage());
        }
    }

    Map<String, String> action(String instanceId, String actionId) {
        byte[] key = bytes(actionKey(instanceId, actionId));
        KvClient.RangeResult result = kv.range(key, prefixEnd(key), 1, 0, false, false);
        if (result.kvs().isEmpty()) {
            return Map.of();
        }
        return parseAction(result.kvs().get(0).value());
    }

    Map<String, Map<String, String>> actions(String instanceId) {
        Map<String, Map<String, String>> values = new LinkedHashMap<>();
        String prefix = instancePrefix(instanceId) + ":action:";
        for (KvClient.KeyValue entry : scan(prefix)) {
            String key = new String(entry.key(), StandardCharsets.UTF_8);
            String actionId = key.substring(prefix.length());
            values.put(actionId, parseAction(entry.value()));
        }
        return values;
    }

    private Map<String, String> parseAction(byte[] value) {
        try {
            JsonNode node = MAPPER.readTree(value);
            Map<String, String> values = new TreeMap<>();
            for (var field : node.properties()) {
                JsonNode fieldValue = field.getValue();
                if (fieldValue != null && !fieldValue.isNull()) {
                    values.put(field.getKey(), fieldValue.stringValue());
                }
            }
            return values;
        } catch (RuntimeException e) {
            log.warn("ignoring corrupt coord workflow sidecar action entry: {}", e.getMessage());
            return Map.of();
        }
    }

    private List<KvClient.KeyValue> scan(String prefix) {
        byte[] start = bytes(prefix);
        return kv.range(start, prefixEnd(start), 0, 0, false, false).kvs();
    }

    private String instancePrefix(String instanceId) {
        return namespace + ":workflow:sidecar:" + instanceId;
    }

    private String formKey(String instanceId, String roundId, String actionId) {
        return instancePrefix(instanceId) + ":form:" + roundId + ":" + actionId;
    }

    private String actionKey(String instanceId, String actionId) {
        return instancePrefix(instanceId) + ":action:" + actionId;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] prefixEnd(byte[] prefix) {
        byte[] end = prefix.clone();
        for (int i = end.length - 1; i >= 0; i--) {
            if ((end[i] & 0xFF) != 0xFF) {
                end[i]++;
                return java.util.Arrays.copyOf(end, i + 1);
            }
        }
        return new byte[0];
    }

    /** 最近一次 coord 响应中的服务端时间；只增不减。 */
    static final class ServerClock {
        private final AtomicReference<Instant> latest = new AtomicReference<>();

        void observe(long epochMillis) {
            if (epochMillis <= 0) {
                return;
            }
            latest.accumulateAndGet(
                    Instant.ofEpochMilli(epochMillis),
                    (current, candidate) ->
                            current == null || candidate.isAfter(current) ? candidate : current);
        }

        void observe(Instant instant) {
            if (instant != null) {
                observe(instant.toEpochMilli());
            }
        }

        Instant now() {
            Instant current = latest.get();
            return current == null ? Instant.now() : current;
        }
    }
}
