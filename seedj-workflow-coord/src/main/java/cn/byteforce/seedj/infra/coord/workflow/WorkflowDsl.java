package cn.byteforce.seedj.infra.coord.workflow;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** coord 原生 DSL 的 JSON 构造辅助（固定键序、ISO8601 duration）。 */
final class WorkflowDsl {

    private WorkflowDsl() {}

    static String iso(Duration duration) {
        return duration.toString();
    }

    static Map<String, Object> timeout(Duration duration) {
        Map<String, Object> node = new TreeMap<>();
        node.put("after", iso(duration));
        return node;
    }

    static Map<String, Object> retry(
            int limit, Duration delay, String backoff, Duration maxDelay, double multiplier) {
        Map<String, Object> node = new TreeMap<>();
        node.put("limit", limit);
        node.put("delay", iso(delay));
        if (backoff != null) {
            node.put("backoff", backoff);
        }
        if (maxDelay != null) {
            node.put("maxDelay", iso(maxDelay));
        }
        node.put("multiplier", multiplier);
        return node;
    }

    static Map<String, Object> sortedStrings(Map<String, String> map) {
        return map == null ? Map.of() : new TreeMap<>(map);
    }

    static List<String> strings(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
