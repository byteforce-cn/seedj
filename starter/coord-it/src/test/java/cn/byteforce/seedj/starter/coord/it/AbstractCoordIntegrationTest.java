package cn.byteforce.seedj.starter.coord.it;

import org.junit.jupiter.api.Assertions;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * 集成测试基类：把 starter 的自动配置指到 {@link CoordItContainer} 中的真实
 * coord Agent。
 *
 * <p>上下文属性：</p>
 * <ul>
 *   <li>{@code byteforce.coord.agent-host/agent-port} —— 容器映射端口（随机）；</li>
 *   <li>{@code byteforce.coord.await-connected-timeout=30s} —— 启动阶段完成协议协商
 *       （fail-fast）：容器异常时上下文直接启动失败，避免「测试跑了但什么都没连通」
 *       的静默假绿。</li>
 * </ul>
 *
 * <p>所有子类共享同一个 ApplicationContext（上下文缓存键一致）与同一个容器，
 * 跨类复用、按域名隔离测试数据（唯一命名）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@SpringBootTest(classes = CoordItApplication.class)
abstract class AbstractCoordIntegrationTest {

    @DynamicPropertySource
    static void coordProperties(DynamicPropertyRegistry registry) {
        CoordItContainer container = CoordItContainer.getInstance();
        registry.add("byteforce.coord.agent-host", container::agentHost);
        registry.add("byteforce.coord.agent-port", container::agentPort);
        registry.add("byteforce.coord.await-connected-timeout", () -> "30s");
    }

    /**
     * 生成带时间戳的唯一名称（同一容器内跨测试运行避免键冲突）。
     */
    static String uniqueName(String prefix) {
        return prefix + "-" + Long.toHexString(System.nanoTime());
    }

    /**
     * 轮询等待异步语义成立（如 Workflow 状态推进）。
     *
     * @param description 等待目标描述（超时失败信息）
     * @param timeout     最长等待时间
     * @param condition   条件；为 true 即返回
     */
    static void awaitUntil(String description, Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待被中断: " + description, e);
            }
        }
        Assertions.fail("等待超时（" + timeout + "）: " + description);
    }
}
