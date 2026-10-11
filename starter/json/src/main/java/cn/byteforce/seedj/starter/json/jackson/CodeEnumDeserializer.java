package cn.byteforce.seedj.starter.json.jackson;

import cn.byteforce.seedj.core.CodeEnum;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.exc.InvalidFormatException;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link CodeEnum} 反序列化器：严格按 {@code code} 命中枚举。
 *
 * <p>契约（seedj ADR-0003）：</p>
 * <ul>
 *   <li>只接受 code 输入（数字或字符串均按 code 类型读取）；</li>
 *   <li>未知 code 抛 {@link InvalidFormatException}（映射为 400），
 *       <b>绝不按 ordinal 静默命中</b>；</li>
 *   <li>JSON {@code null} 返回 {@code null}。</li>
 * </ul>
 *
 * <p>该反序列化器由 {@link CodeEnumDeserializerModifier} 按具体枚举类型装配
 * （Jackson 3 下按接口注册不会级联到子类型）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class CodeEnumDeserializer extends ValueDeserializer<CodeEnum<?>> {

    private final Class<? extends Enum<?>> enumClass;

    private final Class<?> codeClass;

    private final Map<Object, CodeEnum<?>> byCode;

    /**
     * 构造反序列化器。
     *
     * @param enumClass 目标枚举类型（必须实现 {@link CodeEnum}）
     */
    public CodeEnumDeserializer(Class<? extends Enum<?>> enumClass) {
        if (enumClass == null || !enumClass.isEnum() || !CodeEnum.class.isAssignableFrom(enumClass)) {
            throw new IllegalArgumentException(
                    "CodeEnumDeserializer 只能用于 CodeEnum 的枚举实现类, 实际: " + enumClass);
        }
        this.enumClass = enumClass;

        Enum<?>[] constants = enumClass.getEnumConstants();
        Object code = (constants.length > 0) ? ((CodeEnum<?>) constants[0]).getCode() : null;
        this.codeClass = (code != null) ? code.getClass() : String.class;

        Map<Object, CodeEnum<?>> index = new HashMap<>();
        for (Enum<?> constant : constants) {
            index.put(((CodeEnum<?>) constant).getCode(), (CodeEnum<?>) constant);
        }
        this.byCode = Collections.unmodifiableMap(index);
    }

    @Override
    public CodeEnum<?> deserialize(JsonParser p, DeserializationContext ctxt) {
        if (p.currentToken() == JsonToken.VALUE_NULL) {
            return null;
        }

        Object code = ctxt.readValue(p, ctxt.constructType(codeClass));
        CodeEnum<?> result = (code == null) ? null : byCode.get(code);
        if (result == null) {
            throw InvalidFormatException.from(p,
                    "无效的枚举 code: " + code + ", 类型: " + enumClass.getSimpleName(),
                    code, enumClass);
        }
        return result;
    }
}