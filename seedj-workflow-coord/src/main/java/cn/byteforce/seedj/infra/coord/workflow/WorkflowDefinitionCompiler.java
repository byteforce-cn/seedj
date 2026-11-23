package cn.byteforce.seedj.infra.coord.workflow;

import cn.byteforce.seedj.workflow.spi.AllApprove;
import cn.byteforce.seedj.workflow.spi.AnyApprove;
import cn.byteforce.seedj.workflow.spi.AnyRejectTerminates;
import cn.byteforce.seedj.workflow.spi.AtLeastCount;
import cn.byteforce.seedj.workflow.spi.AtLeastPercentage;
import cn.byteforce.seedj.workflow.spi.AtLeastRejects;
import cn.byteforce.seedj.workflow.spi.HumanTaskSpec;
import cn.byteforce.seedj.workflow.spi.ParticipantSpec;
import cn.byteforce.seedj.workflow.spi.RecordAndContinue;
import cn.byteforce.seedj.workflow.spi.RejectCompletion;
import cn.byteforce.seedj.workflow.spi.RetryPolicy;
import cn.byteforce.seedj.workflow.spi.SignCompletion;
import cn.byteforce.seedj.workflow.spi.StepDefinition;
import cn.byteforce.seedj.workflow.spi.StepKind;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinition;
import cn.byteforce.seedj.workflow.spi.WorkflowProviderException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.databind.json.JsonMapper;

/**
 * seedj 定义 → coord 原生方言（{@code document} + {@code do}）的编译器。
 *
 * <p>定义原样 JSON 写进 {@code document.tags["seedj.definition"]}（随 Deploy 一次原子写入，内容进 {@code
 * definition_hash}）；运行语义只映射 coord 能表达的部分，表达不了的在 register 期 fail-closed， 不静默丢弃。
 */
final class WorkflowDefinitionCompiler {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Set<String> RESERVED_STEP_NAMES = Set.of("exit", "end");
    private static final Set<String> TIMEOUT_POLICIES = Set.of("fail", "waiting", "none");

    private final CoordWorkflowIds ids;
    private final String signAdmin;
    private final int maxDefinitionBytes;

    WorkflowDefinitionCompiler(CoordWorkflowIds ids, String signAdmin, int maxDefinitionBytes) {
        this.ids = ids;
        this.signAdmin = signAdmin;
        this.maxDefinitionBytes = maxDefinitionBytes;
    }

    String compile(WorkflowDefinition definition) {
        validateStructure(definition);

        Map<String, Integer> indices = new HashMap<>();
        for (int i = 0; i < definition.steps().size(); i++) {
            indices.put(definition.steps().get(i).stepName(), i);
        }

        List<Object> doTasks = new ArrayList<>();
        for (int i = 0; i < definition.steps().size(); i++) {
            StepDefinition step = definition.steps().get(i);
            Map<String, Object> task = new LinkedHashMap<>();
            task.put(step.stepName(), taskBody(definition, step, i, indices));
            doTasks.add(task);
        }

        String embedded = WorkflowDefinitionCodec.toJson(definition);
        if (embedded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > maxDefinitionBytes) {
            throw new WorkflowProviderException(
                    "seedj workflow definition "
                            + definition.id().namespace()
                            + "/"
                            + definition.id().name()
                            + "@"
                            + definition.version().major()
                            + "."
                            + definition.version().minor()
                            + ": definition exceeds "
                            + maxDefinitionBytes
                            + " bytes");
        }

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("dsl", "1.0.3");
        document.put("namespace", ids.coordNamespace(definition.id().namespace()));
        document.put("name", ids.coordName(definition.id()));
        document.put("version", ids.coordVersion(definition.version()));
        document.put("title", definition.id().namespace() + "/" + definition.id().name());
        Map<String, Object> tags = new TreeMap<>();
        tags.put("seedj.definition", embedded);
        tags.put("seedj.namespace", definition.id().namespace());
        tags.put("seedj.version", ids.coordVersion(definition.version()));
        document.put("tags", tags);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("document", document);
        root.put("do", doTasks);
        if (definition.workflowTimeout() != null) {
            root.put("timeout", WorkflowDsl.timeout(definition.workflowTimeout()));
        }
        return MAPPER.writeValueAsString(root);
    }

    private Map<String, Object> taskBody(
            WorkflowDefinition definition,
            StepDefinition step,
            int index,
            Map<String, Integer> indices) {
        Map<String, Object> body = new LinkedHashMap<>();
        switch (step.kind()) {
            case HUMAN_TASK -> body.put("approve", approve(step, index, indices));
            case WAIT_SIGNAL -> body.put("listen", listen(step));
            case ACTIVITY, COMPENSATION -> body.put("set", set(step));
        }
        // approve 节点也写步骤 timeout：审批自身的 deadline 只管轮次截止，
        // 运行时的 ResumeWorkflow 时间预算取 task_meta.timeout（coord 0.4.6）。
        if (step.timeout() != null) {
            body.put("timeout", WorkflowDsl.timeout(step.timeout()));
        }
        if (step.kind() != StepKind.HUMAN_TASK && step.retryPolicy() != null) {
            body.put("retry", retry(step.retryPolicy()));
        }
        return body;
    }

    private Map<String, Object> set(StepDefinition step) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("_seedjStep", step.stepName());
        return node;
    }

    private Map<String, Object> listen(StepDefinition step) {
        Map<String, Object> with = new LinkedHashMap<>();
        with.put("type", step.waitSignalName());
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("with", with);
        Map<String, Object> to = new LinkedHashMap<>();
        to.put("one", one);
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("to", to);
        return node;
    }

    private Map<String, Object> retry(RetryPolicy policy) {
        String backoff = policy.multiplier() == 1.0 ? "constant" : "exponential";
        return WorkflowDsl.retry(
                policy.maxAttempts(),
                policy.initialBackoff(),
                backoff,
                policy.maxBackoff(),
                policy.multiplier());
    }

    private Map<String, Object> approve(
            StepDefinition step, int index, Map<String, Integer> indices) {
        HumanTaskSpec spec = step.humanTask();
        Map<String, Object> node = new LinkedHashMap<>();

        List<String> approvers = new ArrayList<>();
        List<String> roles = new ArrayList<>();
        List<String> pool = new ArrayList<>();
        for (ParticipantSpec participant : spec.assignees()) {
            switch (participant.type()) {
                case USER -> approvers.add(participant.value());
                case ROLE -> roles.add(participant.value());
                case GROUP, DEPARTMENT -> pool.add(participant.value());
                case EXPRESSION -> approvers.add(ParticipantExpressions.toJq(participant.value()));
            }
        }
        if (!approvers.isEmpty()) {
            node.put("approvers", approvers);
        }
        if (!roles.isEmpty()) {
            node.put("roles", roles);
        }
        if (!pool.isEmpty()) {
            node.put("pool", pool);
        }

        String mode = mode(spec.signCompletion());
        if (mode != null) {
            node.put("mode", mode);
        }
        node.put("reject", reject(spec.rejectCompletion()));

        Duration deadline = spec.dueIn() != null ? spec.dueIn() : step.timeout();
        if (deadline != null) {
            node.put("deadline", WorkflowDsl.iso(deadline));
        }
        String timeoutPolicy = timeoutPolicy(spec.metadata(), deadline != null);
        if (timeoutPolicy != null) {
            node.put("timeoutPolicy", timeoutPolicy);
        }
        node.put("allowCc", spec.allowCc());
        node.put("signAdmins", List.of(signAdmin));

        String onReject = onReject(step, index, indices);
        if (onReject != null) {
            node.put("onReject", onReject);
        }
        return node;
    }

    private static String mode(SignCompletion completion) {
        return switch (completion) {
            case AllApprove ignored -> "all";
            case AnyApprove ignored -> "any";
            case AtLeastCount count -> "quorum:" + count.count();
            case AtLeastPercentage percentage -> "ratio:" + ratio(percentage.percentage());
        };
    }

    private static String ratio(double percentage) {
        BigDecimal decimal;
        try {
            decimal = BigDecimal.valueOf(percentage);
        } catch (NumberFormatException e) {
            throw new WorkflowProviderException(
                    "seedj workflow definition: signCompletion.percentage: " + percentage, e);
        }
        BigInteger numerator = decimal.unscaledValue();
        BigInteger denominator = BigInteger.TEN.pow(decimal.scale());
        BigInteger gcd = numerator.gcd(denominator);
        numerator = numerator.divide(gcd);
        denominator = denominator.divide(gcd);
        if (numerator.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0
                || denominator.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw new WorkflowProviderException(
                    "seedj workflow definition: signCompletion.percentage cannot be represented as"
                            + " a coord ratio (num/den <= 2^31): "
                            + percentage);
        }
        return numerator + "/" + denominator;
    }

    private static String reject(RejectCompletion completion) {
        return switch (completion) {
            case AnyRejectTerminates ignored -> "anyReject";
            case AtLeastRejects rejects -> "atLeastRejects:" + rejects.count();
                // seedj 的 RecordAndContinue 在「全部席位投完」才判死锁；coord 的 onExhausted
                // 是同一口径（recordAndContinue 会提前按 unsatisfiable 回退，与 seedj TCK 不符）。
            case RecordAndContinue ignored -> "onExhausted";
        };
    }

    private String timeoutPolicy(Map<String, String> metadata, boolean hasDeadline) {
        String raw = metadata.get("timeoutPolicy");
        if (raw == null) {
            return hasDeadline ? "fail" : null;
        }
        String normalized = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (!TIMEOUT_POLICIES.contains(normalized)) {
            throw new WorkflowProviderException(
                    "seedj workflow definition: humanTask.metadata.timeoutPolicy: expected"
                            + " fail|waiting|none, got '"
                            + raw
                            + "'");
        }
        return normalized;
    }

    /**
     * 驳回路由（编译器唯一的硬约束）：前向目标只允许 1 个；上游目标走 {@code rejectToNode}。
     *
     * <p>{@code onReject} 是 coord 的定义期常量（#382 裁定），多个前向目标无法表达 ⇒ register 期拒绝。
     */
    private String onReject(StepDefinition step, int index, Map<String, Integer> indices) {
        return rejectTarget(step, indices);
    }

    /**
     * 编译出的 {@code onReject} 目标（同时供动作层判断「plain reject 是否会路由到该目标」）。
     *
     * <p>前向目标优先（唯一）；否则选下标最小的**非自身**上游目标——非审批上游只能靠 节点转场回退（coord 的 {@code rejectToNode} 要求目标曾是审批节点）。
     */
    static String rejectTarget(StepDefinition step, Map<String, Integer> indices) {
        if (step.humanTask() == null || step.humanTask().rejectTargets().isEmpty()) {
            return null;
        }
        int index = indices.get(step.stepName());
        List<String> forward = new ArrayList<>();
        List<Map.Entry<Integer, String>> upstream = new ArrayList<>();
        for (String target : step.humanTask().rejectTargets()) {
            Integer targetIndex = indices.get(target);
            if (targetIndex == null) {
                continue;
            }
            if (targetIndex > index) {
                forward.add(target);
            } else if (targetIndex < index) {
                upstream.add(Map.entry(targetIndex, target));
            }
        }
        if (forward.size() > 1) {
            throw new WorkflowProviderException(
                    "seedj workflow definition "
                            + step.stepName()
                            + ": rejectTargets has "
                            + forward.size()
                            + " forward targets; coord onReject is a definition-time constant"
                            + " (coord#382) and can express at most one");
        }
        if (forward.size() == 1) {
            return forward.get(0);
        }
        upstream.sort(Map.Entry.comparingByKey());
        return upstream.isEmpty() ? null : upstream.get(0).getValue();
    }

    /** 步骤下标表（动作层用）。 */
    static Map<String, Integer> indices(WorkflowDefinition definition) {
        Map<String, Integer> indices = new HashMap<>();
        for (int i = 0; i < definition.steps().size(); i++) {
            indices.put(definition.steps().get(i).stepName(), i);
        }
        return indices;
    }

    private void validateStructure(WorkflowDefinition definition) {
        String prefix =
                "seedj workflow definition "
                        + definition.id().namespace()
                        + "/"
                        + definition.id().name()
                        + "@"
                        + definition.version().major()
                        + "."
                        + definition.version().minor()
                        + ": ";
        ids.coordNamespace(definition.id().namespace());
        ids.coordName(definition.id());

        Set<String> names = new LinkedHashSet<>();
        Map<String, StepDefinition> byName = new HashMap<>();
        for (StepDefinition step : definition.steps()) {
            String name = step.stepName();
            if (name == null || name.isBlank()) {
                throw new WorkflowProviderException(prefix + "stepName must not be blank");
            }
            if (RESERVED_STEP_NAMES.contains(name)) {
                throw new WorkflowProviderException(
                        prefix + name + ": step name is reserved by the coord DSL");
            }
            if (!names.add(name)) {
                throw new WorkflowProviderException(prefix + name + ": duplicate step name");
            }
            byName.put(name, step);
        }
        for (StepDefinition step : definition.steps()) {
            for (String dependency : step.dependsOn()) {
                if (dependency.equals(step.stepName())) {
                    throw new WorkflowProviderException(
                            prefix + step.stepName() + ": dependsOn self");
                }
                if (!byName.containsKey(dependency)) {
                    throw new WorkflowProviderException(
                            prefix
                                    + step.stepName()
                                    + ": dependsOn unknown step '"
                                    + dependency
                                    + "'");
                }
            }
            if (step.kind() == StepKind.HUMAN_TASK) {
                for (String target : step.humanTask().rejectTargets()) {
                    if (!byName.containsKey(target)) {
                        throw new WorkflowProviderException(
                                prefix
                                        + step.stepName()
                                        + ": rejectTargets unknown step '"
                                        + target
                                        + "'");
                    }
                }
                // 触发 ratio/超时策略校验，让 register 期一次报全。
                mode(step.humanTask().signCompletion());
                timeoutPolicy(step.humanTask().metadata(), true);
            }
        }
    }

    /** 供动作层校验：判断 target 是否落在步骤的 rejectTargets 白名单内。 */
    static boolean isRejectTarget(StepDefinition step, String target) {
        return step != null && target != null && step.humanTask().rejectTargets().contains(target);
    }
}
