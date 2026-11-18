package cn.byteforce.seedj.starter.ai;

/**
 * 由 {@code @EnableAIServer} 注册的标记 bean，把注解变成纯粹的启用开关。
 *
 * <p>能力自动配置（{@code ai.autoconfigure} / {@code ai.guard} / {@code ai.plane} / {@code ai.runtime} /
 * {@code ai.token}）一律以 {@code @ConditionalOnBean(AiEnabledMarker.class)} 为门：标记只在应用写了注解时才存在，
 * 因此本模块被宿主依赖但宿主没写注解时，AI 能力一个都不会装配。
 */
public final class AiEnabledMarker {}
