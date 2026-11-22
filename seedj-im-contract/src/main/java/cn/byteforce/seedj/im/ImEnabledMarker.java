package cn.byteforce.seedj.im;

/**
 * 由 {@code @EnableImServer} 注册的标记 bean，把注解变成纯粹的启用开关。
 *
 * <p>适配器模块以同一标记为条件：只引入适配器而未启用 IM 的宿主不会得到 IM 存储 bean。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class ImEnabledMarker {}
