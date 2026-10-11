package cn.byteforce.seedj.starter.eis.it;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * PG16 集成测试容器（Testcontainers）。
 *
 * <p>EIS 契约要求 PostgreSQL 16+（域类型、TIMESTAMPTZ(6)、append-only 规则、
 * 部分唯一索引）——硬性工程决策：<b>不引入 H2</b>（不可模拟上述特性）。</p>
 *
 * <p>生命周期：JVM 级单例（跨测试类复用）；镜像默认 {@code postgres:16-alpine}，
 * 可用系统属性 {@code eis.it.postgres.image} 或环境变量 {@code EIS_IT_POSTGRES_IMAGE}
 * 覆盖。模块内所有集成测试共享一个容器与库，测试间以 TRUNCATE 隔离。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class EisPostgresContainer extends PostgreSQLContainer {

    private static final Logger log = LoggerFactory.getLogger(EisPostgresContainer.class);

    /** 默认镜像。 */
    private static final String DEFAULT_IMAGE = "postgres:16-alpine";

    /** 镜像覆盖系统属性。 */
    public static final String IMAGE_PROPERTY = "eis.it.postgres.image";

    /** 镜像覆盖环境变量。 */
    public static final String IMAGE_ENV = "EIS_IT_POSTGRES_IMAGE";

    private EisPostgresContainer(DockerImageName image) {
        super(image);
        withDatabaseName("eis");
        withUsername("eis");
        withPassword("eis");
        withStartupTimeout(Duration.ofMinutes(2));
    }

    /**
     * 获取 JVM 级单例（首次访问时启动容器）。
     *
     * @return 已启动的容器
     */
    public static EisPostgresContainer getInstance() {
        return Holder.INSTANCE;
    }

    private static EisPostgresContainer startInstance() {
        EisPostgresContainer container = new EisPostgresContainer(resolveImage());
        container.start();
        log.info("eis-it PostgreSQL 容器已启动: {}", container.getJdbcUrl());
        return container;
    }

    private static DockerImageName resolveImage() {
        String image = System.getProperty(IMAGE_PROPERTY,
                System.getenv().getOrDefault(IMAGE_ENV, DEFAULT_IMAGE));
        return DockerImageName.parse(image).asCompatibleSubstituteFor("postgres");
    }

    /**
     * 惰性单例持有。
     */
    private static final class Holder {

        private static final EisPostgresContainer INSTANCE = startInstance();

        private Holder() {
        }
    }
}
