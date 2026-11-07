package cn.byteforce.seedj.starter.read;

import cn.byteforce.seedj.starter.security.CallerPropagation;
import cn.byteforce.seedj.starter.security.RequestContextDecorator;
import cn.byteforce.seedj.starter.security.ThreadLocalCallerPropagation;
import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.CallerProvider;
import cn.byteforce.seedj.core.security.ContextResolver;
import cn.byteforce.seedj.core.security.ScopeContext;
import cn.byteforce.seedj.core.security.ScopeContextHolder;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ObjectVersion;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.domain.TemporalStore;
import cn.byteforce.seedj.infra.local.domain.InMemoryTemporalStore;
import cn.byteforce.seedj.module.ObjectModuleConfigurer;
import cn.byteforce.seedj.module.ObjectModuleRegistry;
import cn.byteforce.seedj.read.ReadAccessEvent;
import cn.byteforce.seedj.read.ReadAuditSink;
import cn.byteforce.seedj.read.ReadGateway;
import cn.byteforce.seedj.starter.domain.web.ObjectQueryController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.core.task.TaskDecorator;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 读路径装配：注册表、网关、通用面开关与异步传播。
 */
class ReadAutoConfigurationTest {

    private static final Instant NOW = Instant.parse("2026-11-06T09:00:00Z");

    private static final Caller CALLER = new Caller("u-1", "alice", "default", null,
            null, null, null, null, null);

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObjectModuleAutoConfiguration.class,
                    ReadAutoConfiguration.class))
            .withBean(Clock.class, () -> (Clock) () -> NOW)
            .withBean(TemporalStore.class,
                    () -> new InMemoryTemporalStore((Clock) () -> NOW))
            .withBean(CallerProvider.class, () -> (CallerProvider) () -> Optional.of(CALLER))
            .withBean(CallerPropagation.class, ThreadLocalCallerPropagation::new)
            .withBean(ObjectModuleConfigurer.class, () -> modules ->
                    modules.object("employee", "hr_employee")
                            .readPermission("hr.employee.read")
                            .field("employee_no", FieldType.TEXT).required().filterable()
                            .sortable());

    @Test
    @DisplayName("注册表由 configurer 构建，网关与过滤器就绪")
    void registryAndGatewayArePresent() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ObjectModuleRegistry.class);
            assertThat(context.getBean(ObjectModuleRegistry.class).find("employee"))
                    .isPresent();
            assertThat(context).hasSingleBean(ReadGateway.class);
            assertThat(context).hasSingleBean(ContextResolver.class);
            assertThat(context).hasSingleBean(ReadAuditSink.class);
            assertThat(context).hasSingleBean(ScopeContextFilter.class);
        });
    }

    @Test
    @DisplayName("通用 /objects 面默认不注册")
    void genericSurfaceIsAbsentByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ObjectQueryController.class);
        });
    }

    @Test
    @DisplayName("通用 /objects 面需要显式属性")
    void genericSurfaceRequiresExplicitProperty() {
        runner.withPropertyValues("seedj.read.generic-surface=enabled").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ObjectQueryController.class);
        });
    }

    @Test
    @DisplayName("视图映射 snake_case → camelCase，元结构来自版本")
    void viewMapperMapsSnakeCaseToCamelCase() {
        UUID id = UUID.randomUUID();
        TemporalRow row = new TemporalRow("employee",
                new ObjectVersion(id, 3, NOW, null, NOW, null, id, NOW, null, null),
                Map.of("employee_no", "E-1"));

        EmployeeView view = (EmployeeView) new ViewMapper().map(row, EmployeeView.class);

        assertThat(view.employeeNo()).isEqualTo("E-1");
        assertThat(view.id()).isEqualTo(id);
        assertThat(view.version()).isEqualTo(3);
    }

    @Test
    @DisplayName("默认读审计记录 Micrometer 计数")
    void defaultReadAuditRecordsMetrics() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        runner.withBean(SimpleMeterRegistry.class, () -> meters).run(context -> {
            ReadAuditSink sink = context.getBean(ReadAuditSink.class);
            sink.record(new ReadAccessEvent(UUID.randomUUID(), "u-1", "employee",
                    ReadAccessEvent.Operation.FIND, Map.of(), List.of(), "digest",
                    Set.of("p-1"), NOW));

            assertThat(meters.get("seedj.read.access")
                    .tag("object_type", "employee")
                    .tag("operation", "FIND")
                    .counter().count()).isEqualTo(1.0);
        });
    }

    @Test
    @DisplayName("异步装饰器同时携带 Caller 与 ScopeContext")
    void asyncDecorationCarriesCallerAndScopeContext() throws Exception {
        CallerPropagation propagation = new ThreadLocalCallerPropagation();
        ScopeContext context = ScopeContext.of(Map.of("org.units", List.of("D01")));
        AtomicReference<Optional<Caller>> seenCaller = new AtomicReference<>();
        AtomicReference<Optional<ScopeContext>> seenContext = new AtomicReference<>();

        TaskDecorator decorator = new RequestContextDecorator(propagation);
        Runnable decorated;
        try (CallerPropagation.Scope ignoredCaller = propagation.open(CALLER);
             ScopeContextHolder.Scope ignoredContext = ScopeContextHolder.open(context)) {
            decorated = decorator.decorate(() -> {
                seenCaller.set(propagation.current());
                seenContext.set(ScopeContextHolder.current());
            });
        }

        var executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(decorated).get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(seenCaller.get()).contains(CALLER);
        assertThat(seenContext.get()).contains(context);
    }

    /** 测试视图。 */
    record EmployeeView(String employeeNo, UUID id, long version) {
    }
}
