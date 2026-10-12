package cn.byteforce.seedj.starter.coord.it;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;

/**
 * coord 集成测试容器（Testcontainers）。
 *
 * <p>单容器内拉起一套「单机语义」的 coord 环境：Server + Agent + loopback
 * 转发器，供 {@code starter-coord} 在真实 gRPC 链路上做集成验证。</p>
 *
 * <h3>为什么不是 {@code coord dev}（v0.2.0 实测结论）</h3>
 * <ul>
 *   <li><b>服务未启用</b>：dev 模式不开启任何可选服务（ADR-0001「默认关」），
 *       Config/Cache 等 RPC 实测返回 {@code UNIMPLEMENTED}，无法覆盖本模块要验证的
 *       config / registry / cache / mq / workflow / transit；</li>
 *   <li><b>绑定受限</b>：dev 模式绑定非 loopback 会被 Raft fail-closed（R-SEC-03）
 *       拒绝启动，而容器端口映射只能命中非 loopback 监听；</li>
 *   <li><b>折中方案</b>：改用与上游 {@code scripts/ci-java-it-cluster.sh} 相同的
 *       server + agent 进程组合（loopback、免鉴权），服务经 {@code agent.toml}
 *       显式开启，并由容器内置的 perl 转发器把 Agent 端口暴露到
 *       {@code 0.0.0.0:29527}，从而被宿主机端口映射访问。</li>
 * </ul>
 *
 * <h3>镜像</h3>
 * <p>默认 {@code ghcr.io/byteforce-cn/coord:v0.3.0}，可用系统属性
 * {@code -Dcoord.it.image=<image>} 或环境变量 {@code COORD_IT_IMAGE} 覆盖。
 * 运行前需保证本机 Docker 可用且镜像已拉取（{@code docker pull ...}）。</p>
 *
 * <h3>生命周期</h3>
 * <p>JVM 级单例：整个测试 JVM 只启动一次，跨测试类复用（Spring TestContext
 * 缓存亦按同一组属性复用 ApplicationContext）；JVM 退出时通过 shutdown hook 停止
 * 容器（Testcontainers Ryuk 兜底清理）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class CoordItContainer extends GenericContainer<CoordItContainer> {

    private static final Logger log = LoggerFactory.getLogger(CoordItContainer.class);

    /** 默认镜像（与 seedj BOM 中 coord-java-sdk 的推荐服务端版本一致）。 */
    private static final String DEFAULT_IMAGE = "ghcr.io/byteforce-cn/coord:v0.3.0";

    /** 镜像覆盖系统属性。 */
    public static final String IMAGE_PROPERTY = "coord.it.image";

    /** 镜像覆盖环境变量。 */
    public static final String IMAGE_ENV = "COORD_IT_IMAGE";

    /** Agent gRPC 宿主端口（转发器监听，Testcontainers 映射到随机宿主端口）。 */
    public static final int AGENT_GRPC_PORT = 29527;

    /** Agent HTTP 宿主端口（转发器监听；健康/指标诊断用）。 */
    public static final int AGENT_HTTP_PORT = 29528;

    /**
     * 容器内 Agent 就绪日志（所有插件启动完成后才会打印，见 coord-agent)。
     * 用日志而非端口探测：转发器端口先于 Agent 就绪监听，无法表达「服务可用」。
     */
    private static final String READY_LOG = ".*coord-agent gRPC server listening.*";

    /** 启动超时（含 server 选主 + agent 全插件启动）。 */
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(3);

    /**
     * Transit 服务 KEK 材料（hex64 = 32 字节；启用 transit 必须注入，否则 Agent
     * 拒绝启动）。仅测试环境使用，固定值便于断言一致性。
     */
    static final String TEST_TRANSIT_KEK =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private CoordItContainer(DockerImageName image) {
        super(image);

        // 镜像 ENTRYPOINT 是 coord 二进制，这里改为执行测试环境启动脚本
        withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("bash"));
        withCommand("/opt/coord-it/start.sh");

        withCopyFileToContainer(
                MountableFile.forClasspathResource("coord-it/start.sh", 0755),
                "/opt/coord-it/start.sh");
        withCopyFileToContainer(
                MountableFile.forClasspathResource("coord-it/fwd.pl", 0755),
                "/opt/coord-it/fwd.pl");
        withCopyFileToContainer(
                MountableFile.forClasspathResource("coord-it/server.toml", 0644),
                "/opt/coord-it/server.toml");
        withCopyFileToContainer(
                MountableFile.forClasspathResource("coord-it/agent.toml", 0644),
                "/opt/coord-it/agent.toml");

        withEnv("COORD_TRANSIT_KEK", TEST_TRANSIT_KEK);
        withExposedPorts(AGENT_GRPC_PORT, AGENT_HTTP_PORT);

        waitingFor(Wait.forLogMessage(READY_LOG, 1));
        withStartupTimeout(STARTUP_TIMEOUT);

        // 容器日志直通测试日志；过滤 raft_wm 心跳（每条 1 次，噪音大）
        withLogConsumer(frame -> {
            String line = frame.getUtf8String();
            if (line != null && !line.isBlank() && !line.contains("raft_wm")) {
                log.info("[coord-it] {}", line.stripTrailing());
            }
        });
    }

    /**
     * 获取 JVM 级单例（首次访问时启动容器）。
     *
     * @return 已启动的容器实例
     */
    public static CoordItContainer getInstance() {
        return Holder.INSTANCE;
    }

    /**
     * Agent gRPC 主机（Testcontainers 语义：连接宿主机上的映射端口）。
     */
    public String agentHost() {
        return getHost();
    }

    /**
     * Agent gRPC 端口（映射到宿主机的随机端口）。
     *
     * @see #AGENT_GRPC_PORT
     */
    public int agentPort() {
        return getMappedPort(AGENT_GRPC_PORT);
    }

    /**
     * 解析镜像名：系统属性 &gt; 环境变量 &gt; 默认值。
     */
    private static DockerImageName resolveImage() {
        String image = System.getProperty(IMAGE_PROPERTY,
                System.getenv().getOrDefault(IMAGE_ENV, DEFAULT_IMAGE));
        log.info("coord 集成测试镜像: {}", image);
        return DockerImageName.parse(image);
    }

    private static CoordItContainer startInstance() {
        CoordItContainer container = new CoordItContainer(resolveImage());
        try {
            container.start();
        } catch (RuntimeException e) {
            throw new IllegalStateException("coord 集成测试容器启动失败："
                    + "请确认本机 Docker 可用且镜像已拉取（docker pull " + container.getDockerImageName()
                    + "；可用 -D" + IMAGE_PROPERTY + "=<image> 覆盖）。失败原因: " + e.getMessage(), e);
        }        // 系统属性通道：ConfigData 引导阶段（spring.config.import 的 resolver / loader）
        // 早于 @DynamicPropertySource 生效时机，必须经系统属性（环境最早可见源）找到 Agent；
        // 运行期客户端仍由 @DynamicPropertySource 提供同一组地址，两者同值不冲突。
        System.setProperty("byteforce.coord.agent-host", container.agentHost());
        System.setProperty("byteforce.coord.agent-port", String.valueOf(container.agentPort()));        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                container.stop();
            } catch (RuntimeException ignored) {
                // 关闭钩子内不容忍异常：Ryuk 会兜底清理残留容器
            }
        }, "coord-it-stop"));
        log.info("coord 集成测试容器就绪: agent={}:{}", container.agentHost(), container.agentPort());
        return container;
    }

    /** 持有单例；类初始化由 JVM 保证线程安全。 */
    private static final class Holder {
        private static final CoordItContainer INSTANCE = startInstance();
    }
}
