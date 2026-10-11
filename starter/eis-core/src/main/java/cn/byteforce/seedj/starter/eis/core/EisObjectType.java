package cn.byteforce.seedj.starter.eis.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 对象类型描述：逻辑类型名 ↔ 物理表 + 领域列 + 敏感列（读投影剔除面）。
 *
 * <p>由应用（域服务）显式注册；表名/列名在注册时做标识符白名单校验
 * （{@code ^[a-z_][a-z0-9_]*$}），杜绝 SQL 注入面。敏感列（如凭证散列）
 * 在查询投影中一律剔除，且不出现在事件/日志（EIS 敏感字段策略）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class EisObjectType {

    private static final Pattern IDENTIFIER = Pattern.compile("^[a-z_][a-z0-9_]*$");

    private final String objectType;

    private final String tableName;

    private final Map<String, EisObjectField> fields;

    private final Set<String> sensitiveFields;

    private EisObjectType(Builder builder) {
        this.objectType = builder.objectType;
        this.tableName = builder.tableName == null ? builder.objectType : builder.tableName;
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(builder.fields));
        this.sensitiveFields = Collections.unmodifiableSet(new LinkedHashSet<>(builder.sensitiveFields));
    }

    /**
     * 创建构造器。
     *
     * @param objectType 逻辑类型名（如 {@code iam_user_account}）
     * @return 构造器
     */
    public static Builder builder(String objectType) {
        return new Builder(objectType);
    }

    /**
     * 获取逻辑类型名。
     *
     * @return 对象类型
     */
    public String getObjectType() {
        return objectType;
    }

    /**
     * 获取物理表名。
     *
     * @return 表名
     */
    public String getTableName() {
        return tableName;
    }

    /**
     * 获取领域字段列表（注册顺序）。
     *
     * @return 字段列表
     */
    public List<EisObjectField> getFields() {
        return new ArrayList<>(fields.values());
    }

    /**
     * 按名取字段。
     *
     * @param name 列名
     * @return 字段；未注册为 {@code null}
     */
    public EisObjectField getField(String name) {
        return fields.get(name);
    }

    /**
     * 获取敏感列集合（读投影剔除）。
     *
     * @return 敏感列集合
     */
    public Set<String> getSensitiveFields() {
        return sensitiveFields;
    }

    /**
     * 对象类型构造器。
     */
    public static final class Builder {

        private final String objectType;

        private String tableName;

        private final Map<String, EisObjectField> fields = new LinkedHashMap<>();

        private final Set<String> sensitiveFields = new LinkedHashSet<>();

        private Builder(String objectType) {
            this.objectType = requireIdentifier(objectType, "objectType");
        }

        /**
         * 指定物理表名（缺省与类型名相同）。
         *
         * @param tableName 表名
         * @return 本构造器
         */
        public Builder table(String tableName) {
            this.tableName = requireIdentifier(tableName, "table");
            return this;
        }

        /**
         * 追加领域字段。
         *
         * @param name 列名
         * @param kind 字段类型
         * @return 本构造器
         */
        public Builder field(String name, EisFieldKind kind) {
            String checked = requireIdentifier(name, "field");
            if (fields.containsKey(checked)) {
                throw new IllegalArgumentException("重复字段: " + checked);
            }
            fields.put(checked, new EisObjectField(checked, kind));
            return this;
        }

        /**
         * 标记敏感列。
         *
         * @param name 列名（须已注册为字段）
         * @return 本构造器
         */
        public Builder sensitive(String name) {
            String checked = requireIdentifier(name, "sensitive");
            if (!fields.containsKey(checked)) {
                throw new IllegalArgumentException("敏感列未注册为字段: " + checked);
            }
            sensitiveFields.add(checked);
            return this;
        }

        /**
         * 构建。
         *
         * @return 对象类型
         */
        public EisObjectType build() {
            if (fields.isEmpty()) {
                throw new IllegalArgumentException("对象类型 " + objectType + " 未注册任何领域字段");
            }
            return new EisObjectType(this);
        }

        private static String requireIdentifier(String value, String what) {
            if (value == null || !IDENTIFIER.matcher(value).matches()) {
                throw new IllegalArgumentException(what + " 非法标识符: " + value);
            }
            return value;
        }
    }
}
