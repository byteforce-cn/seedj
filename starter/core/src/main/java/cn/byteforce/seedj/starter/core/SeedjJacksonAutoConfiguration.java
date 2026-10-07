package cn.byteforce.seedj.starter.core;

import cn.byteforce.seedj.starter.core.jackson.CodeEnumDeserializerModifier;
import cn.byteforce.seedj.starter.core.jackson.CodeEnumSerializer;
import cn.byteforce.seedj.starter.core.jackson.LongDeserializer;
import cn.byteforce.seedj.starter.core.jackson.LongToStringSerializer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

/**
 * seedj Jackson 自动配置：把「CodeEnum ↔ code」与 Long 字符串化写入 JSON 契约。
 *
 * <p>装配效果（随模块自动生效，无需业务方 @Import）：</p>
 * <ul>
 *   <li>序列化：实现 {@code CodeEnum} 的枚举输出 {@code code}；</li>
 *   <li>反序列化：严格按 {@code code} 命中，未知 code 报 400，绝不按 ordinal 静默映射；</li>
 *   <li>{@code Long} 默认序列化为字符串（可用 {@code byteforce.jackson.long-as-string=false} 关闭）。</li>
 * </ul>
 *
 * <p>可通过 {@code byteforce.jackson.enabled=false} 整体关闭。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnClass({JsonMapperBuilderCustomizer.class, JsonMapper.class})
@ConditionalOnProperty(prefix = "byteforce.jackson", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SeedjJacksonProperties.class)
public class SeedjJacksonAutoConfiguration {

    /**
     * seedj JSON 定制：注册 CodeEnum 序列化器与反序列化 modifier，按需注册 Long 字符串化。
     *
     * @param properties 定制属性
     * @return JsonMapper 构建定制器
     */
    @Bean
    @ConditionalOnMissingBean(name = "seedjJsonMapperBuilderCustomizer")
    public JsonMapperBuilderCustomizer seedjJsonMapperBuilderCustomizer(SeedjJacksonProperties properties) {
        return builder -> {
            SimpleModule module = new SimpleModule("SeedjJacksonModule");

            // CodeEnum：序列化输出 code；反序列化经 enum modifier 按具体枚举类型接管
            module.addSerializer(CodeEnumSerializer.INSTANCE);
            module.setDeserializerModifier(new CodeEnumDeserializerModifier());

            if (properties.isLongAsString()) {
                module.addSerializer(Long.class, LongToStringSerializer.INSTANCE);
                module.addSerializer(Long.TYPE, LongToStringSerializer.INSTANCE);
                module.addDeserializer(Long.class, LongDeserializer.INSTANCE);
                module.addDeserializer(Long.TYPE, LongDeserializer.INSTANCE);
            }

            builder.addModule(module);
        };
    }
}
