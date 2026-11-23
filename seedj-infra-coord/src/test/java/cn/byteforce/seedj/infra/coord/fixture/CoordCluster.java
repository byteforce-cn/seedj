package cn.byteforce.seedj.infra.coord.fixture;

import cn.byteforce.coord.sdk.CoordClient;
import cn.byteforce.coord.sdk.CoordConfig;
import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.coord.sdk.txn.TxnClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * coord v2 测试集群：server + agent 两个容器，data_plane=v2，MQ v2 可用。
 *
 * <p>agent 拒绝非 loopback 上的明文绑定，而 {@code agent.toml} 不能开 dev 旁路；因此两个容器都用 host network 绑定
 * 127.0.0.1，测试 JVM 直接连随机端口。仅 Linux 可用，无 Docker 时调用方跳过。
 */
public final class CoordCluster implements AutoCloseable {

    private static final String IMAGE =
            System.getProperty("seedj.coord.test.image", "ghcr.io/byteforce-cn/coord:v0.4.6");
    private static final Duration STARTUP = Duration.ofSeconds(90);

    private final GenericContainer<?> server;
    private final GenericContainer<?> agent;
    private final int agentPort;
    private final CoordClient client;
    private final Path workDir;

    private final String token;

    private CoordCluster(
            GenericContainer<?> server,
            GenericContainer<?> agent,
            int agentPort,
            CoordClient client,
            Path workDir,
            String token) {
        this.server = server;
        this.agent = agent;
        this.agentPort = agentPort;
        this.client = client;
        this.workDir = workDir;
        this.token = token;
    }

    public static CoordCluster start() {
        return start(false);
    }

    /**
     * 带认证的集群：server 启用 CCT、agent 用 bootstrap token 自注册；返回的 client 携带 root CCT。
     *
     * <p>审批的 {@code on_behalf_of} 只在调用方主体非 {@code anonymous} 时可用（coord 0.4.6），
     * 所以 workflow 契约测试必须跑在认证集群上。
     */
    public static CoordCluster startWithAuth() {
        return start(true);
    }

    private static CoordCluster start(boolean auth) {
        Assumptions.assumeTrue(
                System.getProperty("os.name", "").toLowerCase().contains("linux"),
                "coord host-network fixture requires Linux");
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "docker is required");

        int grpc = freePort();
        int raft = freePort();
        int http = freePort();
        int agentPort = freePort();
        int agentHttp = freePort();

        try {
            Path workDir = Files.createTempDirectory("seedj-coord-cluster-");
            Path serverConfig =
                    render(
                            workDir.resolve("server.toml"),
                            "coord/server.toml",
                            grpc,
                            raft,
                            http,
                            agentPort,
                            agentHttp);
            Path agentConfig =
                    render(
                            workDir.resolve("agent.toml"),
                            "coord/agent.toml",
                            grpc,
                            raft,
                            http,
                            agentPort,
                            agentHttp);
            if (auth) {
                enableAuth(serverConfig, agentConfig);
            }

            GenericContainer<?> server =
                    new GenericContainer<>(DockerImageName.parse(IMAGE))
                            .withNetworkMode("host")
                            .withFileSystemBind(
                                    serverConfig.toString(),
                                    "/etc/coord/server.toml",
                                    BindMode.READ_ONLY)
                            .withCommand(
                                    "--config",
                                    "/etc/coord/server.toml",
                                    "server",
                                    "--addr",
                                    "127.0.0.1:" + grpc,
                                    "--raft-addr",
                                    "127.0.0.1:" + raft,
                                    "--cluster-name",
                                    "seedj-fixture")
                            .waitingFor(
                                    Wait.forLogMessage(".*Coord server .* started.*", 1)
                                            .withStartupTimeout(STARTUP))
                            .withLogConsumer(
                                    frame ->
                                            System.out.print(
                                                    "[coord-server] "
                                                            + frame.getUtf8StringWithoutLineEnding()
                                                            + System.lineSeparator()));
            String logLevel = System.getProperty("seedj.coord.test.log", "");
            if (!logLevel.isBlank()) {
                server.withEnv("RUST_LOG", logLevel);
            }
            server.start();
            awaitHealth(http);

            String token = auth ? loginRoot(server, grpc) : null;
            if (token != null) {
                bootstrapAgentRole(server, grpc, token);
                grantWorkflowMq(server, grpc, token);
            }

            GenericContainer<?> agent =
                    new GenericContainer<>(DockerImageName.parse(IMAGE))
                            .withNetworkMode("host")
                            .withEnv("COORD_TRANSIT_KEK", "11".repeat(32))
                            .withFileSystemBind(
                                    agentConfig.toString(),
                                    "/etc/coord/agent.toml",
                                    BindMode.READ_ONLY)
                            .withCommand("agent", "--agent-config", "/etc/coord/agent.toml")
                            .waitingFor(
                                    Wait.forLogMessage(".*coord-agent gRPC server listening.*", 1)
                                            .withStartupTimeout(STARTUP))
                            .withLogConsumer(
                                    frame ->
                                            System.out.print(
                                                    "[coord-agent] "
                                                            + frame.getUtf8StringWithoutLineEnding()
                                                            + System.lineSeparator()));
            if (!logLevel.isBlank()) {
                agent.withEnv("RUST_LOG", logLevel);
            }
            agent.start();

            CoordConfig.Builder config =
                    CoordConfig.builder()
                            .agentHost("127.0.0.1")
                            .agentPort(agentPort)
                            .requestTimeout(Duration.ofSeconds(5))
                            .allowInsecurePlaintext(true);
            if (token != null) {
                String cct = token;
                config.authTokenSupplier(() -> cct);
            }
            CoordClient client = CoordClient.create(config.build());
            awaitIdgen(client);
            awaitKv(client);
            return new CoordCluster(server, agent, agentPort, client, workDir, token);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String agentHost() {
        return "127.0.0.1";
    }

    public int agentPort() {
        return agentPort;
    }

    public CoordClient client() {
        return client;
    }

    /** 认证集群的 root CCT；未启用认证时为 {@code null}。 */
    public String token() {
        return token;
    }

    @Override
    public void close() {
        try {
            client.close();
        } finally {
            agent.stop();
            server.stop();
        }
    }

    private static void enableAuth(Path serverConfig, Path agentConfig) throws IOException {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        String rootKey = java.util.HexFormat.of().formatHex(key);
        String verifyingKey = verifyingKey(rootKey);
        String server = Files.readString(serverConfig, StandardCharsets.UTF_8);
        server =
                server.replace(
                        "[security]\nauth_enabled = false",
                        "[security]\nauth_enabled = true\nauth_root_key = \""
                                + rootKey
                                + "\"\nroot_password = \"root-test-password\"\n"
                                + "agent_bootstrap_tokens = [\"bootstrap-token-for-tests\"]");
        Files.writeString(serverConfig, server, StandardCharsets.UTF_8);

        String agent = Files.readString(agentConfig, StandardCharsets.UTF_8);
        agent =
                agent.replace(
                        "[auth]\nenabled = false",
                        "[auth]\nenabled = true\nverifying_key_hex = \""
                                + verifyingKey
                                + "\"\nbootstrap_token = \"bootstrap-token-for-tests\"");
        Files.writeString(agentConfig, agent, StandardCharsets.UTF_8);
    }

    /** 与服务端 `TokenSigningKeyring` 同一 HKDF 路径派生 Ed25519 公钥。 */
    private static String verifyingKey(String rootKeyHex) {
        byte[] root = java.util.HexFormat.of().parseHex(rootKeyHex);
        var hkdf =
                new org.bouncycastle.crypto.generators.HKDFBytesGenerator(
                        new org.bouncycastle.crypto.digests.SHA256Digest());
        hkdf.init(
                new org.bouncycastle.crypto.params.HKDFParameters(
                        root, null, "coord-cct-ed25519-v1".getBytes(StandardCharsets.UTF_8)));
        byte[] seed = new byte[32];
        hkdf.generateBytes(seed, 0, seed.length);
        var privateKey =
                new org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(seed, 0);
        return java.util.HexFormat.of().formatHex(privateKey.generatePublicKey().getEncoded());
    }

    private static void grantWorkflowMq(
            GenericContainer<?> server, int grpc, String rootToken) {
        try {
            server.execInContainer(
                    "coord",
                    "auth",
                    "role",
                    "add",
                    "agent-self-role",
                    "--token",
                    rootToken,
                    "--addr",
                    "127.0.0.1:" + grpc);
        } catch (Exception e) {
            System.out.println("[coord-server] agent-self-role add failed: " + e);
        }
        for (String capability :
                java.util.List.of(
                        "coord:mq:manage",
                        "coord:mq:publish",
                        "coord:mq:subscribe",
                        "coord:mq:consume",
                        "data:lease:grant",
                        "data:lease:read")) {
            try {
                var result =
                        server.execInContainer(
                                "coord",
                                "auth",
                                "role",
                                "grant-capability",
                                "agent-self-role",
                                capability,
                                "--token",
                                rootToken,
                                "--addr",
                                "127.0.0.1:" + grpc);
                if (result.getExitCode() != 0) {
                    System.out.println(
                            "[coord-server] grant "
                                    + capability
                                    + " retry: "
                                    + result.getStderr());
                    sleep(500);
                }
            } catch (Exception e) {
                System.out.println("[coord-server] grant " + capability + " failed: " + e);
            }
        }
    }

    private static void bootstrapAgentRole(
            GenericContainer<?> server, int grpc, String rootToken) {
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                var result =
                        server.execInContainer(
                                "coord",
                                "security",
                                "bootstrap-role",
                                "--token",
                                rootToken,
                                "--addr",
                                "127.0.0.1:" + grpc);
                if (result.getExitCode() == 0) {
                    System.out.println("[coord-server] agent bootstrap role ready");
                    return;
                }
                System.out.println(
                        "[coord-server] bootstrap-role retry: " + result.getStderr());
            } catch (Exception e) {
                System.out.println("[coord-server] bootstrap-role retry: " + e.getMessage());
            }
            sleep(500);
        }
        throw new IllegalStateException("coord bootstrap-role did not succeed");
    }

    private static String loginRoot(GenericContainer<?> server, int grpc) {
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                var result =
                        server.execInContainer(
                                "coord",
                                "auth",
                                "login",
                                "root",
                                "--password",
                                "root-test-password",
                                "--token-only",
                                "--no-save",
                                "--addr",
                                "127.0.0.1:" + grpc);
                String token = result.getStdout().trim();
                if (result.getExitCode() == 0 && !token.isEmpty()) {
                    return token.lines().reduce((first, second) -> second).orElse(token).trim();
                }
                System.out.println("[coord-server] auth login retry: " + result.getStderr());
            } catch (Exception e) {
                System.out.println("[coord-server] auth login retry: " + e.getMessage());
            }
            sleep(500);
        }
        throw new IllegalStateException("coord root login did not succeed");
    }

    private static void awaitHealth(int httpPort) {
        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                java.net.http.HttpResponse<Void> response =
                        http.send(
                                java.net.http.HttpRequest.newBuilder()
                                        .uri(
                                                java.net.URI.create(
                                                        "http://127.0.0.1:"
                                                                + httpPort
                                                                + "/healthz"))
                                        .timeout(Duration.ofSeconds(2))
                                        .GET()
                                        .build(),
                                java.net.http.HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) {
                    sleep(1500);
                    return;
                }
            } catch (IOException e) {
                // not up yet
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for coord health", e);
            }
            sleep(200);
        }
        throw new IllegalStateException("coord server healthz did not become ready on " + httpPort);
    }

    /**
     * 等 KV 事务面真正可用。
     *
     * <p>单节点 v2 集群刚起来时 {@code TxnClient.begin()} 会短暂抛 {@code read_ts exceeds safe_ts} （global apply
     * lag）；idgen 就绪不代表 KV 就绪。生产侧这类错误映射成可重试码，夹具这里直接等它稳定， 免得每个用例各自撞一次。
     */
    private static void awaitKv(CoordClient client) {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        RuntimeException last = null;
        while (System.nanoTime() < deadline) {
            TxnClient.Session session = null;
            try {
                session = client.tx().begin();
                session.read("seedj-fixture-ready".getBytes(StandardCharsets.UTF_8));
                session.commit(List.of());
                return;
            } catch (RuntimeException e) {
                last = e;
                if (session != null) {
                    try {
                        session.abort();
                    } catch (RuntimeException ignored) {
                        // 已失效的会话，忽略
                    }
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while waiting for coord kv", last);
                }
            }
        }
        throw new IllegalStateException("coord kv not ready within 60s", last);
    }

    private static void awaitIdgen(CoordClient client) {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        CoordException last = null;
        while (System.nanoTime() < deadline) {
            try {
                client.idgen().nextId("seedj-boot");
                return;
            } catch (CoordException e) {
                last = e;
                sleep(200);
            }
        }
        throw new IllegalStateException("coord agent did not become ready", last);
    }

    private static Path render(
            Path target,
            String template,
            int grpc,
            int raft,
            int http,
            int agentPort,
            int agentHttp)
            throws IOException {
        String text;
        try (var in = CoordCluster.class.getResourceAsStream("/" + template)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture template: " + template);
            }
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        text =
                text.replace("__GRPC__", Integer.toString(grpc))
                        .replace("__RAFT__", Integer.toString(raft))
                        .replace("__HTTP__", Integer.toString(http))
                        .replace("__AGENT__", Integer.toString(agentPort))
                        .replace("__AGENT_HTTP__", Integer.toString(agentHttp));
        Files.writeString(target, text, StandardCharsets.UTF_8);
        return target;
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for coord", e);
        }
    }
}
