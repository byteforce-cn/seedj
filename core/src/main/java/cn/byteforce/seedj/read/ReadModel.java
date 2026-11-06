package cn.byteforce.seedj.read;

import cn.byteforce.seedj.domain.ObjectSchema;

import java.util.Objects;

/**
 * 一个对象的读模型声明：对象 schema + 对象级读权限。
 *
 * @param objectType     对象类型
 * @param schema         字段声明
 * @param readPermission 对象级读权限（不可为空）
 * @author ByteForce
 * @since 1.0.0
 */
public record ReadModel(String objectType, ObjectSchema schema, String readPermission) {

    public ReadModel {
        if (objectType == null || objectType.isBlank()) {
            throw new IllegalArgumentException("objectType must not be blank");
        }
        schema = Objects.requireNonNull(schema, "schema");
        if (readPermission == null || readPermission.isBlank()) {
            throw new IllegalArgumentException("readPermission must not be blank");
        }
    }
}
