package cn.byteforce.seedj.starter.ai.effect;

import cn.byteforce.seedj.ai.ExternalEffect;
import cn.byteforce.seedj.ai.ExternalEffectState;
import cn.byteforce.seedj.ai.spi.ExternalEffectStore;
import cn.byteforce.seedj.audit.AuditAppendRequest;
import cn.byteforce.seedj.audit.AuditAppender;
import cn.byteforce.seedj.core.clock.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 外部副作用两段协议：prepare → invoke(decision_id) → confirm/fail。
 *
 * <p>已确认的决策重试直接返回既有回执，不再调用外部系统；PREPARED/FAILED 允许用同一 {@code decision_id} 重试。 外部系统必须自己按 {@code
 * decision_id} 幂等——starter 不能替一个不支持幂等的外部系统去重。
 *
 * <p>U7 审计是尽力而为：效果已经发生，审计失败不能回滚效果，只 WARN。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class ExternalEffectService {

    private static final Logger LOG = LoggerFactory.getLogger(ExternalEffectService.class);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ExternalEffectStore store;

    private final AuditAppender<String> audit;

    private final Clock clock;

    /**
     * 绑定存储、可选审计与时钟。
     *
     * @param store 效果存储
     * @param audit 审计追加端口；{@code null} 表示不写 U7 条目
     * @param clock 取时入口
     */
    public ExternalEffectService(
            ExternalEffectStore store, AuditAppender<String> audit, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.audit = audit;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 执行一次外部副作用。
     *
     * @param call 调用输入
     * @param invoker 外部调用端口
     * @return 外部回执
     * @throws NullPointerException 任一参数为 {@code null}
     * @throws RuntimeException 外部调用失败；失败状态已落库（尽力）
     */
    public ExternalEffectResult execute(ExternalEffectCall call, ExternalEffectInvoker invoker) {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(invoker, "invoker");
        ExternalEffect prepared =
                store.prepare(
                        ExternalEffect.prepared(
                                call.decisionId(),
                                call.tenantId(),
                                call.commandType(),
                                call.argsHash(),
                                clock.now()));
        if (prepared.state() == ExternalEffectState.CONFIRMED) {
            return new ExternalEffectResult(prepared.externalRef());
        }
        try {
            ExternalEffectResult result = invoker.invoke(call);
            ExternalEffect confirmed =
                    store.complete(
                            call.decisionId(),
                            ExternalEffectState.CONFIRMED,
                            result.externalRef(),
                            null,
                            clock.now());
            audit(confirmed, call.actor());
            return result;
        } catch (RuntimeException error) {
            try {
                ExternalEffect failed =
                        store.complete(
                                call.decisionId(),
                                ExternalEffectState.FAILED,
                                null,
                                error.getClass().getSimpleName(),
                                clock.now());
                audit(failed, call.actor());
            } catch (RuntimeException recordFailure) {
                LOG.warn("外部效果失败状态落库失败（原始错误优先）: {}", call.decisionId(), recordFailure);
            }
            throw error;
        }
    }

    /**
     * 查询一次外部效果。
     *
     * @param decisionId 决策标识
     * @return 效果；不存在为空
     */
    public Optional<ExternalEffect> find(UUID decisionId) {
        return store.find(decisionId);
    }

    private void audit(ExternalEffect effect, UUID actor) {
        if (audit == null) {
            return;
        }
        ObjectNode payload = JSON.createObjectNode();
        payload.put("schema", "agent.effect.v1");
        payload.put("decision_id", effect.decisionId().toString());
        payload.put("command_type", effect.commandType());
        payload.put("args_hash", effect.argsHash());
        payload.put("state", effect.state().wire());
        if (effect.externalRef() != null) {
            payload.put("external_ref", effect.externalRef());
        }
        if (effect.errorCode() != null) {
            payload.put("error_code", effect.errorCode());
        }
        try {
            audit.append(
                    new AuditAppendRequest<>(
                            "agent.external_effect",
                            actor.toString(),
                            "external_effect",
                            effect.decisionId().toString(),
                            payload));
        } catch (RuntimeException error) {
            LOG.warn("外部效果 U7 审计失败（效果已发生）: {}", effect.decisionId(), error);
        }
    }
}
