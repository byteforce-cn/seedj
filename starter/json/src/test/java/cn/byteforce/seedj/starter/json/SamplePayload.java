package cn.byteforce.seedj.starter.json;

/**
 * 测试用载荷：覆盖枚举字段、包装 Long 与原生 long。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class SamplePayload {

    /**
     * 枚举字段。
     */
    public SampleState state;

    /**
     * 包装 Long 字段。
     */
    public Long id;

    /**
     * 原生 long 字段。
     */
    public long primitiveId;
}
