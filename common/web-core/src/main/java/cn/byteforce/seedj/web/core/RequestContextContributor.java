package cn.byteforce.seedj.web.core;

/**
 * 请求上下文身份贡献器 SPI：由安全接入等模块实现，在请求处理早期把身份写入
 * {@code RequestContext}（用户 / 租户 / 用户名）。
 *
 * <p>实现类须注册为 Spring Bean；多实现时通过 {@code @Order} 决定执行顺序
 * （由 starter-web 的请求上下文过滤器统一调度）。</p>
 *
 * <p>无身份或解析失败时实现应静默返回（不抛出），避免破坏匿名访问路径。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@FunctionalInterface
public interface RequestContextContributor {

    /**
     * 尝试向 {@code RequestContext} 填充当前请求的身份信息。
     *
     * <p>已填充的字段由下方实现自行通过 {@code RequestContext} 静态方法写入；
     * 无法解析时应直接返回。</p>
     */
    void contribute();
}
