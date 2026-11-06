package cn.byteforce.seedj.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 类型化查询模板：业务只写 {@link #map(TemporalRow)}，分页/过滤/时点由模板处理。
 *
 * <p>通用 {@code /objects} 是内部/服务面；业务读模型应继承本类，得到自己的视图类型与读权限。
 * 默认的 {@link #readable(TemporalRow)} 会剔除 schema 声明的写入专用字段，避免敏感字段因为
 * 新增查询而泄漏。</p>
 *
 * @param <T> 视图类型
 * @author ByteForce
 * @since 1.0.0
 */
public abstract class TemporalQueryRepository<T> {

    private final TemporalStore store;

    private final ObjectSchema schema;

    /**
     * 构造模板。
     *
     * @param store  对象存储
     * @param schema 对象 schema
     */
    protected TemporalQueryRepository(TemporalStore store, ObjectSchema schema) {
        this.store = Objects.requireNonNull(store, "store");
        this.schema = Objects.requireNonNull(schema, "schema");
    }

    /**
     * 按 ID 与时点查询。
     *
     * @param id    对象 ID
     * @param query 时点与版本约束
     * @return 视图
     */
    public Optional<T> find(UUID id, TemporalQuery query) {
        return store.find(schema.objectType(), id, query).map(this::map);
    }

    /**
     * 查询当前版本。
     *
     * @param id 对象 ID
     * @return 视图
     */
    public Optional<T> current(UUID id) {
        return find(id, TemporalQuery.current());
    }

    /**
     * 列表查询。
     *
     * @param search 过滤/排序/分页/时点
     * @return 视图页
     */
    public Page<T> search(TemporalSearch search) {
        Objects.requireNonNull(search, "search");
        if (!schema.objectType().equals(search.objectType())) {
            throw new IllegalArgumentException("repository is bound to "
                    + schema.objectType() + ", got " + search.objectType());
        }
        Page<TemporalRow> page = store.search(search);
        List<T> items = page.items().stream().map(this::map).toList();
        return new Page<>(items, page.total(), page.page(), page.size());
    }

    /**
     * 行 → 视图。
     *
     * @param row 对象行（写入专用字段已剔除）
     * @return 视图
     */
    protected abstract T map(TemporalRow row);

    /**
     * 剔除写入专用字段后的行；子类可覆盖以追加自己的脱敏规则。
     *
     * @param row 原始行
     * @return 可读行
     */
    protected TemporalRow readable(TemporalRow row) {
        if (schema.writeOnlyFields().isEmpty()) {
            return row;
        }
        Map<String, Object> fields = new java.util.LinkedHashMap<>(row.fields());
        schema.writeOnlyFields().forEach(fields::remove);
        return new TemporalRow(row.objectType(), row.version(), fields);
    }

    /**
     * 对外暴露的 schema（只读）。
     *
     * @return schema
     */
    protected final ObjectSchema schema() {
        return schema;
    }
}
