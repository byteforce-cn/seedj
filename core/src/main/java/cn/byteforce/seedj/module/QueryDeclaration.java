package cn.byteforce.seedj.module;

import java.util.Objects;

/**
 * 一条类型化查询声明。
 *
 * @param objectType 对象类型
 * @param viewType   视图类型
 * @param path       HTTP 路径（以 {@code /} 开头、全局唯一）
 * @param permission 查询权限
 * @param sortField  默认排序字段；可为 {@code null}
 * @author ByteForce
 * @since 1.0.0
 */
public record QueryDeclaration(String objectType, Class<?> viewType, String path,
                               String permission, String sortField) {

    public QueryDeclaration {
        if (objectType == null || objectType.isBlank()) {
            throw new IllegalArgumentException("objectType must not be blank");
        }
        viewType = Objects.requireNonNull(viewType, "viewType");
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("query path must start with /: " + path);
        }
        if (permission == null || permission.isBlank()) {
            throw new IllegalArgumentException("query permission must not be blank");
        }
    }
}
