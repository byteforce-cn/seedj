package cn.byteforce.seedj.starter.eis.core;

import java.util.List;
import java.util.UUID;

/**
 * 对象引用（命令结果的 {@code created} 指针；与内核 {@code {"type":…,"id":…}} 对齐）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class EisObjectRef {

    private final String objectType;

    private final UUID id;

    /**
     * 构造对象引用。
     *
     * @param objectType 对象类型
     * @param id         对象 ID
     */
    public EisObjectRef(String objectType, UUID id) {
        this.objectType = objectType;
        this.id = id;
    }

    /**
     * 获取对象类型。
     *
     * @return 对象类型
     */
    public String getObjectType() {
        return objectType;
    }

    /**
     * 获取对象 ID。
     *
     * @return 对象 ID
     */
    public UUID getId() {
        return id;
    }
}
