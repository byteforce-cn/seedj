
package cn.byteforce.seedj.starter.core.jackson;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;


/**
 * Long → 字符串序列化器（ADR-0003：长整型默认字符串化，规避 JS 数值精度上限）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class LongToStringSerializer extends ValueSerializer<Long> {

    private LongToStringSerializer() {}

    /** 单例。 */
    public static final LongToStringSerializer INSTANCE = new LongToStringSerializer();

    @Override
    public void serialize(Long value, JsonGenerator gen, SerializationContext ctxt) {
        if (value == null) {
            gen.writeNull();
        } else {
            gen.writeString(String.valueOf(value));
        }
    }
}