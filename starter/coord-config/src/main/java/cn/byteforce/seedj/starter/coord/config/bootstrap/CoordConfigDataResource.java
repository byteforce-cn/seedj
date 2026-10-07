package cn.byteforce.seedj.starter.coord.config.bootstrap;

import cn.byteforce.seedj.starter.coord.CoordProperties;
import org.springframework.boot.context.config.ConfigDataResource;

import java.util.List;
import java.util.Objects;

/**
 * {@code spring.config.import=coord:<prefix>[,<prefix>]} 的引导资源描述：
 * 前缀集合 + 连接参数快照（引导阶段无 ApplicationContext，连接参数从
 * Binder 绑定 {@link CoordProperties} 得到）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class CoordConfigDataResource extends ConfigDataResource {

    private final List<String> prefixes;
    private final CoordProperties connectionProperties;
    private final boolean optional;

    /**
     * @param prefixes             key 前缀（C2：仅作过滤，暴露属性名为存储 key 原文）
     * @param connectionProperties 引导连接参数快照
     * @param optional             {@code optional:coord:} 显式声明（不可用时可跳过 + WARN）
     */
    public CoordConfigDataResource(List<String> prefixes, CoordProperties connectionProperties, boolean optional) {
        super(optional);
        this.prefixes = List.copyOf(prefixes);
        this.connectionProperties = connectionProperties;
        this.optional = optional;
    }

    /**
     * key 前缀。
     *
     * @return 不可变前缀列表
     */
    public List<String> getPrefixes() {
        return prefixes;
    }

    /**
     * 连接参数快照（引导期由 Binder 绑定）。
     *
     * @return 连接参数
     */
    public CoordProperties getConnectionProperties() {
        return connectionProperties;
    }

    /**
     * 是否可跳过（{@code optional:coord:}）。
     *
     * @return true 表示不可用时允许跳过并 WARN
     */
    public boolean isOptional() {
        return optional;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CoordConfigDataResource other)) {
            return false;
        }
        return optional == other.optional && prefixes.equals(other.prefixes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(prefixes, optional);
    }

    @Override
    public String toString() {
        return "coord:" + String.join(",", prefixes) + (optional ? " (optional)" : "");
    }
}
