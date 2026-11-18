package cn.byteforce.seedj.starter.ai.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.ai.ActionClass;
import cn.byteforce.seedj.ai.CommandRef;
import cn.byteforce.seedj.ai.DecisionKind;
import cn.byteforce.seedj.ai.Proposal;
import cn.byteforce.seedj.starter.ai.AiProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 只读任务的工具选择基线（eval baseline）：{@code eval/read-only-cases.jsonl} 的 20 条用例各跑一遍
 * {@link BoundedLoop}，量两个指标——<b>工具选择准确率</b>与<b>步数</b>。
 *
 * <p><b>这两个指标量的是什么</b>：夹具是一句自然语言任务 + 应该选中的只读工具。指标一「选中的工具 ==
 * {@code expected_tool}」用模型端口产出的提案来衡量，是这个 baseline 唯一真正在测的东西；指标二「走完一轮的步数不超过部署上限」
 * 顺带证明循环没有为了对齐答案而多绕步数。
 *
 * <p><b>为什么不是只读执行</b>：T0 的 {@link BoundedLoop} 不执行只读查询（{@code READ} 提案是循环内转换，见
 * {@link ToolInvoker#read}），因此夹具里的只读工具名被<b>投影</b>成一次 {@code EXECUTE_TOOL} 提议，让选择结果经过循环的
 * 写分支：决策口与工具口都是 fake（一律放行、一律成功），没有任何真实命令被执行。换言之本测试只度量「指令 → 工具选择」，
 * <b>没有、也不能</b>说明只读路径跑通了。
 *
 * <p><b>为什么模型端口是假的</b>：结论的可重复性优先于结论的强度。{@link ScriptedLlmGateway} 不是模型，是一张「关键词 →
 * 工具」的确定性路由表：它让这个 gate 在离线、无密钥、无限额的环境里也能跑，量的因此是<b>夹具与标签的一致性</b>与循环的装配，
 * 不是某个真实模型的准确率；换真模型只需替换这一个端口。
 *
 * <p><b>阈值是 ratchet</b>：{@value #MIN_TOOL_CHOICE_ACCURACY} 允许 20 条里错 1 条，只准收紧。改夹具的说明文字或换路由表
 * 都会让这里变红——这正是它存在的意义。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class EvalBaselineTest {

    /** 工具选择准确率下限：20 条里至少 19 条选中 {@code expected_tool}。 */
    private static final double MIN_TOOL_CHOICE_ACCURACY = 0.95;

    /** 夹具条目数：少于这个数就不是同一套基线了。 */
    private static final int EXPECTED_CASES = 20;

    /** 夹具路径（classpath）；一行一条 JSON。 */
    private static final String CASES_RESOURCE = "/eval/read-only-cases.jsonl";

    private static final String CONTRACT_ID = "018f2e3a-0000-7000-8000-00000000c0e1";

    private static final UUID CONVERSATION_ID =
            UUID.fromString("018f2e3a-0000-7000-8000-00000000e0e1");

    private static final UUID DECISION_ID = UUID.fromString("018f2e3a-0000-7000-8000-0000000d00e1");

    private static final UUID COMMAND_ID = UUID.fromString("018f2e3a-0000-7000-8000-0000000c00e1");

    private static final String TOKEN = "tkn-eval-baseline-decision-token";

    private static final String AUDIENCE = "eval-service";

    /** 选不出工具时用的哨兵名：该条记为选择错误，而不是抛异常掩盖准确率。 */
    private static final String UNROUTED = "(unrouted)";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /**
     * 默认配置实例：只用它取 {@code seedj.ai.runtime.max-steps} 的默认值（6），不做绑定。
     *
     * <p>全部段给 {@code null} 是刻意的：记录构造器会把缺省段补成默认实例，取值路径因此与真实绑定一致。
     */
    private static final AiProperties DEFAULTS =
            new AiProperties(true, null, null, null, null, null, List.of(), List.of());

    /** 路由表：工具 → 触发词。顺序即并列时的优先级（先到先得）。 */
    private static final Map<String, List<String>> TOOL_MARKERS = toolMarkers();

    @Test
    @DisplayName("夹具是 20 条只读任务，每条都带 id/instruction/expected_tool")
    void fixture_declares_twenty_read_only_cases() {
        List<EvalCase> cases = cases();

        assertThat(cases).hasSize(EXPECTED_CASES);
        assertThat(cases).extracting(EvalCase::id).doesNotHaveDuplicates();
        assertThat(cases)
                .allSatisfy(
                        evalCase -> {
                            assertThat(evalCase.id()).isNotBlank();
                            assertThat(evalCase.instruction()).isNotBlank();
                            assertThat(TOOL_MARKERS)
                                    .as("用例 %s 的 expected_tool 必须在路由表的工具目录里", evalCase.id())
                                    .containsKey(evalCase.expectedTool());
                        });
    }

    @Test
    @DisplayName("20 条只读任务的工具选择准确率不低于 0.95")
    void tool_choice_accuracy_meets_baseline() {
        List<CaseRun> runs = runAll(cases());
        List<CaseRun> misses = runs.stream().filter(run -> !run.hit()).toList();
        double accuracy = (double) (runs.size() - misses.size()) / runs.size();

        System.out.printf(
                "eval: tool_choice_accuracy=%.3f (%d/%d)%n",
                accuracy, runs.size() - misses.size(), runs.size());

        assertThat(accuracy)
                .as("选错的用例（expected → selected）：%s", misses)
                .isGreaterThanOrEqualTo(MIN_TOOL_CHOICE_ACCURACY);
    }

    @Test
    @DisplayName("每条用例都在默认步数上限内以 FINISHED 收尾")
    void every_case_finishes_within_default_step_limit() {
        int stepLimit = DEFAULTS.runtime().maxSteps();
        List<CaseRun> runs = runAll(cases());

        assertThat(stepLimit).as("基线跑的是 seedj.ai.runtime.max-steps 的默认值").isEqualTo(6);
        assertThat(runs)
                .allSatisfy(
                        run ->
                                assertThat(run.result().outcome())
                                        .as("用例 %s 的结局", run.evalCase().id())
                                        .isEqualTo(LoopResult.Outcome.FINISHED));
        assertThat(runs.stream().mapToInt(run -> run.result().steps()).max().orElseThrow())
                .as("最长一轮的步数")
                .isLessThanOrEqualTo(stepLimit);
    }

    /**
     * 逐条跑循环：每条用例一个新循环、一套新的 fake 端口，用例之间不共享状态。
     *
     * @param cases 夹具条目
     * @return 每条用例的选择结果与结局
     */
    private static List<CaseRun> runAll(List<EvalCase> cases) {
        int stepLimit = DEFAULTS.runtime().maxSteps();
        List<CaseRun> runs = new ArrayList<>(cases.size());
        for (EvalCase evalCase : cases) {
            RecordingToolInvoker tools = new RecordingToolInvoker();
            BoundedLoop loop =
                    new BoundedLoop(
                            new ScriptedLlmGateway(),
                            new AllowAllDecisionClient(),
                            tools,
                            stepLimit,
                            AUDIENCE);
            TaskContract contract =
                    new TaskContract(
                            CONTRACT_ID, CONVERSATION_ID, evalCase.instruction(), stepLimit);
            LoopResult result = loop.run(contract);
            runs.add(new CaseRun(evalCase, tools.selected(), result));
        }
        return List.copyOf(runs);
    }

    /** 读夹具：允许空行，其它行必须是带三个字段的 JSON 对象。 */
    private static List<EvalCase> cases() {
        List<EvalCase> cases = new ArrayList<>();
        for (String line : lines()) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode node = MAPPER.readTree(line);
            cases.add(
                    new EvalCase(
                            required(node, "id", line),
                            required(node, "instruction", line),
                            required(node, "expected_tool", line)));
        }
        return List.copyOf(cases);
    }

    private static List<String> lines() {
        try (InputStream stream = EvalBaselineTest.class.getResourceAsStream(CASES_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("缺少夹具: " + CASES_RESOURCE);
            }
            return List.of(new String(stream.readAllBytes(), StandardCharsets.UTF_8).split("\n"));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static String required(JsonNode node, String field, String line) {
        JsonNode value = node.get(field);
        if (value == null || value.asString().isBlank()) {
            throw new IllegalStateException("夹具条目缺少 " + field + ": " + line);
        }
        return value.asString();
    }

    /**
     * 路由表：只读工具的触发词。
     *
     * <p>词与工具一一对应，因此一条指令只命中一张表；同一张表命中多次也只算同一个工具。
     *
     * @return 工具 → 触发词（顺序即并列优先级）
     */
    private static Map<String, List<String>> toolMarkers() {
        Map<String, List<String>> markers = new LinkedHashMap<>();
        markers.put("hr.employee.get", List.of("员工档案"));
        markers.put("hr.employee.search", List.of("搜员工"));
        markers.put("hr.org-unit.tree", List.of("组织树"));
        markers.put("ticket.get", List.of("工单详情"));
        markers.put("ticket.list", List.of("工单列表"));
        markers.put("ticket.timeline", List.of("时间线"));
        markers.put("audit.entry.query", List.of("审计条目"));
        markers.put("approval.pending.list", List.of("待审批清单"));
        markers.put("payroll.run.summary", List.of("工资批次汇总"));
        markers.put("iam.role.grants", List.of("角色授权"));
        return Collections.unmodifiableMap(markers);
    }

    /**
     * 按触发词把指令路由到工具：命中次数最多的那张表胜出，一次都没命中时返回 {@value #UNROUTED}。
     *
     * <p>并列时保持 {@link #TOOL_MARKERS} 的顺序（只用严格大于替换），因此路由是确定性的。
     */
    private static String route(String instruction) {
        String selected = UNROUTED;
        int best = 0;
        for (Map.Entry<String, List<String>> entry : TOOL_MARKERS.entrySet()) {
            int hits = 0;
            for (String marker : entry.getValue()) {
                hits += occurrences(instruction, marker);
            }
            if (hits > best) {
                best = hits;
                selected = entry.getKey();
            }
        }
        return selected;
    }

    private static int occurrences(String text, String marker) {
        int count = 0;
        int index = text.indexOf(marker);
        while (index >= 0) {
            count++;
            index = text.indexOf(marker, index + 1);
        }
        return count;
    }

    /**
     * 一条夹具：任务标识、自然语言指令与应该选中的只读工具。
     *
     * @param id 用例标识
     * @param instruction 任务指令原文
     * @param expectedTool 期望被选中的工具
     */
    private record EvalCase(String id, String instruction, String expectedTool) {}

    /**
     * 一条用例的实跑结果。
     *
     * @param evalCase 用例
     * @param selected 实际流到工具口的命令类型；没走到执行时为 {@code null}
     * @param result 循环结局
     */
    private record CaseRun(EvalCase evalCase, String selected, LoopResult result) {

        /** 选择是否正确。 */
        boolean hit() {
            return evalCase.expectedTool().equals(selected);
        }

        @Override
        public String toString() {
            return evalCase.id() + " " + evalCase.expectedTool() + " → " + selected;
        }
    }

    /**
     * 假模型端口：第一步按关键词表给一个 {@code EXECUTE_TOOL} 提议，第二步收尾。
     *
     * <p>它不是模型（见类说明）：提示里的观测一条都不读，只读指令；两次以上的步序一律 {@code FINISH}，所以步数恒为 2。
     */
    private static final class ScriptedLlmGateway implements LlmGateway {

        @Override
        public Proposal next(Prompt prompt) {
            if (prompt.step() > 1) {
                return new Proposal(DecisionKind.FINISH, null, null, null, "答案已给出", List.of());
            }
            return new Proposal(
                    DecisionKind.EXECUTE_TOOL,
                    ActionClass.READ_ONLY,
                    new CommandRef(route(prompt.instructions()), List.of(), null),
                    Map.of(),
                    "按指令选择只读工具",
                    List.of());
        }
    }

    /** 假决策口：一律放行（eval 不测授权），放行时给一枚 token 让循环能走到工具口。 */
    private static final class AllowAllDecisionClient implements DecisionClient {

        @Override
        public DecisionGrant ask(
                TaskContract contract, int step, Proposal proposal, String audience) {
            return new DecisionGrant(
                    DECISION_ID, TOKEN, null, Instant.parse("2026-11-17T09:41:12Z"));
        }

        @Override
        public void report(UUID decisionId, UUID commandId) {
            // eval 只看循环结局，不测账本：上报是 no-op。
        }
    }

    /** 假工具口：只记下流过它的命令类型，一律成功。 */
    private static final class RecordingToolInvoker implements ToolInvoker {

        private final Set<String> executed = new LinkedHashSet<>();

        @Override
        public ToolResult read(Call call) {
            throw new AssertionError("T0 的循环不执行只读查询: " + call.commandType());
        }

        @Override
        public ToolResult execute(Call call, String token, UUID decisionId) {
            executed.add(call.commandType());
            return new ToolResult(true, "{}", COMMAND_ID);
        }

        /** 第一条流到工具口的命令类型；一条都没有时为 {@code null}。 */
        String selected() {
            return executed.isEmpty() ? null : executed.iterator().next();
        }
    }
}
