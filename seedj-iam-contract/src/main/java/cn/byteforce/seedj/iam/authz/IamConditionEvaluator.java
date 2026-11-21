package cn.byteforce.seedj.iam.authz;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 声明式条件求值器：只读快照、只收窄、不授予。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class IamConditionEvaluator {

    /**
     * 求值条件。
     *
     * @param condition 条件
     * @param snapshot 属性快照
     * @return 结果
     */
    public ConditionOutcome evaluate(IamCondition condition, Map<String, Object> snapshot) {
        List<ConditionOutcome> outcomes = new ArrayList<>(condition.terms().size());
        for (IamConditionTerm term : condition.terms()) {
            outcomes.add(evaluateTerm(term, snapshot == null ? Map.of() : snapshot));
        }
        if (condition.logic().equals("any")) {
            if (outcomes.stream().anyMatch(ConditionOutcome::matched)) {
                return ConditionOutcome.success();
            }
            return outcomes.stream()
                    .filter(outcome -> "ATTRIBUTE_MISSING".equals(outcome.reasonCode()))
                    .findFirst()
                    .orElse(outcomes.get(0));
        }
        return outcomes.stream()
                .filter(outcome -> !outcome.matched())
                .findFirst()
                .orElse(ConditionOutcome.success());
    }

    private ConditionOutcome evaluateTerm(IamConditionTerm term, Map<String, Object> snapshot) {
        PathResult path = resolve(snapshot, term.attribute());
        List<String> candidates =
                path.values().stream().filter(value -> value != null).map(String::valueOf).toList();
        if (!path.present()) {
            return ConditionOutcome.missing(term.attribute());
        }
        if (term.op() == IamConditionOperator.EXISTS) {
            boolean expected = Boolean.parseBoolean(term.values().get(0));
            boolean exists = !candidates.isEmpty();
            return exists == expected
                    ? ConditionOutcome.success()
                    : ConditionOutcome.mismatch(term.attribute());
        }
        if (candidates.isEmpty()) {
            return ConditionOutcome.mismatch(term.attribute());
        }
        boolean matched =
                switch (term.op()) {
                    case EQ, IN -> candidates.stream().anyMatch(term.values()::contains);
                    case NEQ, NOT_IN -> candidates.stream().noneMatch(term.values()::contains);
                    case EXISTS -> throw new IllegalStateException("handled above");
                };
        return matched ? ConditionOutcome.success() : ConditionOutcome.mismatch(term.attribute());
    }

    private static PathResult resolve(Map<String, Object> snapshot, String attribute) {
        List<Object> current = new ArrayList<>();
        current.add(snapshot);
        boolean present = true;
        for (String segment : attribute.split("\\.")) {
            boolean any = segment.endsWith("[*]");
            String key = any ? segment.substring(0, segment.length() - 3) : segment;
            List<Object> next = new ArrayList<>();
            boolean found = false;
            for (Object value : current) {
                if (!(value instanceof Map<?, ?> map) || !map.containsKey(key)) {
                    continue;
                }
                found = true;
                Object child = map.get(key);
                if (any) {
                    if (child instanceof Iterable<?> iterable) {
                        for (Object item : iterable) {
                            next.add(item);
                        }
                    } else if (child != null) {
                        next.add(child);
                    }
                } else {
                    next.add(child);
                }
            }
            if (!found) {
                present = false;
                break;
            }
            current = next;
        }
        return new PathResult(present, current);
    }

    private record PathResult(boolean present, List<Object> values) {}
}
