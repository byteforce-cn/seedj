package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.AppendResult;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.domain.event.CommandEvent;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 双时间轴对象写入的命令代理。
 *
 * <p>与事件溯源路径的 {@link CommandAgent} 并列：本类不做聚合加载/保存，
 * 而是把类型化命令适配成 {@link CommandRuntime.Work}，由运行时保证幂等、
 * 锁、事务与终态。子类只声明命令类型、对象类型、目标 ID、追加请求与事件。</p>
 *
 * @param <C> 命令载荷类型
 * @author ByteForce
 * @since 1.0.0
 */
public abstract class TemporalCommandAgent<C> {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final CommandRuntime runtime;

    private final String commandType;

    private final String objectType;

    private final Class<C> payloadType;

    /**
     * 构造代理。
     *
     * @param runtime     命令运行时
     * @param commandType 命令类型（线格式）
     * @param objectType  对象类型（对象表注册名）
     * @param payloadType 载荷类型
     */
    protected TemporalCommandAgent(CommandRuntime runtime, String commandType, String objectType,
                                   Class<C> payloadType) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.commandType = requireText(commandType, "commandType");
        this.objectType = requireText(objectType, "objectType");
        this.payloadType = Objects.requireNonNull(payloadType, "payloadType");
    }

    /**
     * 执行命令。
     *
     * @param submission 命令提交
     * @return 受理结果
     */
    public final CommandReceipt execute(CommandSubmission submission) {
        Objects.requireNonNull(submission, "submission");
        if (!commandType.equals(submission.type())) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                    "command type mismatch: expected " + commandType
                            + ", got " + submission.type());
        }
        C command = decode(submission.payload());
        return runtime.execute(submission, new CommandRuntime.Work() {
            @Override
            public String objectType() {
                return objectType;
            }

            @Override
            public UUID targetId() {
                return TemporalCommandAgent.this.targetId(command);
            }

            @Override
            public void validate() {
                TemporalCommandAgent.this.validate(command);
            }

            @Override
            public AppendRequest append(Optional<TemporalRow> current, Instant now, UUID actor) {
                return TemporalCommandAgent.this.append(command, current, now, actor);
            }

            @Override
            public List<CommandEvent> events(AppendResult result, Instant now, UUID actor) {
                return TemporalCommandAgent.this.events(command, result, now, actor);
            }
        });
    }

    /**
     * 命令类型。
     *
     * @return 线格式命令类型
     */
    public final String commandType() {
        return commandType;
    }

    /**
     * 对象类型。
     *
     * @return 对象表注册名
     */
    public final String objectType() {
        return objectType;
    }

    /**
     * 业务校验；默认不校验。
     *
     * @param command 类型化命令
     */
    protected void validate(C command) {
    }

    /**
     * 目标对象 ID。
     *
     * @param command 类型化命令
     * @return 目标 ID
     */
    protected abstract UUID targetId(C command);

    /**
     * 生成追加请求。
     *
     * @param command 类型化命令
     * @param current 当前行；创建时为空
     * @param now     系统时间
     * @param actor   操作者
     * @return 追加请求
     */
    protected abstract AppendRequest append(C command, Optional<TemporalRow> current,
                                            Instant now, UUID actor);

    /**
     * 产生事件；默认无事件。
     *
     * @param command 类型化命令
     * @param result  追加结果
     * @param now     系统时间
     * @param actor   操作者
     * @return 事件列表
     */
    protected List<CommandEvent> events(C command, AppendResult result, Instant now, UUID actor) {
        return List.of();
    }

    /**
     * 分配一个新标识（事件等）。
     *
     * @return UUIDv7
     */
    protected final UUID newId() {
        return runtime.newId();
    }

    /**
     * 当前系统时间。
     *
     * @return UTC 微秒时间
     */
    protected final Instant now() {
        return runtime.now();
    }

    private C decode(Map<String, Object> payload) {
        try {
            return JSON.convertValue(payload, payloadType);
        } catch (RuntimeException error) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                    "invalid payload for " + commandType,
                    Map.of("command_type", commandType), null);
        }
    }

    private static String requireText(String value, String role) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(role + " must not be blank");
        }
        return value;
    }
}
