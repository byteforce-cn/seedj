package cn.byteforce.seedj.module;

import cn.byteforce.seedj.core.security.ScopeKeyCatalog;
import cn.byteforce.seedj.read.ReadModel;
import cn.byteforce.seedj.read.ReadModelRegistry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 冻结后的模块注册表：schema、读模型、查询与请求事实 key 的唯一来源。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class ObjectModuleRegistry implements ReadModelRegistry {

    private final Map<String, ReadModel> models;

    private final List<QueryDeclaration> queries;

    private final ScopeKeyCatalog scopeKeys;

    ObjectModuleRegistry(Map<String, ReadModel> models, List<QueryDeclaration> queries,
                         ScopeKeyCatalog scopeKeys) {
        this.models = Collections.unmodifiableMap(new LinkedHashMap<>(models));
        this.queries = List.copyOf(queries);
        this.scopeKeys = scopeKeys;
    }

    @Override
    public Optional<ReadModel> find(String objectType) {
        return Optional.ofNullable(objectType == null ? null : models.get(objectType));
    }

    /**
     * 按类型取读模型，未知即失败。
     *
     * @param objectType 对象类型
     * @return 读模型
     */
    public ReadModel require(String objectType) {
        return find(objectType).orElseThrow(
                () -> new IllegalArgumentException("unknown object type: " + objectType));
    }

    @Override
    public List<ReadModel> all() {
        return List.copyOf(models.values());
    }

    /**
     * 全部查询声明。
     *
     * @return 不可变列表
     */
    public List<QueryDeclaration> queries() {
        return queries;
    }

    /**
     * 冻结的请求事实 key。
     *
     * @return key 目录
     */
    public ScopeKeyCatalog scopeKeys() {
        return scopeKeys;
    }
}
