package cn.byteforce.seedj.starter.coord.config;

import org.springframework.context.ApplicationEvent;

import java.util.Map;
import java.util.Set;

/**
 * coord 配置刷新完成事件（自研刷新机制，不依赖 / 不发布 Spring Cloud 的
 * {@code RefreshEvent}；seedj ADR-0001 §决策 4）。
 *
 * <p>发布时机：一次 watch 变更批次（含防抖合并）完成 {@code @ConfigurationProperties}
 * rebind 之后。{@code @Value} 不参与刷新（文档明示）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class CoordConfigRefreshedEvent extends ApplicationEvent {

    /** 本批次变更的 key 集合（不可变副本）。 */
    private final Set<String> changedKeys;

    /** rebind 失败的 bean 名 → 异常（不可变副本；无失败时为空 Map）。 */
    private final Map<String, Throwable> rebindFailures;

    /**
     * @param source         事件源（刷新管理器）
     * @param changedKeys    本批次变更的 key 集合（不可变副本）
     * @param rebindFailures rebind 失败的 bean 名 → 异常（无失败时为空 Map）
     */
    public CoordConfigRefreshedEvent(Object source, Set<String> changedKeys, Map<String, Throwable> rebindFailures) {
        super(source);
        this.changedKeys = Set.copyOf(changedKeys);
        this.rebindFailures = Map.copyOf(rebindFailures);
    }

    /**
     * 本批次变更的 key 集合。
     *
     * @return 不可变 key 集合
     */
    public Set<String> getChangedKeys() {
        return changedKeys;
    }

    /**
     * rebind 失败的 bean（失败 bean 保留旧值，其余正常刷新）。
     *
     * @return bean 名 → 失败原因（不可变）
     */
    public Map<String, Throwable> getRebindFailures() {
        return rebindFailures;
    }

    /**
     * 是否存在 rebind 失败。
     *
     * @return true 表示有 bean 刷新失败（旧值保留）
     */
    public boolean hasRebindFailures() {
        return !rebindFailures.isEmpty();
    }
}
