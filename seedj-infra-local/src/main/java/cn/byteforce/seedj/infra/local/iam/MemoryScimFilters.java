package cn.byteforce.seedj.infra.local.iam;

import cn.byteforce.seedj.iam.ScimComparisonOperator;
import cn.byteforce.seedj.iam.ScimFilter;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;

/** In-memory SCIM filter evaluation over a whitelisted attribute map. */
final class MemoryScimFilters {

    private MemoryScimFilters() {}

    static boolean matches(ScimFilter filter, Map<String, Object> attributes) {
        if (filter == null) {
            return true;
        }
        if (filter instanceof ScimFilter.And and) {
            return and.filters().stream().allMatch(child -> matches(child, attributes));
        }
        if (filter instanceof ScimFilter.Or or) {
            return or.filters().stream().anyMatch(child -> matches(child, attributes));
        }
        if (filter instanceof ScimFilter.Not not) {
            return !matches(not.filter(), attributes);
        }
        if (filter instanceof ScimFilter.Present present) {
            Object value = value(attributes, present.attribute());
            return value != null && (!(value instanceof Collection<?> c) || !c.isEmpty());
        }
        if (filter instanceof ScimFilter.Compare compare) {
            Object value = value(attributes, compare.attribute());
            return compare(compare.operator(), value, compare.value());
        }
        return false;
    }

    private static Object value(Map<String, Object> attributes, String path) {
        Object direct = attributes.get(path);
        if (direct != null) {
            return direct;
        }
        // Allow simple nested lookup for attributes stored as maps.
        Object current = attributes;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(part);
        }
        return current;
    }

    private static boolean compare(ScimComparisonOperator operator, Object left, Object right) {
        if (operator == ScimComparisonOperator.EQ) {
            return eq(left, right);
        }
        if (operator == ScimComparisonOperator.NE) {
            return !eq(left, right);
        }
        if (left == null || right == null) {
            return false;
        }
        if (left instanceof Collection<?> collection) {
            return collection.stream().anyMatch(item -> compare(operator, item, right));
        }
        String l = String.valueOf(left);
        String r = String.valueOf(right);
        return switch (operator) {
            case CO -> l.toLowerCase(Locale.ROOT).contains(r.toLowerCase(Locale.ROOT));
            case SW -> l.toLowerCase(Locale.ROOT).startsWith(r.toLowerCase(Locale.ROOT));
            case EW -> l.toLowerCase(Locale.ROOT).endsWith(r.toLowerCase(Locale.ROOT));
            case GT -> compareValues(left, right) > 0;
            case LT -> compareValues(left, right) < 0;
            case GE -> compareValues(left, right) >= 0;
            case LE -> compareValues(left, right) <= 0;
            default -> false;
        };
    }

    private static boolean eq(Object left, Object right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left instanceof Collection<?> collection) {
            return collection.stream().anyMatch(item -> eq(item, right));
        }
        if (left instanceof Boolean || right instanceof Boolean) {
            return String.valueOf(left).equalsIgnoreCase(String.valueOf(right));
        }
        if (left instanceof Number l && right instanceof Number r) {
            return Double.compare(l.doubleValue(), r.doubleValue()) == 0;
        }
        return String.valueOf(left).equals(String.valueOf(right));
    }

    private static int compareValues(Object left, Object right) {
        if (left instanceof Number l && right instanceof Number r) {
            return Double.compare(l.doubleValue(), r.doubleValue());
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }
}
