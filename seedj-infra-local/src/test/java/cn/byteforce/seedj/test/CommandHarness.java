package cn.byteforce.seedj.test;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.cqrs.CommandReceipt;
import cn.byteforce.seedj.cqrs.CommandRegistration;
import cn.byteforce.seedj.cqrs.CommandRuntime;
import cn.byteforce.seedj.cqrs.CommandSubmission;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.ObjectSchemaRegistry;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.infra.local.cqrs.InMemoryCommandStore;
import cn.byteforce.seedj.infra.local.domain.InMemoryTemporalStore;
import cn.byteforce.seedj.module.ObjectModuleRegistry;
import cn.byteforce.seedj.module.ObjectModules;
import cn.byteforce.seedj.read.ReadModel;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 业务规则测试工具：内存存储 + 固定时钟 + 调用方 + 写范围，10 行内测一条规则。
 *
 * <p>这是测试侧工件（{@code seedj-infra-local} 的 test-jar），不进入运行时 classpath。 业务规则测试不需要
 * {@code @SpringBootTest}：装配只保留业务依赖（工号、派生字段、 组织目录等），运行时的幂等、锁、事务、schema 校验与写范围由本类提供。
 *
 * <pre>{@code
 * CommandHarness harness = CommandHarness.of(moduleRegistry(), caller)
 *         .clockAt("2026-10-08T09:00:00Z");
 * CommandReceipt created = harness.execute("hr.employee.create",
 *         Map.of("name", "张三", "org_unit_id", "D01"), "k-1");
 * assertThat(harness.object("employee", created.locator()).fields())
 *         .containsEntry("status", "pending_onboarding");
 * }</pre>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class CommandHarness {

    private static final Instant DEFAULT_NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final Map<String, CommandRegistration> registrations = new LinkedHashMap<>();

    private final InMemoryCommandStore commandStore = new InMemoryCommandStore();

    private final InMemoryTemporalStore objects;

    private final AtomicReference<Instant> now = new AtomicReference<>(DEFAULT_NOW);

    private final AtomicLong idSequence = new AtomicLong();

    private final CommandRuntime runtime;

    private final Caller caller;

    private final UUID actor;

    private ScopePredicate writeScope = ScopePredicate.all();

    private CommandHarness(ObjectModuleRegistry registry, Caller caller) {
        Objects.requireNonNull(registry, "registry");
        this.caller = Objects.requireNonNull(caller, "caller");
        this.actor = UUID.fromString(caller.id());
        registry.commands()
                .forEach(
                        registration ->
                                registrations.put(registration.descriptor().type(), registration));
        Clock clock = now::get;
        ObjectSchemaRegistry schemas =
                new ObjectSchemaRegistry(registry.all().stream().map(ReadModel::schema).toList());
        this.objects = new InMemoryTemporalStore(clock, schemas);
        this.runtime =
                new CommandRuntime(
                        CommandRuntime.Options.forTests(
                                clock,
                                () -> new UUID(0, idSequence.incrementAndGet()),
                                commandStore,
                                objects));
    }

    /**
     * 用冻结的模块注册表创建 harness。
     *
     * @param registry 模块注册表
     * @param caller 调用方
     * @return harness
     */
    public static CommandHarness of(ObjectModuleRegistry registry, Caller caller) {
        return new CommandHarness(registry, caller);
    }

    /**
     * 用未冻结的模块声明创建 harness。
     *
     * @param modules 模块声明
     * @param caller 调用方
     * @return harness
     */
    public static CommandHarness of(ObjectModules modules, Caller caller) {
        return new CommandHarness(Objects.requireNonNull(modules, "modules").freeze(), caller);
    }

    /**
     * 固定时钟。
     *
     * @param instant 当前时间
     * @return 本 harness
     */
    public CommandHarness clockAt(Instant instant) {
        now.set(Objects.requireNonNull(instant, "instant"));
        return this;
    }

    /**
     * 固定时钟（ISO-8601）。
     *
     * @param instant 当前时间
     * @return 本 harness
     */
    public CommandHarness clockAt(String instant) {
        return clockAt(Instant.parse(instant));
    }

    /**
     * 推进时钟。
     *
     * @param duration 推进量
     * @return 本 harness
     */
    public CommandHarness advance(Duration duration) {
        now.updateAndGet(current -> current.plus(Objects.requireNonNull(duration, "duration")));
        return this;
    }

    /**
     * 写范围；默认 {@link ScopePredicate#all()}。
     *
     * @param scope 写范围
     * @return 本 harness
     */
    public CommandHarness writeScope(ScopePredicate scope) {
        this.writeScope = scope == null ? ScopePredicate.all() : scope;
        return this;
    }

    /**
     * 执行一条命令。
     *
     * @param type 命令类型
     * @param payload 载荷（snake_case 字段名）
     * @param idempotencyKey 幂等键
     * @return 受理结果
     */
    public CommandReceipt execute(String type, Map<String, Object> payload, String idempotencyKey) {
        CommandRegistration registration = registrations.get(type);
        if (registration == null) {
            throw new IllegalArgumentException(
                    "command type is not declared: "
                            + type
                            + " (known: "
                            + registrations.keySet()
                            + ")");
        }
        return runtime.execute(
                new CommandSubmission(type, payload, idempotencyKey, List.of(), actor),
                registration,
                caller,
                writeScope);
    }

    /**
     * 读取当前行；不存在即失败。
     *
     * @param objectType 对象类型
     * @param id 对象 ID
     * @return 当前行
     */
    public TemporalRow object(String objectType, UUID id) {
        return objects.current(objectType, id)
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "object not found: " + objectType + ":" + id));
    }

    /**
     * 读取当前行。
     *
     * @param objectType 对象类型
     * @param id 对象 ID
     * @return 当前行
     */
    public Optional<TemporalRow> findObject(String objectType, UUID id) {
        return objects.current(objectType, id);
    }

    /**
     * 对象 schema。
     *
     * @param objectType 对象类型
     * @return schema
     */
    public ObjectSchema schema(String objectType) {
        return registrations.values().stream()
                .filter(registration -> registration.descriptor().objectType().equals(objectType))
                .map(CommandRegistration::schema)
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "no command declared for object type: " + objectType));
    }

    /**
     * 内存命令存储（用于断言幂等、拒绝终态）。
     *
     * @return 命令存储
     */
    public InMemoryCommandStore commands() {
        return commandStore;
    }

    /**
     * 内存对象存储。
     *
     * @return 对象存储
     */
    public InMemoryTemporalStore objects() {
        return objects;
    }

    /**
     * 命令运行时。
     *
     * @return 运行时
     */
    public CommandRuntime runtime() {
        return runtime;
    }
}
