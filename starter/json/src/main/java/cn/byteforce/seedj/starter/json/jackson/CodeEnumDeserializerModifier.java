package cn.byteforce.seedj.starter.json.jackson;

import cn.byteforce.seedj.core.CodeEnum;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.DeserializationConfig;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.deser.ValueDeserializerModifier;

/**
 * 把 {@link CodeEnumDeserializer} 装配到所有实现 {@link CodeEnum} 的枚举类型上。
 *
 * <p>Jackson 3 下按接口键注册反序列化器（{@code addDeserializer(CodeEnum.class, ...)}）
 * 不会级联到具体枚举子类型，必须经 {@code modifyEnumDeserializer} 钩子按具体类型接管。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class CodeEnumDeserializerModifier extends ValueDeserializerModifier {

    @Override
    public ValueDeserializer<?> modifyEnumDeserializer(DeserializationConfig config, JavaType valueType,
                                                       BeanDescription.Supplier beanDesc,
                                                       ValueDeserializer<?> deserializer) {
        Class<?> raw = valueType.getRawClass();
        if (raw.isEnum() && CodeEnum.class.isAssignableFrom(raw)) {
            return new CodeEnumDeserializer(asEnumClass(raw));
        }
        return deserializer;
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Enum<?>> asEnumClass(Class<?> raw) {
        return (Class<? extends Enum<?>>) raw;
    }
}
