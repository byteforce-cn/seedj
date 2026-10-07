package cn.byteforce.seedj.starter.coord.registry;

import cn.byteforce.coord.sdk.CoordClient;
import cn.byteforce.coord.sdk.registry.Registration;
import cn.byteforce.coord.sdk.registry.Registry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 自动注册 / 优雅注销（Phase 1，约定 C1 / C5）。
 *
 * <p>语义要点：</p>
 * <ul>
 *   <li><b>不阻塞启动</b>：端口在 {@code WebServerInitializedEvent} 时获知后异步注册；
 *       失败按 1s→30s 指数退避重试并 WARN（C5「可降级」）；</li>
 *   <li><b>身份</b>：service_name = {@code byteforce.coord.registry.service-name} ?:
 *       {@code spring.application.name}；instance_id 默认 {@code {host}:{port}}
 *       （同键重复注册 = 覆盖续约，可安全复用）；两者之一缺失时跳过并 WARN；</li>
 *   <li><b>心跳失败恢复</b>：注销旧注册（尽力而为）后重建注册以刷新租约；旧注册对象
 *       保留引用，停机时一并关闭，杜绝停机后僵尸心跳；</li>
 *   <li><b>优雅注销</b>：{@link #stop()} 在 web 容器停止前（phase = MAX_VALUE）关闭注册。</li>
 * </ul>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class CoordAutoRegistration implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(CoordAutoRegistration.class);

    /** 注销时的最长等待（注册面 graceful close）。 */
    private static final Duration DEREGISTER_TIMEOUT = Duration.ofSeconds(5);

    /** 心跳恢复时关闭旧注册句柄的最长等待。 */
    private static final Duration RELEASE_TIMEOUT = Duration.ofSeconds(1);

    /** 心跳失败后重建注册前的固定等待。 */
    private static final Duration HEARTBEAT_RECOVERY_DELAY = Duration.ofSeconds(1);

    /** 退避上限。 */
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private final Registry registry;
    private final CoordRegistryProperties properties;
    private final Environment environment;
    private final List<CoordRegistrationListener> listeners;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final ScheduledExecutorService scheduler;

    /** 已创建的注册句柄（含心跳恢复前的旧句柄）；停机时全部关闭，防僵尸心跳。 */
    private final List<Registration> allRegistrations = new CopyOnWriteArrayList<>();

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicBoolean announced = new AtomicBoolean();

    private volatile Registration registration;
    private volatile CoordServiceInstance registeredView;
    private volatile int webServerPort = -1;
    private volatile boolean stopped;

    /**
     * 生产构造。
     *
     * @param coordClient SDK 主客户端
     * @param properties  注册属性
     * @param environment 环境（读取 {@code spring.application.name} / {@code server.port}）
     * @param listeners   生命周期回调（可为空列表）
     */
    public CoordAutoRegistration(CoordClient coordClient, CoordRegistryProperties properties,
                                 Environment environment, List<CoordRegistrationListener> listeners) {
        this(coordClient.registry(), properties, environment, listeners,
                Duration.ofSeconds(1), MAX_BACKOFF);
    }

    /**
     * 测试用构造（可调退避）。
     *
     * @param registry       SDK 注册面客户端
     * @param properties     注册属性
     * @param environment    环境
     * @param listeners      生命周期回调
     * @param initialBackoff 初始退避
     * @param maxBackoff     退避上限
     */
    CoordAutoRegistration(Registry registry, CoordRegistryProperties properties, Environment environment,
                          List<CoordRegistrationListener> listeners, Duration initialBackoff, Duration maxBackoff) {
        this.registry = registry;
        this.properties = properties;
        this.environment = environment;
        this.listeners = List.copyOf(listeners);
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "coord-registration");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Web 容器端口可用（来自 {@code WebServerInitializedEvent}）。
     *
     * @param port 实际监听端口（支持 {@code server.port=0} 的随机端口）
     */
    public void onPortAvailable(int port) {
        if (stopped) {
            return;
        }
        this.webServerPort = port;
        scheduleAttempt(Duration.ZERO);
    }

    /**
     * 应用就绪（{@code ApplicationReadyEvent}）：非 web 应用按
     * {@code server.port} 固定端口兜底；未取得端口时跳过（WARN 由注册尝试输出）。
     *
     * @param environment 环境
     */
    public void onApplicationReady(Environment environment) {
        if (stopped || webServerPort > 0) {
            return;
        }
        Integer port = environment.getProperty("server.port", Integer.class);
        if (port != null && port > 0) {
            onPortAvailable(port);
        }
    }

    /**
     * 是否已注册（心跳恢复期间可能短暂为 false）。
     *
     * @return true 表示当前持有有效注册
     */
    public boolean isRegistered() {
        return registration != null;
    }

    /**
     * 当前注册实例视图（未注册时为空）。
     *
     * @return 实例视图
     */
    public Optional<CoordServiceInstance> registeredInstance() {
        return Optional.ofNullable(registeredView);
    }

    @Override
    public void start() {
        // 注册由事件驱动；start 仅维持 SmartLifecycle 契约（phase = MAX_VALUE，停机最先执行）
    }

    @Override
    public void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        scheduler.shutdownNow();
        boolean first = true;
        for (Registration registrationHandle : allRegistrations) {
            try {
                registrationHandle.close(DEREGISTER_TIMEOUT);
                if (first) {
                    first = false;
                    log.info("服务实例已注销: {}", registeredView);
                    CoordServiceInstance view = registeredView;
                    if (view != null) {
                        notifyListeners(listener -> listener.onDeregistered(view));
                    }
                }
            } catch (RuntimeException e) {
                log.warn("注销服务实例失败（依赖 TTL 过期兜底）: {}", e.getMessage());
            }
        }
        registration = null;
    }

    @Override
    public boolean isRunning() {
        return !stopped;
    }

    @Override
    public int getPhase() {
        // 停机最先执行：早于 web 容器停止与 CoordClient.close
        return Integer.MAX_VALUE;
    }

    // ---------------------------------------------------------------- 注册尝试 / 恢复

    private void scheduleAttempt(Duration delay) {
        if (stopped) {
            return;
        }
        try {
            scheduler.schedule(this::attemptRegister, delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // 停机竞态：调度器已关闭，注册需求随停机自然取消
        }
    }

    private synchronized void attemptRegister() {
        if (stopped || registration != null) {
            return;
        }
        String serviceName = resolveServiceName();
        if (!StringUtils.hasText(serviceName)) {
            log.warn("未配置 spring.application.name / byteforce.coord.registry.service-name，自动注册已跳过");
            return;
        }
        int port = webServerPort;
        if (port <= 0) {
            log.warn("未取得服务端口，自动注册已跳过（service={}；无 web 容器时可用 server.port 指定）", serviceName);
            return;
        }
        String host = resolveHost();
        String instanceId = StringUtils.hasText(properties.getInstanceId())
                ? properties.getInstanceId()
                : host + ":" + port;
        String metadataJson = CoordInstanceMetadata.toJson(host, port, properties);
        try {
            Registration newRegistration =
                    registry.register(serviceName, instanceId, metadataJson, properties.ttlSeconds());
            if (stopped) {
                // 停机竞态回滚：注册结果落地前已开始停机，直接注销避免残留
                closeQuietly(newRegistration);
                return;
            }
            newRegistration.onHeartbeatFailed(this::onHeartbeatFailed);
            allRegistrations.add(newRegistration);
            this.registration = newRegistration;
            this.registeredView = new CoordServiceInstance(serviceName, instanceId, metadataJson);
            consecutiveFailures.set(0);
            log.info("服务实例注册成功: service={} instance={} ttl={}s",
                    serviceName, instanceId, properties.ttlSeconds());
            if (announced.compareAndSet(false, true)) {
                CoordServiceInstance view = registeredView;
                notifyListeners(listener -> listener.onRegistered(view));
            }
        } catch (RuntimeException e) {
            int attempt = consecutiveFailures.incrementAndGet();
            Duration delay = backoff(attempt);
            log.warn("服务注册失败（连续第 {} 次），{} 后重试: {}", attempt, delay, e.getMessage());
            notifyListeners(listener -> listener.onRegisterFailed(serviceName, attempt, e));
            scheduleAttempt(delay);
        }
    }

    private void onHeartbeatFailed(Registration failedRegistration) {
        if (stopped) {
            return;
        }
        log.warn("注册心跳失败（instance={}），将重建注册以刷新租约", registeredView);
        scheduleRecovery();
    }

    private void scheduleRecovery() {
        if (stopped) {
            return;
        }
        try {
            scheduler.schedule(this::recoverRegistration,
                    HEARTBEAT_RECOVERY_DELAY.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // 停机竞态：调度器已关闭
        }
    }

    private synchronized void recoverRegistration() {
        if (stopped) {
            return;
        }
        Registration current = this.registration;
        if (current == null) {
            // 已由其他路径处理（并发恢复 / 已注销）
            return;
        }
        this.registration = null;
        // 先尽力注销旧注册（若 Agent 可达可避免旧心跳残留），再重建；旧句柄保留在
        // allRegistrations 中随停机一并关闭，杜绝停机后的僵尸心跳
        closeQuietly(current);
        attemptRegister();
    }

    private void closeQuietly(Registration handle) {
        try {
            handle.close(RELEASE_TIMEOUT);
        } catch (RuntimeException e) {
            log.warn("关闭旧注册句柄失败（依赖 TTL 过期兜底）: {}", e.getMessage());
        }
    }

    private Duration backoff(int attempt) {
        long millis = initialBackoff.toMillis() * (1L << Math.min(attempt - 1, 10));
        return Duration.ofMillis(Math.min(millis, maxBackoff.toMillis()));
    }

    private String resolveServiceName() {
        if (StringUtils.hasText(properties.getServiceName())) {
            return properties.getServiceName().trim();
        }
        String fromEnvironment = environment.getProperty("spring.application.name");
        return StringUtils.hasText(fromEnvironment) ? fromEnvironment.trim() : null;
    }

    private String resolveHost() {
        if (StringUtils.hasText(properties.getHost())) {
            return properties.getHost().trim();
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            log.warn("本机 hostname 解析失败，注册地址回退 localhost: {}", e.getMessage());
            return "localhost";
        }
    }

    private void notifyListeners(java.util.function.Consumer<CoordRegistrationListener> action) {
        for (CoordRegistrationListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (RuntimeException e) {
                log.warn("注册生命周期回调执行失败（已忽略）: {}", e.getMessage());
            }
        }
    }
}
