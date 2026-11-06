package cn.byteforce.seedj.module;

import cn.byteforce.seedj.core.security.ScopeKey;
import cn.byteforce.seedj.core.security.ScopeKeyCatalog;
import cn.byteforce.seedj.domain.FieldDefinition;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.Masker;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.Privacy;
import cn.byteforce.seedj.read.ReadModel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 实体声明 DSL：字段、读权限、查询与请求事实 key。
 *
 * <p>{@link #freeze()} 一次性校验并构建 {@link ObjectModuleRegistry}；重复对象/路径、
 * 缺读权限、未知或不可排序的查询排序字段都让启动失败。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class ObjectModules {

    private final List<ObjectSpec> objects = new ArrayList<>();

    private ObjectModules() {
    }

    /**
     * 新建声明。
     *
     * @return 空 DSL
     */
    public static ObjectModules create() {
        return new ObjectModules();
    }

    /**
     * 声明一个对象。
     *
     * @param objectType 对象类型
     * @param tableName  物理表名
     * @return 对象声明
     */
    public ObjectSpec object(String objectType, String tableName) {
        ObjectSpec spec = new ObjectSpec(objectType, tableName);
        objects.add(spec);
        return spec;
    }

    /**
     * 冻结为注册表。
     *
     * @return 不可变注册表
     */
    public ObjectModuleRegistry freeze() {
        Map<String, ReadModel> models = new LinkedHashMap<>();
        List<QueryDeclaration> queries = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        List<ScopeKey<?>> keys = new ArrayList<>();
        for (ObjectSpec object : objects) {
            if (models.containsKey(object.objectType)) {
                throw new IllegalArgumentException(
                        "duplicate object type: " + object.objectType);
            }
            if (object.readPermission == null || object.readPermission.isBlank()) {
                throw new IllegalArgumentException(
                        "object " + object.objectType + " must declare readPermission");
            }
            if (object.fields.isEmpty()) {
                throw new IllegalArgumentException(
                        "object " + object.objectType + " must declare at least one field");
            }
            ObjectSchema schema = object.schema();
            models.put(object.objectType,
                    new ReadModel(object.objectType, schema, object.readPermission));
            keys.addAll(object.contextKeys);
            for (QuerySpec query : object.queries) {
                ObjectSchema fieldSchema = schema;
                if (query.sortField != null) {
                    FieldDefinition sort = fieldSchema.field(query.sortField).orElseThrow(
                            () -> new IllegalArgumentException("unknown query sort field: "
                                    + query.sortField + " on " + object.objectType));
                    if (!sort.sortable()) {
                        throw new IllegalArgumentException("query sort field is not sortable: "
                                + query.sortField + " on " + object.objectType);
                    }
                }
                if (!query.path.startsWith("/")) {
                    throw new IllegalArgumentException(
                            "query path must start with /: " + query.path);
                }
                if (!paths.add(query.path)) {
                    throw new IllegalArgumentException("duplicate query path: " + query.path);
                }
                String permission = query.permission == null || query.permission.isBlank()
                        ? object.readPermission : query.permission;
                queries.add(new QueryDeclaration(object.objectType, query.viewType,
                        query.path, permission, query.sortField));
            }
        }
        return new ObjectModuleRegistry(models, queries, ScopeKeyCatalog.of(keys));
    }

    /** 对象声明。 */
    public static final class ObjectSpec {

        private final String objectType;

        private final String tableName;

        private String readPermission;

        private final List<FieldSpec> fields = new ArrayList<>();

        private final List<QuerySpec> queries = new ArrayList<>();

        private final List<ScopeKey<?>> contextKeys = new ArrayList<>();

        private ObjectSpec(String objectType, String tableName) {
            this.objectType = objectType;
            this.tableName = tableName;
        }

        /**
         * 对象级读权限。
         *
         * @param permission 权限名
         * @return 本对象
         */
        public ObjectSpec readPermission(String permission) {
            this.readPermission = permission;
            return this;
        }

        /**
         * 声明一个请求事实 key。
         *
         * @param key 声明 key
         * @return 本对象
         */
        public ObjectSpec contextKey(ScopeKey<?> key) {
            contextKeys.add(Objects.requireNonNull(key, "key"));
            return this;
        }

        /**
         * 声明字段。
         *
         * @param name 字段名
         * @param type 字段类型
         * @return 字段声明
         */
        public FieldSpec field(String name, FieldType type) {
            FieldSpec field = new FieldSpec(this, name, type);
            fields.add(field);
            return field;
        }

        /**
         * 声明类型化查询。
         *
         * @param viewType 视图类型
         * @return 查询声明
         */
        public QuerySpec query(Class<?> viewType) {
            QuerySpec query = new QuerySpec(this, viewType);
            queries.add(query);
            return query;
        }

        private ObjectSchema schema() {
            ObjectSchema.Builder builder = ObjectSchema.builder(objectType, tableName);
            for (FieldSpec field : fields) {
                builder.field(field.definition());
            }
            return builder.build();
        }
    }

    /** 字段声明。 */
    public static final class FieldSpec {

        private final ObjectSpec owner;

        private final String name;

        private final FieldType type;

        private boolean required;

        private boolean writeOnly;

        private boolean filterable;

        private boolean sortable;

        private boolean indexed;

        private Privacy privacy = Privacy.PUBLIC;

        private Masker masker;

        private FieldSpec(ObjectSpec owner, String name, FieldType type) {
            this.owner = owner;
            this.name = name;
            this.type = type;
        }

        public FieldSpec required() {
            required = true;
            return this;
        }

        public FieldSpec writeOnly() {
            writeOnly = true;
            return this;
        }

        public FieldSpec filterable() {
            filterable = true;
            return this;
        }

        public FieldSpec sortable() {
            sortable = true;
            return this;
        }

        public FieldSpec indexed() {
            indexed = true;
            return this;
        }

        public FieldSpec privacy(Privacy value) {
            privacy = value == null ? Privacy.PUBLIC : value;
            return this;
        }

        public FieldSpec mask(Masker value) {
            masker = value;
            return this;
        }

        /**
         * 回到对象声明继续加字段。
         *
         * @param fieldName 字段名
         * @param fieldType 字段类型
         * @return 新字段声明
         */
        public FieldSpec field(String fieldName, FieldType fieldType) {
            return owner.field(fieldName, fieldType);
        }

        /**
         * 回到对象声明继续加查询。
         *
         * @param viewType 视图类型
         * @return 查询声明
         */
        public QuerySpec query(Class<?> viewType) {
            return owner.query(viewType);
        }

        private FieldDefinition definition() {
            boolean eventVisible = !writeOnly && privacy.ordinal() < Privacy.SENSITIVE.ordinal();
            return new FieldDefinition(name, type, required, writeOnly, eventVisible, filterable,
                    sortable, indexed, privacy, masker);
        }
    }

    /** 查询声明。 */
    public static final class QuerySpec {

        private final ObjectSpec owner;

        private final Class<?> viewType;

        private String path;

        private String permission;

        private String sortField;

        private QuerySpec(ObjectSpec owner, Class<?> viewType) {
            this.owner = owner;
            this.viewType = Objects.requireNonNull(viewType, "viewType");
        }

        public QuerySpec path(String value) {
            this.path = value;
            return this;
        }

        public QuerySpec permission(String value) {
            this.permission = value;
            return this;
        }

        public QuerySpec sort(String value) {
            this.sortField = value;
            return this;
        }

        /**
         * 回到对象声明继续加字段。
         *
         * @param fieldName 字段名
         * @param fieldType 字段类型
         * @return 新字段声明
         */
        public FieldSpec field(String fieldName, FieldType fieldType) {
            return owner.field(fieldName, fieldType);
        }

        /**
         * 回到对象声明继续加查询。
         *
         * @param nextViewType 视图类型
         * @return 新查询声明
         */
        public QuerySpec query(Class<?> nextViewType) {
            return owner.query(nextViewType);
        }
    }
}
