package cn.byteforce.seedj.infra.coord.fixture;

import cn.byteforce.coord.sdk.CoordClient;
import cn.byteforce.coord.sdk.CoordConfig;
import cn.byteforce.coord.sdk.CoordException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Assumptions;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * coord v2 测试集群：server + agent 两个容器，data_plane=v2，MQ v2 可用。
 *
 * <p>agent 拒绝非 loopback 上的明文绑定，而 {@code agent.toml} 不能开 dev 旁路；因此两个容器都用
 * host network 绑定 127.0.0.1，测试 JVM 直接连随机端口。仅 Linux 可用，无 Docker 时调用方跳过。
 */
public final class CoordCluster implements AutoCloseable {

    private static final String IMAGE =
            System.getProperty("seedj.coord.test.image", "ghcr.io/byteforce-cn/coord:v0.4.3");
    private static final Duration STARTUP = Duration.ofSeconds(90);

    private final GenericContainer<?> server;
    private final GenericContainer<?> agent;
    private final int agentPort;
    private final CoordClient client;
    private final Path workDir;

    private CoordCluster(
            GenericContainer<?> server,
            GenericContainer<?> agent,
            int agentPort,
            CoordClient client,
            Path workDir) {
        this.server = server;
        this.agent = agent;
        this.agentPort = agentPort;
        this.client = client;
        this.workDir = workDir;
    }

    public static CoordCluster start() {
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
            server.start();
            awaitHealth(http);

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
                                    Wait.forLogMessage(
                                                    ".*coord-agent gRPC server listening.*", 1)
                                            .withStartupTimeout(STARTUP))
                            .withLogConsumer(
                                    frame ->
                                            System.out.print(
                                                    "[coord-agent] "
                                                            + frame.getUtf8StringWithoutLineEnding()
                                                            + System.lineSeparator()));
            agent.start();

            CoordClient client =
                    CoordClient.create(
                            CoordConfig.builder()
                                    .agentHost("127.0.0.1")
                                    .agentPort(agentPort)
                                    .requestTimeout(Duration.ofSeconds(5))
                                    .allowInsecurePlaintext(true)
                                    .build());
            awaitIdgen(client);
            return new CoordCluster(server, agent, agentPort, client, workDir);
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

    @Override
    public void close() {
        try {
            client.close();
        } finally {
            agent.stop();
            server.stop();
        }
    }

    private static void awaitHealth(int httpPort) {
        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                java.net.http.HttpResponse<Void> response =
                        http.send(
                                java.net.http.HttpRequest.newBuilder()
                                        .uri(java.net.URI.create("http://127.0.0.1:" + httpPort + "/healthz"))
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
