package cn.byteforce.seedj.starter.coord.config.bootstrap;

import cn.byteforce.seedj.starter.coord.CoordProperties;
import org.springframework.boot.context.config.ConfigDataLocation;
import org.springframework.boot.context.config.ConfigDataLocationNotFoundException;
import org.springframework.boot.context.config.ConfigDataLocationResolver;
import org.springframework.boot.context.config.ConfigDataLocationResolverContext;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;

/**
 * {@code coord:} 配置位置解析器（经 {@code META-INF/spring.factories} 注册，
 * 启动时由 ConfigData 机制发现）。
 *
 * <p>语法：{@code coord:<prefix>[;<prefix>...]}；可加 {@code optional:} 前缀
 * （由框架剥离并设 {@code isOptional()}）。注意：框架级 {@code spring.config.import}
 * 用「逗号」分隔多个导入项，因此同一项内的多前缀用「分号」分隔：
 * 等价写法 {@code coord:a;coord:b} / {@code coord:a;b} / {@code coord:a,coord:b}。</p>
 *
 * <p>连接参数（{@code byteforce.coord.*}）在此绑定为 {@link CoordProperties} 快照交给
 * {@link CoordConfigDataLoader} —— 引导阶段没有 ApplicationContext，凭据 Supplier 等
 * Bean 不可用，仅支持静态 {@code auth-token}（文档明示）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class CoordConfigDataLocationResolver implements ConfigDataLocationResolver<CoordConfigDataResource> {

    /** 位置前缀。 */
    public static final String PREFIX = "coord:";

    /** 同项多前缀分隔符（框架已占用逗号，见类注释）。 */
    public static final String PREFIX_SEPARATOR = ";";

    @Override
    public boolean isResolvable(ConfigDataLocationResolverContext context, ConfigDataLocation location) {
        return location.hasPrefix(PREFIX);
    }

    @Override
    public List<CoordConfigDataResource> resolve(ConfigDataLocationResolverContext context,
                                                 ConfigDataLocation location) throws ConfigDataLocationNotFoundException {
        String value = location.getNonPrefixedValue(PREFIX);
        List<String> prefixes = Arrays.stream(value.split(PREFIX_SEPARATOR))
                .map(String::trim)
                .map(item -> item.startsWith(PREFIX) ? item.substring(PREFIX.length()) : item)
                .filter(StringUtils::hasText)
                .toList();
        if (prefixes.isEmpty()) {
            throw new ConfigDataLocationNotFoundException(location,
                    "coord: 位置需要至少一个 key 前缀（如 spring.config.import=coord:app.orders）", null);
        }
        CoordProperties properties = context.getBinder()
                .bind("byteforce.coord", Bindable.of(CoordProperties.class))
                .orElseGet(CoordProperties::new);
        return List.of(new CoordConfigDataResource(prefixes, properties, location.isOptional()));
    }
}
