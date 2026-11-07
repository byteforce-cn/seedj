package cn.byteforce.seedj.infra.local.domain;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.AppendResult;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.ObjectSchemaRegistry;
import cn.byteforce.seedj.domain.ObjectVersion;
import cn.byteforce.seedj.domain.Page;
import cn.byteforce.seedj.domain.PageCursor;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.domain.ScopePredicates;
import cn.byteforce.seedj.domain.Sealing;
import cn.byteforce.seedj.domain.SortDirection;
import cn.byteforce.seedj.domain.TemporalFilter;
import cn.byteforce.seedj.domain.TemporalQuery;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.domain.TemporalSearch;
import cn.byteforce.seedj.domain.TemporalStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存双时间轴对象存储（单进程，不跨服务共享）。
 *
 * <p>语义与 PostgreSQL 实现一致：封口/追加都委托 {@link Sealing}， 每个对象的版本列表独立加锁，保证同一对象的封口与追加原子。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class InMemoryTemporalStore implements TemporalStore {

    private final Clock clock;

    private final ObjectSchemaRegistry schemas;

    private final Map<Key, List<TemporalRow>> rows = new ConcurrentHashMap<>();

    /**
     * 构造内存存储（无 schema：列表查询不允许过滤/排序）。
     *
     * @param clock 取时入口
     */
    public InMemoryTemporalStore(Clock clock) {
        this(clock, ObjectSchemaRegistry.empty());
    }

    /**
     * 构造内存存储。
     *
     * @param clock 取时入口
     * @param schemas 对象 schema 注册表
     */
    public InMemoryTemporalStore(Clock clock, ObjectSchemaRegistry schemas) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.schemas = Objects.requireNonNull(schemas, "schemas");
    }

    @Override
    public Optional<TemporalRow> find(
            String objectType, UUID id, TemporalQuery query, ScopePredicate scope) {
        Objects.requireNonNull(objectType, "objectType");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(scope, "scope");
        List<TemporalRow> versions = rows.get(new Key(objectType, id));
        if (versions == null) {
            return Optional.empty();
        }
        synchronized (versions) {
            Optional<TemporalRow> hit =
                    versions.stream()
                            .filter(row -> matches(row.version(), query))
                            .max(Comparator.comparingLong(row -> row.version().version()))
                            .filter(row -> matchesScope(row, scope));
            if (hit.isPresent()
                    && query.minVersion() != null
                    && hit.get().version().version() < query.minVersion()) {
                long readable = hit.get().version().version();
                throw new ContractException(
                        ContractErrorCode.READ_MODEL_NOT_READY,
                        "readable version " + readable + " is below required " + query.minVersion(),
                        Map.of("minVersion", query.minVersion(), "readableVersion", readable),
                        null);
            }
            return hit;
        }
    }

    @Override
    public AppendResult append(AppendRequest request) {
        Objects.requireNonNull(request, "request");
        List<TemporalRow> versions =
                rows.computeIfAbsent(
                        new Key(request.objectType(), request.id()), key -> new ArrayList<>());
        synchronized (versions) {
            TemporalRow current =
                    versions.stream()
                            .filter(row -> row.version().supersededAt() == null)
                            .findFirst()
                            .orElse(null);
            requireExpectedVersion(request, current);
            AppendResult result = Sealing.next(request, current, clock.now());
            result.superseded().ifPresent(old -> replace(versions, old));
            versions.add(result.appended());
            return result;
        }
    }

    private static void requireExpectedVersion(AppendRequest request, TemporalRow current) {
        Long expected = request.expectedVersion();
        if (expected == null) {
            return;
        }
        long actual = current == null ? 0 : current.version().version();
        if (expected.longValue() != actual) {
            throw new ContractException(
                    ContractErrorCode.VERSION_CONFLICT,
                    "expected version " + expected + " but current is " + actual,
                    Map.of("expected_version", expected, "current_version", actual),
                    null);
        }
    }

    @Override
    public Page<TemporalRow> search(TemporalSearch search, ScopePredicate scope) {
        Objects.requireNonNull(search, "search");
        Objects.requireNonNull(scope, "scope");
        ObjectSchema schema = schemas.find(search.objectType()).orElse(null);
        Map<String, Object> filters = typedFilters(search, schema);
        String sortField = typedSortField(search.sortField(), schema);
        if (!search.filters().isEmpty()) {
            requireSchema(search.objectType(), schema);
            requireFilterable(search.filters(), schema);
        }

        List<TemporalRow> matched = new ArrayList<>();
        rows.forEach(
                (key, versions) -> {
                    if (!key.objectType().equals(search.objectType())) {
                        return;
                    }
                    synchronized (versions) {
                        versions.stream()
                                .filter(
                                        row ->
                                                matches(
                                                        row.version(),
                                                        search.asOf(),
                                                        search.recordedAsOf()))
                                .max(Comparator.comparingLong(row -> row.version().version()))
                                .filter(row -> matchesScope(row, scope))
                                .filter(row -> matchesFilters(row, filters))
                                .filter(row -> matchesFilters(row, search.filters(), schema))
                                .ifPresent(matched::add);
                    }
                });
        matched.sort(comparator(sortField, search.direction()));
        long total = matched.size();
        List<TemporalRow> visible = matched;
        if (search.cursor() != null) {
            visible =
                    matched.stream()
                            .filter(
                                    row ->
                                            afterCursor(
                                                    row,
                                                    search.cursor(),
                                                    sortField,
                                                    search.direction(),
                                                    schema))
                            .toList();
        }
        int from = (int) Math.min(search.page().offset(), visible.size());
        int to = Math.min(from + search.page().size(), visible.size());
        boolean hasMore = to < visible.size();
        List<TemporalRow> items = List.copyOf(visible.subList(from, to));
        PageCursor next =
                hasMore && !items.isEmpty()
                        ? cursorOf(items.get(items.size() - 1), sortField)
                        : null;
        return new Page<>(items, total, search.page().page(), search.page().size(), next);
    }

    private Map<String, Object> typedFilters(TemporalSearch search, ObjectSchema schema) {
        if (search.equals().isEmpty()) {
            return Map.of();
        }
        requireSchema(search.objectType(), schema);
        Map<String, Object> filters = new java.util.LinkedHashMap<>();
        search.equals()
                .forEach(
                        (field, value) -> {
                            if (!schema.filterableFields().contains(field)) {
                                throw new ContractException(
                                        ContractErrorCode.INVALID_PAYLOAD,
                                        "unknown or non-filterable field: " + field);
                            }
                            filters.put(field, schema.requireField(field).type().coerce(value));
                        });
        return filters;
    }

    private String typedSortField(String field, ObjectSchema schema) {
        if (field == null) {
            return null;
        }
        requireSchema(null, schema);
        if (!schema.sortableFields().contains(field)) {
            throw new ContractException(
                    ContractErrorCode.INVALID_PAYLOAD, "unknown or non-sortable field: " + field);
        }
        return field;
    }

    private static void requireSchema(String objectType, ObjectSchema schema) {
        if (schema == null) {
            throw new ContractException(
                    ContractErrorCode.INVALID_PAYLOAD, "object type has no schema: " + objectType);
        }
    }

    private static boolean matchesFilters(TemporalRow row, Map<String, Object> filters) {
        for (Map.Entry<String, Object> filter : filters.entrySet()) {
            Object actual = row.fields().get(filter.getKey());
            if (!Objects.equals(actual, filter.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesFilters(
            TemporalRow row, java.util.List<TemporalFilter> filters, ObjectSchema schema) {
        for (TemporalFilter filter : filters) {
            if (!matchesFilter(row.fields(), filter, schema)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesFilter(
            Map<String, Object> fields, TemporalFilter filter, ObjectSchema schema) {
        return switch (filter) {
            case TemporalFilter.Eq eq -> {
                Object value = typed(schema, eq.field(), eq.value());
                yield value != null && Objects.equals(fields.get(eq.field()), value);
            }
            case TemporalFilter.Ne ne -> {
                Object value = typed(schema, ne.field(), ne.value());
                Object actual = fields.get(ne.field());
                yield value != null && actual != null && !Objects.equals(actual, value);
            }
            case TemporalFilter.In in -> {
                Object actual = fields.get(in.field());
                if (actual == null || in.values().isEmpty()) {
                    yield false;
                }
                yield in.values().stream()
                        .map(value -> typed(schema, in.field(), value))
                        .anyMatch(actual::equals);
            }
            case TemporalFilter.Gt gt -> compare(fields, gt.field(), gt.value(), schema) > 0;
            case TemporalFilter.Gte gte -> compare(fields, gte.field(), gte.value(), schema) >= 0;
            case TemporalFilter.Lt lt -> compare(fields, lt.field(), lt.value(), schema) < 0;
            case TemporalFilter.Lte lte -> compare(fields, lte.field(), lte.value(), schema) <= 0;
            case TemporalFilter.Between between -> {
                Object actual = fields.get(between.field());
                if (actual == null) {
                    yield false;
                }
                Object lower = typed(schema, between.field(), between.lower());
                Object upper = typed(schema, between.field(), between.upper());
                yield compare(actual, lower) >= 0 && compare(actual, upper) <= 0;
            }
            case TemporalFilter.Like like -> {
                Object actual = fields.get(like.field());
                yield actual != null
                        && likePattern(like.pattern()).matcher(String.valueOf(actual)).matches();
            }
            case TemporalFilter.And and ->
                    and.parts().stream().allMatch(part -> matchesFilter(fields, part, schema));
            case TemporalFilter.Or or ->
                    or.parts().stream().anyMatch(part -> matchesFilter(fields, part, schema));
            case TemporalFilter.Not not -> !matchesFilter(fields, not.part(), schema);
        };
    }

    private static int compare(
            Map<String, Object> fields, String field, Object expected, ObjectSchema schema) {
        Object actual = fields.get(field);
        if (actual == null) {
            return -1;
        }
        return compare(actual, typed(schema, field, expected));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object left, Object right) {
        if (left == null || right == null) {
            return left == null ? (right == null ? 0 : -1) : 1;
        }
        if (left instanceof Comparable comparable && left.getClass().isInstance(right)) {
            return comparable.compareTo(right);
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    private static Object typed(ObjectSchema schema, String field, Object value) {
        return schema.requireField(field).type().coerce(value);
    }

    private static java.util.regex.Pattern likePattern(String pattern) {
        StringBuilder regex = new StringBuilder("^");
        for (char c : pattern.toCharArray()) {
            switch (c) {
                case '%' -> regex.append(".*");
                case '_' -> regex.append('.');
                case '\\' -> regex.append("\\\\");
                default -> regex.append(java.util.regex.Pattern.quote(String.valueOf(c)));
            }
        }
        return java.util.regex.Pattern.compile(
                regex.append('$').toString(), java.util.regex.Pattern.DOTALL);
    }

    private static void requireFilterable(
            java.util.List<TemporalFilter> filters, ObjectSchema schema) {
        for (TemporalFilter filter : filters) {
            switch (filter) {
                case TemporalFilter.Eq eq -> requireFilterable(eq.field(), schema);
                case TemporalFilter.Ne ne -> requireFilterable(ne.field(), schema);
                case TemporalFilter.In in -> requireFilterable(in.field(), schema);
                case TemporalFilter.Gt gt -> requireFilterable(gt.field(), schema);
                case TemporalFilter.Gte gte -> requireFilterable(gte.field(), schema);
                case TemporalFilter.Lt lt -> requireFilterable(lt.field(), schema);
                case TemporalFilter.Lte lte -> requireFilterable(lte.field(), schema);
                case TemporalFilter.Between between -> requireFilterable(between.field(), schema);
                case TemporalFilter.Like like -> requireFilterable(like.field(), schema);
                case TemporalFilter.And and -> requireFilterable(and.parts(), schema);
                case TemporalFilter.Or or -> requireFilterable(or.parts(), schema);
                case TemporalFilter.Not not ->
                        requireFilterable(java.util.List.of(not.part()), schema);
            }
        }
    }

    private static void requireFilterable(String field, ObjectSchema schema) {
        if (!schema.filterableFields().contains(field)) {
            throw new ContractException(
                    ContractErrorCode.INVALID_PAYLOAD, "unknown or non-filterable field: " + field);
        }
    }

    private static boolean afterCursor(
            TemporalRow row,
            PageCursor cursor,
            String sortField,
            SortDirection direction,
            ObjectSchema schema) {
        if (sortField == null) {
            int idCompare = row.version().id().compareTo(cursor.id());
            return direction == SortDirection.DESC ? idCompare < 0 : idCompare > 0;
        }
        Object actual = row.fields().get(sortField);
        Object expected =
                cursor.sortValue() == null
                        ? null
                        : schema.requireField(sortField).type().coerce(cursor.sortValue());
        if (actual == null || expected == null) {
            return false;
        }
        int valueCompare = compare(actual, expected);
        if (valueCompare == 0) {
            int idCompare = row.version().id().compareTo(cursor.id());
            return direction == SortDirection.DESC ? idCompare < 0 : idCompare > 0;
        }
        return direction == SortDirection.DESC ? valueCompare < 0 : valueCompare > 0;
    }

    private static PageCursor cursorOf(TemporalRow row, String sortField) {
        if (sortField == null) {
            return new PageCursor(null, null, row.version().id());
        }
        Object value = row.fields().get(sortField);
        return value == null ? null : new PageCursor(sortField, value, row.version().id());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Comparator<TemporalRow> comparator(String field, SortDirection direction) {
        Comparator<TemporalRow> comparator;
        if (field == null) {
            comparator = Comparator.comparing(row -> row.version().id());
        } else {
            comparator =
                    (left, right) -> {
                        Object a = left.fields().get(field);
                        Object b = right.fields().get(field);
                        if (a == null || b == null) {
                            return a == null ? (b == null ? 0 : 1) : -1;
                        }
                        if (a instanceof Comparable comparable && a.getClass().isInstance(b)) {
                            return comparable.compareTo(b);
                        }
                        return String.valueOf(a).compareTo(String.valueOf(b));
                    };
        }
        return direction == SortDirection.DESC ? comparator.reversed() : comparator;
    }

    private static boolean matches(
            ObjectVersion version, java.time.Instant asOf, java.time.Instant recordedAsOf) {
        return matches(version, new TemporalQuery(asOf, recordedAsOf, null));
    }

    @Override
    public List<TemporalRow> history(String objectType, UUID id, ScopePredicate scope) {
        Objects.requireNonNull(scope, "scope");
        List<TemporalRow> versions = rows.get(new Key(objectType, id));
        if (versions == null) {
            return List.of();
        }
        synchronized (versions) {
            return versions.stream()
                    .filter(row -> matchesScope(row, scope))
                    .sorted(Comparator.comparing(row -> row.version().recordedAt()))
                    .toList();
        }
    }

    private boolean matchesScope(TemporalRow row, ScopePredicate scope) {
        if (scope instanceof ScopePredicate.All) {
            return true;
        }
        if (scope instanceof ScopePredicate.None) {
            return false;
        }
        ObjectSchema schema =
                schemas.find(row.objectType())
                        .orElseThrow(
                                () ->
                                        new ContractException(
                                                ContractErrorCode.INVALID_PAYLOAD,
                                                "object type has no schema: " + row.objectType()));
        return ScopePredicates.matches(row, scope, schema);
    }

    /** 清空全部对象（测试用）。 */
    public void clear() {
        rows.clear();
    }

    private static boolean matches(ObjectVersion version, TemporalQuery query) {
        if (query.asOf() == null && query.recordedAsOf() == null) {
            return version.isCurrent();
        }
        if (query.asOf() != null && !version.validAt(query.asOf())) {
            return false;
        }
        return query.recordedAsOf() == null || version.recordedAt(query.recordedAsOf());
    }

    private static void replace(List<TemporalRow> versions, TemporalRow sealed) {
        for (int i = 0; i < versions.size(); i++) {
            TemporalRow candidate = versions.get(i);
            if (candidate.version().id().equals(sealed.version().id())
                    && candidate.version().recordedAt().equals(sealed.version().recordedAt())) {
                versions.set(i, sealed);
                return;
            }
        }
        throw new IllegalStateException("sealed row not found: " + sealed.version().id());
    }

    private record Key(String objectType, UUID id) {}
}
