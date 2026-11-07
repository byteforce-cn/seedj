package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.cqrs.spi.CommandStore;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.id.IdGenerator;
import tools.jackson.databind.json.JsonMapper;

import java.util.Objects;
import java.util.UUID;

/**
 * 统一信封交给事件溯源执行模型的上下文：一条已解码命令在 agent 内部执行时
 * 仍然需要的信封字段。
 *
 * <p>包内类型：命令只能从 {@link CommandRuntime} 进入 agent，业务代码不构造它。</p>
 *
 * @param targetRef    聚合标识的规范文本（{@code IdCodec.encode}）
 * @param pending      已写入的命令记录；成功时在同一事务内转为接受态
 * @param schema       对象 schema（写范围与事件可见性）
 * @param registration 注册项（事件白名单）
 * @param writeScope   调用方写范围
 * @param jsonMapper   事件属性展开
 * @param commandStore 命令记录存储（终态与事件流同事务）
 * @param idGenerator  事件 ID 分配（与命令记录同一 UUIDv7 生成器）
 * @author ByteForce
 * @since 1.0.0
 */
record AgentInvocation(String targetRef, CommandRecord pending, ObjectSchema schema,
                       CommandRegistration registration, ScopePredicate writeScope,
                       JsonMapper jsonMapper, CommandStore commandStore,
                       IdGenerator<UUID> idGenerator) {

    /**
     * 测试/兼容构造：事件 ID 使用随机 UUID。
     */
    AgentInvocation(String targetRef, CommandRecord pending, ObjectSchema schema,
                    CommandRegistration registration, ScopePredicate writeScope,
                    JsonMapper jsonMapper, CommandStore commandStore) {
        this(targetRef, pending, schema, registration, writeScope, jsonMapper, commandStore,
                UUID::randomUUID);
    }

    AgentInvocation {
        Objects.requireNonNull(targetRef, "targetRef");
        Objects.requireNonNull(pending, "pending");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(registration, "registration");
        Objects.requireNonNull(writeScope, "writeScope");
        Objects.requireNonNull(jsonMapper, "jsonMapper");
        Objects.requireNonNull(commandStore, "commandStore");
        Objects.requireNonNull(idGenerator, "idGenerator");
    }
}
