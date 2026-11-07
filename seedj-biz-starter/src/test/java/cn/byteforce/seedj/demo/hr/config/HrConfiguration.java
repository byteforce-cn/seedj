package cn.byteforce.seedj.demo.hr.config;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.clock.Clocks;
import cn.byteforce.seedj.cqrs.CommandCatalog;
import cn.byteforce.seedj.cqrs.CommandPolicy;
import cn.byteforce.seedj.cqrs.CommandRuntime;
import cn.byteforce.seedj.cqrs.IdCodec;
import cn.byteforce.seedj.cqrs.TemporalCommandAudit;
import cn.byteforce.seedj.cqrs.spi.CommandStore;
import cn.byteforce.seedj.starter.cqrs.wiring.CommandAgents;
import cn.byteforce.seedj.cqrs.QueryAgent;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.domain.TemporalStore;
import cn.byteforce.seedj.infra.local.cqrs.InMemoryCommandStore;
import cn.byteforce.seedj.infra.local.cqrs.InMemoryEventStore;
import cn.byteforce.seedj.infra.local.domain.InMemoryTemporalStore;
import cn.byteforce.seedj.infra.local.id.Uuid7IdGenerator;
import cn.byteforce.seedj.module.ObjectModuleRegistry;
import cn.byteforce.seedj.module.ObjectModules;
import cn.byteforce.seedj.outbox.OutboxWriter;
import cn.byteforce.seedj.cqrs.spi.EventStore;
import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
import cn.byteforce.seedj.lock.LockProvider;
import cn.byteforce.seedj.infra.local.lock.InMemoryLockProvider;
import cn.byteforce.seedj.demo.hr.app.HrCommandService;
import cn.byteforce.seedj.demo.hr.app.OnboardingAgent;
import cn.byteforce.seedj.demo.hr.domain.EmployeeId;
import cn.byteforce.seedj.demo.hr.domain.EmployeeIdCodec;
import cn.byteforce.seedj.demo.hr.domain.EmployeeRepository;
import cn.byteforce.seedj.demo.hr.domain.EventSourcedEmployeeRepository;
import cn.byteforce.seedj.demo.hr.query.EmployeeDirectory;
import cn.byteforce.seedj.demo.hr.query.EmployeeDirectoryProjection;
import cn.byteforce.seedj.demo.hr.query.EmployeeView;
import cn.byteforce.seedj.demo.hr.query.HrQueryService;
import cn.byteforce.seedj.demo.hr.web.HrEmployeeController;
import cn.byteforce.seedj.eventbus.EventPublisher;
import cn.byteforce.seedj.id.IdGenerator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * HR 模块装配。
 *
 * <p>刻意不用组件扫描：业务模块通过 {@link EnableHr} 显式开启，避免在宿主应用的
 * 扫描路径里"自动长出"一个需要 {@code EventPublisher} 的 bean。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
public class HrConfiguration {

    /**
     * 员工标识的规范编解码（全系统唯一转换点）。
     */
    @Bean
    public IdCodec<EmployeeId> employeeIdCodec() {
        return new EmployeeIdCodec();
    }

    /**
     * 事件存储：演示用内存实现，生产环境换成 JDBC 实现即可，契约不变。
     */
    @Bean
    public EventStore<EmployeeId> employeeEventStore(IdCodec<EmployeeId> employeeIdCodec) {
        return new InMemoryEventStore<>(employeeIdCodec);
    }

    /**
     * 员工标识生成器：把框架的字符串生成器适配成业务值对象。
     *
     * <p>业务侧只看到 {@code EmployeeId}，看不到 {@code String}。
     */
    @Bean
    public IdGenerator<EmployeeId> employeeIdGenerator(IdGenerator<String> stringIds) {
        return () -> EmployeeId.of(stringIds.id());
    }

    @Bean
    public EmployeeRepository employeeRepository(EventStore<EmployeeId> employeeEventStore) {
        return new EventSourcedEmployeeRepository(employeeEventStore);
    }

    /**
     * 分布式锁：单实例演示用内存实现，生产环境换成分布式 provider。
     *
     * <p>锁是独立能力（{@code cn.byteforce.seedj.lock}），CQRS、outbox relay、workflow
     * 调度共用同一个入口，谁也不拥有它。内存实现只能单实例用：多实例下它会让互斥
     * 在进程边界失效且没有任何报错。
     */
    @Bean
    public LockProvider lockProvider() {
        return new InMemoryLockProvider();
    }

    /**
     * 事务边界：内存事件存储没有事务，用直通实现。
     *
     * <p>换成 JDBC 事件存储时，这里必须换成基于 {@code TransactionTemplate} 的实现，
     * 否则"事件流与 outbox 同事务"失效——但命令模板的锁顺序不需要改。
     */
    @Bean
    public TransactionRunner transactionRunner() {
        return TransactionRunner.direct();
    }

    @Bean
    public Clock hrClock() {
        return Clocks.fixed(Instant.parse("2026-11-07T09:00:00Z"));
    }

    @Bean
    public ObjectModuleRegistry hrObjectModuleRegistry(OnboardingAgent onboardingAgent) {
        ObjectModules modules = ObjectModules.create();
        new HrEmployeeModule(onboardingAgent).configure(modules);
        return modules.freeze();
    }

    @Bean
    public CommandStore hrCommandStore() {
        return new InMemoryCommandStore();
    }

    @Bean
    public TemporalStore hrTemporalStore(Clock hrClock) {
        return new InMemoryTemporalStore(hrClock);
    }

    @Bean
    public IdGenerator<UUID> hrCommandIds(Clock hrClock) {
        return new Uuid7IdGenerator(hrClock);
    }

    @Bean
    public CommandRuntime hrCommandRuntime(CommandStore hrCommandStore,
                                           TemporalStore hrTemporalStore,
                                           TransactionRunner transactionRunner,
                                           LockProvider lockProvider, Clock hrClock,
                                           IdGenerator<UUID> hrCommandIds) {
        return new CommandRuntime(new CommandRuntime.Options(transactionRunner, lockProvider,
                Duration.ofSeconds(30), hrClock, hrCommandIds, hrCommandStore,
                hrTemporalStore, OutboxWriter.discard(), TemporalCommandAudit.noop(),
                JsonMapper.builder().build()));
    }

    @Bean
    public CommandCatalog hrCommandCatalog(ObjectModuleRegistry hrObjectModuleRegistry,
                                           CommandRuntime hrCommandRuntime) {
        return new CommandCatalog(hrObjectModuleRegistry.commands(),
                CommandPolicy.permissions(), hrCommandRuntime,
                (objectType, caller) -> ScopePredicate.all());
    }

    @Bean
    public OnboardingAgent onboardingAgent(EmployeeRepository employees,
                                           EventPublisher publisher,
                                           IdCodec<EmployeeId> employeeIdCodec,
                                           CommandAgents agents) {
        return new OnboardingAgent(employees, publisher, employeeIdCodec, agents.options());
    }

    @Bean
    public HrCommandService hrCommandService(CommandCatalog hrCommandCatalog,
                                             IdGenerator<EmployeeId> employeeIdGenerator) {
        return new HrCommandService(hrCommandCatalog, employeeIdGenerator);
    }

    @Bean
    public EmployeeDirectory employeeDirectory() {
        return new EmployeeDirectory();
    }

    @Bean
    public EmployeeDirectoryProjection employeeDirectoryProjection(EmployeeDirectory directory) {
        return new EmployeeDirectoryProjection(directory);
    }

    /**
     * 按标识查员工的查询代理。缓存/鉴权按读模型配置，所以一个读模型一个 agent。
     */
    @Bean
    public QueryAgent<Optional<EmployeeView>> employeeByIdQueryAgent() {
        return new QueryAgent<>();
    }

    /**
     * 员工目录列表的查询代理。
     */
    @Bean
    public QueryAgent<List<EmployeeView>> employeeListQueryAgent() {
        return new QueryAgent<>();
    }

    @Bean
    public HrQueryService hrQueryService(
            EmployeeDirectory employeeDirectory,
            @Qualifier("employeeByIdQueryAgent") QueryAgent<Optional<EmployeeView>> byIdAgent,
            @Qualifier("employeeListQueryAgent") QueryAgent<List<EmployeeView>> listAgent) {
        return new HrQueryService(employeeDirectory, byIdAgent, listAgent);
    }

    @Bean
    public HrEmployeeController hrEmployeeController(HrQueryService queries) {
        return new HrEmployeeController(queries);
    }
}
