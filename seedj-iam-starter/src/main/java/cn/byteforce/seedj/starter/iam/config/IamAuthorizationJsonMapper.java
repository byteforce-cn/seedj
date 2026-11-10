package cn.byteforce.seedj.starter.iam.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.jackson.SecurityJacksonModules;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.module.SimpleModule;

/**
 * {@code oauth2_authorization} 行的 JSON 编解码器：在 SAS 默认配置之上放行 JDK 不可变集合的旧类型 id。
 *
 * <p>{@link
 * org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService}
 * 用默认类型信息（{@code @class} / 类型包装数组）把 claims 与 attributes 写进数据库，读回时受 {@code
 * BasicPolymorphicTypeValidator} 白名单约束：只放行 {@code java.util.ArrayList}、{@code LinkedHashMap}
 * 等公开实现，拒绝 {@code java.util.ImmutableCollections$*}。
 *
 * <p>旧版本把 {@code List.copyOf(...)} 写进 claims，库里已经存在类型 id 为 {@code
 * java.util.ImmutableCollections$List12} / {@code $ListN} / {@code $Set12} / {@code $SetN} / {@code
 * $Map1} / {@code $MapN} 的行。升级后这些行必须在第一次 refresh_token 交换时就能读出，否则用户被迫重新登录。这里为这六种类型
 * 登记「读成公开集合」的反序列化器，读出的对象再写回时类型 id 已回到白名单内，刷新链可以连续成功。
 *
 * @author ByteForce
 * @since 1.0.0
 */
final class IamAuthorizationJsonMapper {

    /** 旧行里可能出现的 JDK 内部集合类型及对应的公开集合读法。 */
    private static final List<LegacyType> LEGACY_TYPES =
            List.of(
                    new LegacyType(
                            "java.util.ImmutableCollections$List12", new LegacyListDeserializer()),
                    new LegacyType(
                            "java.util.ImmutableCollections$ListN", new LegacyListDeserializer()),
                    new LegacyType(
                            "java.util.ImmutableCollections$Set12", new LegacySetDeserializer()),
                    new LegacyType(
                            "java.util.ImmutableCollections$SetN", new LegacySetDeserializer()),
                    new LegacyType(
                            "java.util.ImmutableCollections$Map1", new LegacyMapDeserializer()),
                    new LegacyType(
                            "java.util.ImmutableCollections$MapN", new LegacyMapDeserializer()));

    private IamAuthorizationJsonMapper() {}

    /**
     * 构造授权行编解码器。
     *
     * @return 与 SAS 默认配置同源、并兼容旧不可变集合类型 id 的 JSON mapper
     */
    static JsonMapper create() {
        BasicPolymorphicTypeValidator.Builder validator = BasicPolymorphicTypeValidator.builder();
        LEGACY_TYPES.forEach(type -> validator.allowIfSubType(type.name()));
        List<JacksonModule> modules =
                SecurityJacksonModules.getModules(
                        IamAuthorizationJsonMapper.class.getClassLoader(), validator);
        SimpleModule legacyCollections = new SimpleModule("seedj-oauth2-legacy-collections");
        LEGACY_TYPES.forEach(type -> register(legacyCollections, type));
        return JsonMapper.builder().addModules(modules).addModule(legacyCollections).build();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void register(SimpleModule module, LegacyType type) {
        try {
            module.addDeserializer((Class) Class.forName(type.name()), type.deserializer());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("JDK collection type disappeared: " + type.name(), e);
        }
    }

    /**
     * 一种旧类型 id 与它的反序列化器。
     *
     * @param name JDK 内部集合的类名
     * @param deserializer 读成公开集合的反序列化器
     */
    private record LegacyType(String name, ValueDeserializer<Object> deserializer) {}

    /** {@code List.of} / {@code List.copyOf} 的旧类型 id → {@link ArrayList}。 */
    private static final class LegacyListDeserializer extends ValueDeserializer<Object> {

        @Override
        public Object deserialize(JsonParser parser, DeserializationContext context)
                throws JacksonException {
            List<Object> values = new ArrayList<>();
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                values.add(context.readValue(parser, Object.class));
            }
            return values;
        }
    }

    /** {@code Set.of} / {@code Set.copyOf} 的旧类型 id → {@link LinkedHashSet}。 */
    private static final class LegacySetDeserializer extends ValueDeserializer<Object> {

        @Override
        public Object deserialize(JsonParser parser, DeserializationContext context)
                throws JacksonException {
            Set<Object> values = new LinkedHashSet<>();
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                values.add(context.readValue(parser, Object.class));
            }
            return values;
        }
    }

    /** {@code Map.of} / {@code Map.copyOf} 的旧类型 id → {@link LinkedHashMap}。 */
    private static final class LegacyMapDeserializer extends ValueDeserializer<Object> {

        @Override
        public Object deserialize(JsonParser parser, DeserializationContext context)
                throws JacksonException {
            Map<String, Object> values = new LinkedHashMap<>();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String name = parser.currentName();
                parser.nextToken();
                values.put(name, context.readValue(parser, Object.class));
            }
            return values;
        }
    }
}
