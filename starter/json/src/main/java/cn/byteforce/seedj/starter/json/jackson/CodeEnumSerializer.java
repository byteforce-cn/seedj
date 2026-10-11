package cn.byteforce.seedj.starter.json.jackson;

import cn.byteforce.seedj.core.CodeEnum;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * {@link CodeEnum} 序列化器：wire 输出枚举的 {@code code}（而非 {@code ordinal} / 名称）。
 *
 * <p>实现继承 {@link StdSerializer}，使其可经 {@code module.addSerializer(INSTANCE)}
 * 完成类型安全注册（Jackson 3 下 {@code ValueSerializer} 直接无参注册会因缺少合法
 * {@code handledType()} 而抛 {@code IllegalArgumentException}）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class CodeEnumSerializer extends StdSerializer<CodeEnum<?>> {

    private CodeEnumSerializer() {
        super(CodeEnum.class);
    }

    /**
     * 无状态单例。
     */
    public static final CodeEnumSerializer INSTANCE = new CodeEnumSerializer();

    @Override
    public void serialize(CodeEnum<?> value, JsonGenerator gen, SerializationContext ctx) {
        if (value == null) {
            gen.writeNull();
        } else {
            gen.writePOJO(value.getCode());
        }
    }
}