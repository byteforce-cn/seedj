package cn.byteforce.seedj.codegen.processor;

import java.util.List;
import java.util.Objects;

/**
 * 一个业务对象的 schema 模型：对象类型、表名与字段列表。
 *
 * @param objectType 对象类型
 * @param table 物理表名
 * @param packageName 生成类所在的包（与 record 相同）
 * @param recordName 源 record 简单类名
 * @param view 是否生成默认读视图
 * @param fields 字段（保持 record 声明顺序）
 * @author ByteForce
 * @since 1.0.0
 */
record ObjectModel(
        String objectType,
        String table,
        String packageName,
        String recordName,
        boolean view,
        List<FieldModel> fields) {

    ObjectModel {
        Objects.requireNonNull(objectType, "objectType");
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(packageName, "packageName");
        Objects.requireNonNull(recordName, "recordName");
        fields = List.copyOf(fields);
    }

    /**
     * 生成类名：{@code <Record>Schema}。
     *
     * @return 生成类简单名
     */
    String schemaClassName() {
        return recordName + "Schema";
    }

    /**
     * 生成读视图类名：{@code <Record>View}。
     *
     * @return 生成类简单名
     */
    String viewClassName() {
        return recordName + "View";
    }
}
