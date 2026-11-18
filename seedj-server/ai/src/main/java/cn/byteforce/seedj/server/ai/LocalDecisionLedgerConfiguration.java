package cn.byteforce.seedj.server.ai;

import cn.byteforce.seedj.ai.spi.DecisionLedger;
import cn.byteforce.seedj.infra.local.ai.InMemoryDecisionLedger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * {@code local} profile 的决策账本：进程内内存实现。
 *
 * <p><b>仅本地/演示</b>：无持久化，重启即丢，绝不用于生产——生产与 {@code postgres} profile 都用 {@code seedj-jdbc-postgresql}
 * 的数据库账本（{@code seedj.ai.provider=postgres}，缺省即如此）。
 *
 * <p>starter 故意不提供内存账本 bean：账本必须落库才谈得上「有意图无效果可以对账」，所以「退化成内存」这个决定只能由宿主 在本地 profile 里显式做出。没有它，{@code
 * --spring.profiles.active=local} 会因为缺 {@link DecisionLedger} 而启动失败。
 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
class LocalDecisionLedgerConfiguration {

    @Bean
    DecisionLedger decisionLedger() {
        return new InMemoryDecisionLedger();
    }
}
