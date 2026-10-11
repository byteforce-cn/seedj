package cn.byteforce.seedj.starter.coord.config;

import cn.byteforce.coord.sdk.config.ConfigClient;
import cn.byteforce.coord.sdk.config.ConfigEvent;
import cn.byteforce.coord.sdk.config.ConfigListener;
import cn.byteforce.coord.sdk.config.ConfigWatchSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 运行期刷新管理器（Phase 1.5，R3：{@code @ConfigurationProperties} 级 rebind）。
 *
 * <p>数据流：{@code configClient.watch(prefix)} 变更 → 原地更新已导入的
 * {@link CoordPropertySource} → 防抖合并 → {@link CoordConfigurationPropertiesRebinder#rebindAll()}
 * → 发布 {@link CoordConfigRefreshedEvent}。**{@code @Value} 不参与刷新**
 * （字段仅首次解析取值；动态配置一律使用 {@code @ConfigurationProperties}）。</p>
 *
 * <p>语义要点（C2 / C5）：</p>
 * <ul>
 *   <li>仅作用于 import 引入的属性源（v1 无 refresh-only 模式）；</li>
 *   <li>watch 断流：保留最后已知值 + WARN + 退避重订阅（不阻塞运行）；</li>
 *   <li>订阅就绪后做一次 {@code list()} 快照合并，并对「管理集中已消失」的 key
 *       做删除收敛（覆盖断线窗口的删除，契约语义以 C2 为准）；</li>
 *   <li>事件应用幂等（重复应用不产生变更）；单个 bean rebind 失败不影响其余。</li>
 * </ul>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class CoordConfigRefreshManager implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(CoordConfigRefreshManager.class);

    /** rebind 防抖窗口：多事件合并为一次 rebind。 */
    private static final Duration REBIND_DEBOUNCE = Duration.ofMillis(150);

    /** 重订阅退避上限。 */
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private final ConfigClient configClient;
    private final CoordConfigurationPropertiesRebinder rebinder;
    private final ConfigurableEnvironment environment;
    private final ApplicationEventPublisher eventPublisher;
    private final Duration initialBackoff;
    private final ScheduledExecutorService scheduler;

    private final Map<String, SubscriptionState> subscriptions = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> managedKeysByPrefix = new ConcurrentHashMap<>();
    private final Set<String> pendingChangedKeys = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean rebindScheduled = new AtomicBoolean();

    private volatile boolean running;

    /**
     * 生产构造。
     *
     * @param configClient   SDK 配置客户端
     * @param rebinder       rebind 执行器
     * @param environment    环境（定位 {@link CoordPropertySource}）
     * @param eventPublisher 事件发布器（{@link CoordConfigRefreshedEvent}）
     */
    public CoordConfigRefreshManager(ConfigClient configClient, CoordConfigurationPropertiesRebinder rebinder,
                                     ConfigurableEnvironment environment, ApplicationEventPublisher eventPublisher) {
        this(configClient, rebinder, environment, eventPublisher, Duration.ofSeconds(1));
    }

    /**
     * 测试用构造（可调重订阅退避）。
     *
     * @param configClient   SDK 配置客户端
     * @param rebinder       rebind 执行器
     * @param environment    环境
     * @param eventPublisher 事件发布器
     * @param initialBackoff 初始重订阅退避
     */
    CoordConfigRefreshManager(ConfigClient configClient, CoordConfigurationPropertiesRebinder rebinder,
                              ConfigurableEnvironment environment, ApplicationEventPublisher eventPublisher,
                              Duration initialBackoff) {
        this.configClient = configClient;
        this.rebinder = rebinder;
        this.environment = environment;
        this.eventPublisher = eventPublisher;
        this.initialBackoff = initialBackoff;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "coord-config-refresh");
            thread.setDaemon(true);
            return thread;
        });
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        List<CoordPropertySource> sources = findPropertySources();
        if (sources.isEmpty()) {
            log.info("未发现 coord: 配置导入（spring.config.import=coord:<prefix>），运行期刷新未启用");
            return;
        }
        log.info("coord 配置导入运行期刷新已启用：prefixes={}",
                sources.stream().flatMap(source -> source.prefixes().stream()).toList());
        for (CoordPropertySource source : sources) {
            for (String prefix : source.prefixes()) {
                subscribe(source, prefix, 0);
            }
        }
    }

    @Override
    public void stop() {
        running = false;
        scheduler.shutdownNow();
        for (SubscriptionState state : subscriptions.values()) {
            closeQuietly(state.subscription());
        }
        subscriptions.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // 停机最先执行：早于 CoordClient.close（watch 先取消）
        return Integer.MAX_VALUE;
    }

    /**
     * 当前活跃的订阅前缀（诊断 / 测试用）。
     *
     * @return 前缀集合快照
     */
    public Set<String> subscribedPrefixes() {
        return Set.copyOf(subscriptions.keySet());
    }

    // ---------------------------------------------------------------- 订阅管理

    private List<CoordPropertySource> findPropertySources() {
        List<CoordPropertySource> sources = new ArrayList<>();
        for (PropertySource<?> propertySource : environment.getPropertySources()) {
            if (propertySource instanceof CoordPropertySource coordPropertySource) {
                sources.add(coordPropertySource);
            }
        }
        return sources;
    }

    private void subscribe(CoordPropertySource source, String prefix, int failures) {
        if (!running) {
            return;
        }
        try {
            ConfigWatchSubscription subscription = configClient.watch(prefix, new PrefixListener(source, prefix));
            if (!running) {
                // 停机竞态：订阅建立期间已开始停机，直接取消，避免残留在途 watch
                closeQuietly(subscription);
                return;
            }
            SubscriptionState previous = subscriptions.put(prefix, new SubscriptionState(subscription, failures));
            if (previous != null) {
                closeQuietly(previous.subscription());
            }
            // 订阅就绪后的快照合并：覆盖「引导快照 → 订阅建立」窗口，并做删除收敛
            mergeSnapshot(source, prefix);
        } catch (RuntimeException e) {
            int attempt = failures + 1;
            Duration delay = backoff(attempt);
            log.warn("配置订阅失败（prefix={}，连续第 {} 次），{} 后重试: {}", prefix, attempt, delay, e.getMessage());
            schedule(() -> subscribe(source, prefix, attempt), delay);
        }
    }

    private void mergeSnapshot(CoordPropertySource source, String prefix) {
        Map<String, String> snapshot;
        try {
            snapshot = configClient.list(prefix);
        } catch (RuntimeException e) {
            log.warn("配置快照读取失败（prefix={}，watch 事件仍将生效）: {}", prefix, e.getMessage());
            return;
        }
        Set<String> managed = managedKeysByPrefix.computeIfAbsent(prefix, key -> ConcurrentHashMap.newKeySet());
        Set<String> changed = new HashSet<>();
        for (Map.Entry<String, String> entry : snapshot.entrySet()) {
            if (source.apply(entry.getKey(), Optional.of(entry.getValue()))) {
                changed.add(entry.getKey());
            }
            managed.add(entry.getKey());
        }
        // 删除收敛：管理集中已不在快照内的 key 视为已删除（覆盖断线窗口）
        for (String stale : Set.copyOf(managed)) {
            if (!snapshot.containsKey(stale)) {
                if (source.apply(stale, Optional.empty())) {
                    changed.add(stale);
                }
                managed.remove(stale);
            }
        }
        if (!changed.isEmpty()) {
            pendingChangedKeys.addAll(changed);
            scheduleRebind();
        }
    }

    private void schedule(Runnable task, Duration delay) {
        if (!running) {
            return;
        }
        try {
            scheduler.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // 停机竞态：调度器已关闭
        }
    }

    private void scheduleRebind() {
        if (!running) {
            return;
        }
        if (rebindScheduled.compareAndSet(false, true)) {
            try {
                scheduler.schedule(this::runRebind, REBIND_DEBOUNCE.toMillis(), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ignored) {
                rebindScheduled.set(false);
            }
        }
    }

    private void runRebind() {
        rebindScheduled.set(false);
        if (!running) {
            return;
        }
        Set<String> changed = new HashSet<>(pendingChangedKeys);
        pendingChangedKeys.clear();
        if (changed.isEmpty()) {
            return;
        }
        Map<String, Throwable> failures = rebinder.rebindAll();
        log.info("coord 配置刷新完成：变更 {} 项，rebind 失败 {} 项", changed.size(), failures.size());
        try {
            eventPublisher.publishEvent(new CoordConfigRefreshedEvent(this, changed, failures));
        } catch (RuntimeException e) {
            log.warn("发布配置刷新事件失败（已忽略）: {}", e.getMessage());
        }
    }

    private Duration backoff(int attempt) {
        long millis = initialBackoff.toMillis() * (1L << Math.min(attempt - 1, 10));
        return Duration.ofMillis(Math.min(millis, MAX_BACKOFF.toMillis()));
    }

    private void closeQuietly(ConfigWatchSubscription subscription) {
        try {
            subscription.close();
        } catch (RuntimeException e) {
            log.warn("关闭配置订阅失败（已忽略）: {}", e.getMessage());
        }
    }

    /** 订阅状态。 */
    private static final class SubscriptionState {

        private final ConfigWatchSubscription subscription;
        private final int failures;

        private SubscriptionState(ConfigWatchSubscription subscription, int failures) {
            this.subscription = subscription;
            this.failures = failures;
        }

        private ConfigWatchSubscription subscription() {
            return subscription;
        }
    }

    /** 前缀级 watch 监听器。 */
    private final class PrefixListener implements ConfigListener {

        private final CoordPropertySource source;
        private final String prefix;

        private PrefixListener(CoordPropertySource source, String prefix) {
            this.source = source;
            this.prefix = prefix;
        }

        @Override
        public void onEvent(ConfigEvent event) {
            boolean changed = source.apply(event.getKey(), event.getNewValue());
            Set<String> managed = managedKeysByPrefix.computeIfAbsent(prefix, key -> ConcurrentHashMap.newKeySet());
            if (event.getNewValue().isPresent()) {
                managed.add(event.getKey());
            } else {
                managed.remove(event.getKey());
            }
            if (changed) {
                pendingChangedKeys.add(event.getKey());
                scheduleRebind();
            }
        }

        @Override
        public void onTerminated(Throwable cause) {
            if (!running) {
                return;
            }
            log.warn("配置 watch 终止（prefix={}），将重建订阅: {}",
                    prefix, cause == null ? "unknown" : cause.getMessage());
            SubscriptionState state = subscriptions.get(prefix);
            int attempt = (state == null ? 0 : state.failures) + 1;
            schedule(() -> subscribe(source, prefix, attempt), backoff(attempt));
        }
    }
}
