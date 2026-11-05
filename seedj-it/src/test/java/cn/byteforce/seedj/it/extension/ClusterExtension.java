package cn.byteforce.seedj.it.extension;

import cn.byteforce.seedj.pki.BcPkiProvider;
import cn.byteforce.seedj.pki.PkiProvider;
import cn.byteforce.seedj.pki.PkiProvider.IssuedCertificate;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.extension.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;
import org.testcontainers.containers.wait.strategy.WaitStrategy;
import org.testcontainers.utility.DockerImageName;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ClusterExtension implements BeforeAllCallback, AfterAllCallback, ParameterResolver {

    private static final char[] PASSWORD = "test-password".toCharArray();
    private static final String PASSWORD_STR = "test-password";
    private static final String CA_ALIAS = "pki-test-ca";
    private static final String TLS_ALIAS = "iam-test";

    /** 注册到 Spring 上下文中作为 JwtDecoder 出站身份的 SSLContext bean 名 */
    private static final String CLIENT_SSL_BEAN_NAME = "backendClientSslContext";

    // ==================== 服务定义（新增服务 = 加一行） ====================

    private record ServiceDefinition(String name, String appName, Class<?> mainClass,
                                     List<Class<?>> sources) {}

    /** IAM 是特殊角色：HTTPS + mTLS server + 签发 issuer，单独处理 */
    private static final ServiceDefinition IAM_SERVICE =
            new ServiceDefinition("iam", "iam-node-1", cn.byteforce.seedj.server.iam.IamServer.class,
                    List.of(cn.byteforce.seedj.it.fixture.iam.IamTestUsers.class));

    /** 后端服务（资源服务器）：自动签发客户端证书 + 覆盖 JwtDecoder */
    private static final List<ServiceDefinition> BACKEND_SERVICES = List.of(
            new ServiceDefinition("im", "im-node-1", cn.byteforce.seedj.server.im.ImServer.class,
                    List.of(cn.byteforce.seedj.it.fixture.im.ImTestController.class)),
            new ServiceDefinition("hr", "hr-node-1", cn.byteforce.seedj.server.hr.HrServer.class,
                    List.of(cn.byteforce.seedj.it.fixture.hr.HrTestController.class,
                            cn.byteforce.seedj.it.fixture.hr.HrEventSubscriber.class))
            // 新增：new ServiceDefinition("xx", "xx-node-1", cn.byteforce.seedj.server.xx.XxServer.class,
            //        List.of())
    );

    // ==================== 运行期状态 ====================

    private final List<ConfigurableApplicationContext> contexts = new ArrayList<>();
    private final Map<String, RestTestClient> clients = new HashMap<>();
    private final Map<String, SSLContext> backendClientSslContexts = new HashMap<>();

    private PostgreSQLContainer<?> postgres;
    private Path tempDir;
    private Path caP12;
    private Path iamKeyStore;
    private Path iamTrustStore;
    private Path pkiStateFile;
    private PkiProvider pkiProvider;
    private SSLContext testClientSslContext;
    private SSLContext untrustedClientSslContext;

    // ==================== 生命周期 ====================

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        try {
            // 0. 解析本测试类需要启动的服务
            Set<String> enabledServices = resolveEnabledServices(context);
            log.info("本次启动的集群服务: {}", enabledServices);

            // 1. 临时目录
            this.tempDir = Files.createTempDirectory("seedj-it-");
            log.info("测试临时目录: {}", tempDir);

            // 2. 动态 CA
            this.caP12 = createCaP12(tempDir);

            // 3. PKI 状态与 provider（ClusterExtension 侧）
            this.pkiStateFile = tempDir.resolve("pki-state.properties");
            this.pkiProvider = new BcPkiProvider(
                    caP12.toUri().toString(), PASSWORD, CA_ALIAS, pkiStateFile);

            // 4. IAM server cert + test client cert
            IssuedCertificate iamServerCert = pkiProvider.issue(
                    PkiProvider.CertificateRequest.server(
                            "CN=localhost, O=Byteforce",
                            List.of("localhost"),
                            List.of("127.0.0.1")));
            IssuedCertificate testClientCert = pkiProvider.issue(
                    PkiProvider.CertificateRequest.client("CN=test-client, O=Byteforce"));
            log.info("IAM server cert serial={}, test client cert serial={}",
                    iamServerCert.serialNumber(), testClientCert.serialNumber());

            // 只给"启用"的后端服务签发客户端证书
            for (ServiceDefinition svc : BACKEND_SERVICES) {
                if (!enabledServices.contains(svc.name())) {
                    continue;
                }
                IssuedCertificate cert = pkiProvider.issue(
                        PkiProvider.CertificateRequest.client(
                                "CN=" + svc.appName() + ", O=Byteforce"));
                backendClientSslContexts.put(svc.name(), pkiProvider.sslContext(cert));
                log.info("服务 {} 客户端证书 serial={}", svc.name(), cert.serialNumber());
            }

            // 5. IAM keystore（leaf + CA 链）、IAM truststore（CA）
            this.iamKeyStore = writeKeyStore(
                    tempDir.resolve("iam-test-keystore.p12"), iamServerCert);
            this.iamTrustStore = writeTrustStore(
                    tempDir.resolve("iam-test-truststore.p12"), pkiProvider.caCertificate());

            // 6. 测试客户端 SSLContext
            this.testClientSslContext = pkiProvider.sslContext(testClientCert);

            // 7. 负向 mTLS：第二套 CA 签发客户端证书，IAM 只信任第一套 CA
            Path untrustedCaDir = Files.createDirectories(tempDir.resolve("untrusted-ca"));
            Path untrustedCaP12 = createCaP12(untrustedCaDir);
            BcPkiProvider untrustedPki = new BcPkiProvider(
                    untrustedCaP12.toUri().toString(), PASSWORD, CA_ALIAS,
                    tempDir.resolve("untrusted-pki-state.properties"));
            IssuedCertificate untrustedClientCert = untrustedPki.issue(
                    PkiProvider.CertificateRequest.client("CN=untrusted-client"));
            this.untrustedClientSslContext = pkiProvider.sslContext(untrustedClientCert);

            // 8. Postgres
            // 使用官方镜像并固定大版本：第三方 "batteries" 镜像的初始化脚本曾导致
            // 容器在 initdb 阶段直接退出，且 latest 标签会随上游漂移。
            DockerImageName pgImage = DockerImageName.parse("postgres:17-alpine");
            // entrypoint 会先启动一个临时实例完成 initdb/初始化脚本，再启动正式实例，
            // 因此 "ready to accept connections" 会出现两次；只等待一次会把临时
            // 实例误判为就绪（正式实例尚未监听 TCP 时就会开始连接）。
            WaitStrategy waitStrategy = new LogMessageWaitStrategy()
                    .withRegEx(".*database system is ready to accept connections.*\\s")
                    .withTimes(2)
                    .withStartupTimeout(Duration.ofSeconds(120));
            postgres = new PostgreSQLContainer<>(pgImage)
                    .withDatabaseName("seedj_test_db")
                    .withUsername("test_user")
                    .withPassword("test_password")
                    .waitingFor(waitStrategy);
            postgres.start();
            log.info("PostgreSQL started at URL: {}", postgres.getJdbcUrl());

            // 9. 公共属性
            Map<String, Object> commonProps = buildCommonProperties();

            // 10. IAM：HTTPS + mTLS
            int iamPort = findFreePort();
            String iamIssuer = "https://localhost:" + iamPort;
            Map<String, Object> iamProps = new HashMap<>(commonProps);
            iamProps.put("spring.security.oauth2.authorizationserver.issuer", iamIssuer);
            iamProps.put("seedj.iam.demo.enabled", true);
            iamProps.put("seedj.iam.jwk.provider", "pki");
            iamProps.put("seedj.iam.store", "memory");
            iamProps.put("server.port", iamPort);
            iamProps.put("server.ssl.enabled", true);
            iamProps.put("server.ssl.key-store", iamKeyStore.toUri().toString());
            iamProps.put("server.ssl.key-store-password", PASSWORD_STR);
            iamProps.put("server.ssl.key-store-type", "PKCS12");
            iamProps.put("server.ssl.key-alias", TLS_ALIAS);
            // 生产语义：客户端证书可选（浏览器可匿名访问 discovery/login/authorize）；
            // tls_client_auth 的强制执行由 SAS 按 RegisteredClient 的认证方式完成。
            iamProps.put("server.ssl.client-auth", "want");
            iamProps.put("server.ssl.trust-store", iamTrustStore.toUri().toString());
            iamProps.put("server.ssl.trust-store-password", PASSWORD_STR);
            iamProps.put("server.ssl.trust-store-type", "PKCS12");
            log.info("动态分配的 IAM 服务地址 (optional mTLS): {}", iamIssuer);

            ConfigurableApplicationContext iamCtx = startApp(
                    IAM_SERVICE.appName(), IAM_SERVICE.mainClass(), iamProps, null,
                    IAM_SERVICE.sources().toArray(Class<?>[]::new));
            registerClient(IAM_SERVICE.name(), iamCtx, testClientSslContext, true);

            // 11. 只启动"启用"的后端服务
            for (ServiceDefinition svc : BACKEND_SERVICES) {
                if (!enabledServices.contains(svc.name())) {
                    continue;
                }
                SSLContext clientSsl = backendClientSslContexts.get(svc.name());
                Map<String, Object> props = new HashMap<>(commonProps);
                props.put("server.port", 0);
                props.put("spring.security.oauth2.resourceserver.jwt.issuer-uri", iamIssuer);

                Class<?>[] sources = Stream.concat(
                        Stream.of(TestJwtDecoderConfiguration.class),
                        svc.sources().stream())
                        .toArray(Class<?>[]::new);
                ConfigurableApplicationContext ctx = startApp(
                        svc.appName(), svc.mainClass(), props,
                        c -> c.getBeanFactory().registerSingleton(CLIENT_SSL_BEAN_NAME, clientSsl),
                        sources);
                registerClient(svc.name(), ctx, clientSsl, false);
            }

        } catch (Exception e) {
            afterAll(context);
            throw e;
        }
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        for (int i = contexts.size() - 1; i >= 0; i--) {
            if (contexts.get(i).isActive()) {
                contexts.get(i).close();
            }
        }
        contexts.clear();
        clients.clear();
        backendClientSslContexts.clear();
        pkiProvider = null;
        testClientSslContext = null;
        untrustedClientSslContext = null;

        if (postgres != null && postgres.isRunning()) {
            postgres.stop();
        }

        if (tempDir != null && Files.exists(tempDir)) {
            try (Stream<Path> stream = Files.walk(tempDir)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // 清理失败不影响测试结果
                    }
                });
            }
            log.info("已清理测试临时目录: {}", tempDir);
            tempDir = null;
        }
    }

    // ==================== 服务启用解析 ====================

    /** 全部已知服务名（IAM + 所有后端） */
    private Set<String> knownServiceNames() {
        Set<String> names = new HashSet<>();
        names.add(IAM_SERVICE.name());
        BACKEND_SERVICES.forEach(s -> names.add(s.name()));
        return names;
    }

    /**
     * 从测试类上的 {@link EnableClusterServices} 解析需要启动的服务。
     * <ul>
     *   <li>无注解 / value 为空 → 启动全部（向后兼容）</li>
     *   <li>点名了任意后端 → 自动加入 IAM</li>
     *   <li>点名了未知服务 → 启动期直接报错</li>
     * </ul>
     */
    private Set<String> resolveEnabledServices(ExtensionContext context) {
        EnableClusterServices ann = AnnotatedElementUtils.findMergedAnnotation(
                context.getRequiredTestClass(), EnableClusterServices.class);

        Set<String> known = knownServiceNames();
        Set<String> enabled = new LinkedHashSet<>();

        if (ann == null || ann.value().length == 0) {
            enabled.addAll(known);
            return enabled;
        }

        for (String name : ann.value()) {
            if (!known.contains(name)) {
                throw new IllegalStateException(
                        "未知服务 [" + name + "]，可用服务: " + known);
            }
            enabled.add(name);
        }

        // 后端服务依赖 IAM（JWT issuer）
        if (enabled.stream().anyMatch(n -> !n.equals(IAM_SERVICE.name()))) {
            enabled.add(IAM_SERVICE.name());
        }
        return enabled;
    }

    // ==================== 内部辅助 ====================

    private Map<String, Object> buildCommonProperties() {
        Map<String, Object> props = new HashMap<>();
        props.put("management.endpoint.health.show-details", "always");
        props.put("management.endpoints.web.exposure.include", "*");
        props.put("spring.datasource.url", postgres.getJdbcUrl());
        props.put("spring.datasource.username", postgres.getUsername());
        props.put("spring.datasource.password", postgres.getPassword());
        props.put("spring.main.allow-bean-definition-overriding", true);
        props.put("seedj.pki.enabled", true);
        props.put("seedj.pki.ca-resource", caP12.toUri().toString());
        props.put("seedj.pki.ca-password", PASSWORD_STR);
        props.put("seedj.pki.ca-alias", CA_ALIAS);
        props.put("seedj.pki.revocation-store", pkiStateFile.toString());

        // Transit：多服务共享同一状态文件
        props.put("seedj.transit.enabled", true);
        props.put("seedj.transit.persist", true);
        props.put("seedj.transit.state-file", tempDir.resolve("transit-state.properties").toString());

        // EventBus：磁盘队列（多服务共享目录）+ PostgreSQL outbox
        props.put("seedj.eventbus.enabled", true);
        props.put("seedj.eventbus.transport", "file");
        props.put("seedj.eventbus.file.base-dir", tempDir.resolve("eventbus").toString());
        props.put("seedj.outbox.relay.enabled", true);
        props.put("seedj.outbox.relay.poll-interval", "100ms");
        props.put("seedj.outbox.relay.lease", "10s");
        props.put("seedj.outbox.enabled", true);
        props.put("seedj.eventbus.consumer.poll-interval", "100ms");
        props.put("seedj.eventbus.consumer.lease", "10s");
        props.put("seedj.eventbus.consumer.max-attempts", "5");

        // ID：多服务从共享文件领取不同节点编号
        props.put("seedj.id.enabled", true);
        props.put("seedj.id.node-state-file", tempDir.resolve("id-nodes.properties").toString());

        return props;
    }

    private ConfigurableApplicationContext startApp(String appName,
                                                   Class<?> mainClass,
                                                   Map<String, Object> additionalProperties) {
        return startApp(appName, mainClass, additionalProperties, null);
    }

    private ConfigurableApplicationContext startApp(String appName,
                                                   Class<?> mainClass,
                                                   Map<String, Object> additionalProperties,
                                                   ApplicationContextInitializer<?> initializer,
                                                   Class<?>... sources) {
        Map<String, Object> props = new HashMap<>(additionalProperties);
        props.put("spring.application.name", appName);

        SpringApplicationBuilder builder = new SpringApplicationBuilder(mainClass).properties(props);
        if (initializer != null) {
            builder.initializers(initializer);
        }
        if (sources != null && sources.length > 0) {
            builder.sources(sources);
        }
        ConfigurableApplicationContext ctx = builder.run();
        contexts.add(ctx);
        return ctx;
    }

    private void registerClient(String serviceName,
                                ConfigurableApplicationContext ctx,
                                SSLContext sslContext,
                                boolean https) {
        String port = ctx.getEnvironment().getProperty("local.server.port");
        String scheme = https ? "https" : "http";
        HttpClient httpClient = HttpClient.newBuilder()
                .sslContext(sslContext)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        RestTestClient client = RestTestClient
                .bindToServer(new JdkClientHttpRequestFactory(httpClient))
                .baseUrl(scheme + "://localhost:" + port)
                .build();
        clients.put(serviceName, client);
    }

    private int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("无法获取空闲端口", e);
        }
    }

    // ==================== PKI / TLS 工具 ====================

    private static Path createCaP12(Path dir) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        X500Name dn = new X500Name("C=CN, O=Byteforce, CN=SeedJ Test Root CA");
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                dn, BigInteger.valueOf(System.nanoTime()),
                Date.from(now), Date.from(now.plus(Duration.ofDays(3650))),
                dn, kp.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(0));
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));

        var signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        X509Certificate ca = new JcaX509CertificateConverter()
                .getCertificate(builder.build(signer));

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry(CA_ALIAS, kp.getPrivate(), PASSWORD, new Certificate[]{ca});

        Path p12 = dir.resolve("pki-test-ca.p12");
        try (OutputStream out = Files.newOutputStream(p12)) {
            ks.store(out, PASSWORD);
        }
        return p12;
    }

    private static Path writeKeyStore(Path path, IssuedCertificate cert) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        Certificate[] chain = cert.chain().toArray(new Certificate[0]);
        ks.setKeyEntry(TLS_ALIAS, cert.privateKey(), PASSWORD, chain);
        try (OutputStream out = Files.newOutputStream(path)) {
            ks.store(out, PASSWORD);
        }
        return path;
    }

    private static Path writeTrustStore(Path path, X509Certificate ca) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setCertificateEntry(CA_ALIAS, ca);
        try (OutputStream out = Files.newOutputStream(path)) {
            ks.store(out, PASSWORD);
        }
        return path;
    }

    // ==================== TestJwtDecoderConfiguration ====================

    @Configuration
    static class TestJwtDecoderConfiguration {

        @Bean
        @Primary
        JwtDecoder jwtDecoder(
                @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
                @Qualifier(CLIENT_SSL_BEAN_NAME) SSLContext clientSslContext) {

            HttpClient httpClient = HttpClient.newBuilder()
                    .sslContext(clientSslContext)
                    .build();
            RestTemplate restTemplate = new RestTemplate(
                    new JdkClientHttpRequestFactory(httpClient));

            NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuer)
                    .restOperations(restTemplate)
                    .build();

            OAuth2TokenValidator<Jwt> atJwtValidator =
                    cn.byteforce.seedj.iam.security.IamJwtValidators.accessToken(issuer);
            OAuth2TokenValidator<Jwt> scopeValidator =
                    new JwtClaimValidator<>("scope", Objects::nonNull);
            decoder.setJwtValidator(
                    new DelegatingOAuth2TokenValidator<>(atJwtValidator, scopeValidator));
            return decoder;
        }
    }

    // ==================== ParameterResolver ====================

    @Override
    public boolean supportsParameter(ParameterContext parameterContext,
                                     ExtensionContext extensionContext) {
        Class<?> type = parameterContext.getParameter().getType();
        if (parameterContext.isAnnotated(ClusterService.class)) {
            return RestTestClient.class.isAssignableFrom(type);
        }
        if (parameterContext.isAnnotated(ClusterPki.class)) {
            return PkiProvider.class.isAssignableFrom(type);
        }
        if (parameterContext.isAnnotated(ClusterClientSslContext.class)
                || parameterContext.isAnnotated(ClusterUntrustedClient.class)) {
            return SSLContext.class.isAssignableFrom(type);
        }
        return false;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext,
                                   ExtensionContext extensionContext)
            throws ParameterResolutionException {
        if (parameterContext.isAnnotated(ClusterPki.class)) {
            if (pkiProvider == null) {
                throw new ParameterResolutionException("PKI Provider 尚未初始化");
            }
            return pkiProvider;
        }
        if (parameterContext.isAnnotated(ClusterClientSslContext.class)) {
            if (testClientSslContext == null) {
                throw new ParameterResolutionException("测试客户端 SSLContext 尚未初始化");
            }
            return testClientSslContext;
        }
        if (parameterContext.isAnnotated(ClusterUntrustedClient.class)) {
            if (untrustedClientSslContext == null) {
                throw new ParameterResolutionException("非受信客户端 SSLContext 尚未初始化");
            }
            return untrustedClientSslContext;
        }

        String serviceName = parameterContext.findAnnotation(ClusterService.class)
                .orElseThrow(() -> new ParameterResolutionException("缺少 @ClusterService 注解"))
                .value();
        RestTestClient client = clients.get(serviceName);
        if (client == null) {
            Set<String> known = knownServiceNames();
            if (known.contains(serviceName)) {
                throw new ParameterResolutionException(
                        "服务 [" + serviceName + "] 未启动。请在该测试类上通过 @EnableClusterServices 显式启用它。");
            }
            throw new ParameterResolutionException(
                    "未知服务 [" + serviceName + "]，可用服务: " + known);
        }
        return client;
    }
}