package cn.byteforce.seedj.module;

import cn.byteforce.seedj.core.security.ScopeKey;
import cn.byteforce.seedj.core.security.ScopeKeyCatalog;
import cn.byteforce.seedj.core.utils.FieldNames;
import cn.byteforce.seedj.cqrs.CommandAgent;
import cn.byteforce.seedj.cqrs.CommandDescriptor;
import cn.byteforce.seedj.cqrs.CommandHandler;
import cn.byteforce.seedj.cqrs.CommandPayloadRedactor;
import cn.byteforce.seedj.cqrs.CommandRegistration;
import cn.byteforce.seedj.cqrs.CommandValidator;
import cn.byteforce.seedj.domain.FieldDefinition;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.Masker;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.Privacy;
import cn.byteforce.seedj.read.ReadModel;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
        ObjectSpec spec = new ObjectSpec(objectType, tableName, null);
        objects.add(spec);
        return spec;
    }

    /**
     * 用已生成的 schema 声明一个对象。
     *
     * <p>字段唯一来源是 {@code @SeedjObject} record 生成的 {@link ObjectSchema}；
     * 再调用 {@link ObjectSpec#field(String, FieldType)} 会直接失败，避免两处声明。</p>
     *
     * @param schema 生成的 schema
     * @return 对象声明
     */
    public ObjectSpec object(ObjectSchema schema) {
        Objects.requireNonNull(schema, "schema");
        ObjectSpec spec = new ObjectSpec(schema.objectType(), schema.tableName(), schema);
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
        List<CommandDeclaration> declarations = new ArrayList<>();
        List<CommandRegistration> registrations = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        Set<String> commandTypes = new HashSet<>();
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
            if (object.schema().fields().isEmpty()) {
                throw new IllegalArgumentException(
                        "object " + object.objectType + " must declare at least one field");
            }
            ObjectSchema schema = object.schema();
            models.put(object.objectType,
                    new ReadModel(object.objectType, schema, object.readPermission));
            keys.addAll(object.contextKeys);
            for (CommandSpec command : object.commands) {
                if (!commandTypes.add(command.type)) {
                    throw new IllegalArgumentException(
                            "duplicate command type: " + command.type);
                }
                if (command.permission == null || command.permission.isBlank()) {
                    throw new IllegalArgumentException(
                            "command " + command.type + " must declare a permission");
                }
                if ((command.handler == null) == (command.agent == null)) {
                    throw new IllegalArgumentException("command " + command.type
                            + " must declare exactly one of handler or agent");
                }
                if (command.agent != null && !command.payloadType.equals(
                        command.agent.commandType())) {
                    throw new IllegalArgumentException("command " + command.type
                            + " payload type " + command.payloadType.getName()
                            + " does not match agent command type "
                            + command.agent.commandType().getName());
                }
                boolean creates = command.createsDeclared
                        ? command.creates
                        : command.agent != null && command.agent.isCreateCommand();
                if (command.agent != null && command.createsDeclared
                        && command.creates != command.agent.isCreateCommand()) {
                    throw new IllegalArgumentException("command " + command.type
                            + " creates flag does not match agent");
                }
                requirePayloadFields(command, schema);
                CommandDescriptor descriptor = CommandDescriptor.builder()
                        .type(command.type)
                        .objectType(object.objectType)
                        .payloadType(command.payloadType)
                        .permission(command.permission)
                        .validator(command.validator)
                        .redactor(command.redactor)
                        .build();
                declarations.add(new CommandDeclaration(object.objectType, command.type,
                        command.payloadType, command.permission, command.handler,
                        command.agent, creates, command.emits, command.commandFields,
                        command.redactedFields));
                registrations.add(command.agent == null
                        ? new CommandRegistration(descriptor, command.handler, schema,
                                command.commandFields, command.redactedFields, command.emits,
                                creates)
                        : CommandRegistration.eventSourced(descriptor, command.agent, schema,
                                command.commandFields, command.redactedFields, command.emits));
            }
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
        return new ObjectModuleRegistry(models, queries, declarations, registrations,
                ScopeKeyCatalog.of(keys));
    }

    private static void requirePayloadFields(CommandSpec command, ObjectSchema schema) {
        if (!command.payloadType.isRecord()) {
            throw new IllegalArgumentException(
                    "command payload must be a record: " + command.payloadType.getName());
        }
        Set<String> allowed = new LinkedHashSet<>(command.commandFields);
        for (FieldDefinition field : schema.fields()) {
            allowed.add(field.name());
        }
        for (RecordComponent component : command.payloadType.getRecordComponents()) {
            String field = FieldNames.snakeCase(component.getName());
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException("command " + command.type
                        + " payload field is not in schema: " + field);
            }
        }
    }

    /** 对象声明。 */
    public static final class ObjectSpec {

        private final String objectType;

        private final String tableName;

        private String readPermission;

        private final List<FieldSpec> fields = new ArrayList<>();

        private final List<QuerySpec> queries = new ArrayList<>();

        private final List<ScopeKey<?>> contextKeys = new ArrayList<>();

        private final List<CommandSpec> commands = new ArrayList<>();

        private final ObjectSchema fixedSchema;

        private ObjectSpec(String objectType, String tableName, ObjectSchema fixedSchema) {
            this.objectType = objectType;
            this.tableName = tableName;
            this.fixedSchema = fixedSchema;
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
            if (fixedSchema != null) {
                throw new IllegalStateException(
                        "object " + objectType + " uses a generated schema; declare fields on"
                                + " the @SeedjObject record instead of ObjectModules.field()");
            }
            FieldSpec field = new FieldSpec(this, name, type);
            fields.add(field);
            return field;
        }

        /**
         * 声明命令。
         *
         * @param type        命令类型
         * @param payloadType 载荷 record
         * @return 命令声明
         */
        public CommandSpec command(String type, Class<?> payloadType) {
            CommandSpec command = new CommandSpec(this, type, payloadType);
            commands.add(command);
            return command;
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
            if (fixedSchema != null) {
                return fixedSchema;
            }
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

        /**
         * 回到对象声明继续加命令。
         *
         * @param commandType 命令类型
         * @param payloadType 载荷类型
         * @return 命令声明
         */
        public CommandSpec command(String commandType, Class<?> payloadType) {
            return owner.command(commandType, payloadType);
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

        /**
         * 回到对象声明继续加命令。
         *
         * @param commandType 命令类型
         * @param payloadType 载荷类型
         * @return 命令声明
         */
        public CommandSpec command(String commandType, Class<?> payloadType) {
            return owner.command(commandType, payloadType);
        }
    }

    /** 命令声明。 */
    public static final class CommandSpec {

        private final ObjectSpec owner;

        private final String type;

        private final Class<?> payloadType;

        private String permission;

        private CommandHandler<?> handler;

        private CommandAgent<?, ?, ?> agent;

        private boolean creates;

        private boolean createsDeclared;

        private List<String> emits = List.of();

        private final Set<String> commandFields = new LinkedHashSet<>();

        private final Set<String> redactedFields = new LinkedHashSet<>();

        private CommandValidator validator = CommandValidator.none();

        private CommandPayloadRedactor redactor = CommandPayloadRedactor.noop();

        private CommandSpec(ObjectSpec owner, String type, Class<?> payloadType) {
            this.owner = owner;
            this.type = type;
            this.payloadType = Objects.requireNonNull(payloadType, "payloadType");
        }

        public CommandSpec permission(String value) {
            this.permission = value;
            return this;
        }

        public CommandSpec handler(CommandHandler<?> value) {
            this.handler = value;
            return this;
        }

        /**
         * 声明为事件溯源命令：执行模型由 agent 提供，handler 必须为空。
         *
         * @param value 命令代理
         * @return 本命令
         */
        public CommandSpec agent(CommandAgent<?, ?, ?> value) {
            this.agent = value;
            return this;
        }

        /**
         * 声明为创建型命令：运行时在对象已存在时失败关闭，避免重复创建或误把更新当创建。
         *
         * @return 本命令
         */
        public CommandSpec creates() {
            this.creates = true;
            this.createsDeclared = true;
            return this;
        }

        public CommandSpec emits(String... eventTypes) {
            this.emits = List.of(eventTypes);
            return this;
        }

        public CommandSpec commandField(String field) {
            this.commandFields.add(field);
            return this;
        }

        /**
         * 声明一个命令专用、且不得落命令记录的敏感字段（如原始证件号）。
         *
         * @param field 字段名
         * @return 本命令
         */
        public CommandSpec redactedCommandField(String field) {
            this.commandFields.add(field);
            this.redactedFields.add(field);
            return this;
        }

        public CommandSpec validator(CommandValidator value) {
            this.validator = value == null ? CommandValidator.none() : value;
            return this;
        }

        public CommandSpec redactor(CommandPayloadRedactor value) {
            this.redactor = value == null ? CommandPayloadRedactor.noop() : value;
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

        /**
         * 回到对象声明继续加命令。
         *
         * @param commandType 命令类型
         * @param nextPayloadType 载荷类型
         * @return 新命令声明
         */
        public CommandSpec command(String commandType, Class<?> nextPayloadType) {
            return owner.command(commandType, nextPayloadType);
        }
    }
}
