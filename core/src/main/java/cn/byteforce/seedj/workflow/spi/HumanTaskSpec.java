package cn.byteforce.seedj.workflow.spi;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 人工任务规格。
 *
 * <p>v2.4 构造器约束（无静默修正）：
 *
 * <ul>
 *   <li>{@code allowAddAssignee == true} 且 {@code allowedAddPositions} 为空 → 抛 {@link
 *       IllegalArgumentException}，要求调用方显式声明位置（只允许当前节点时传 {@code
 *       Set.of(AddAssigneePosition.CURRENT)}）。
 *   <li>{@code allowAddAssignee == false} 且 {@code allowedAddPositions} 非空 → 抛 {@link
 *       IllegalArgumentException}，避免矛盾输入。
 * </ul>
 */
public record HumanTaskSpec(
        List<ParticipantSpec> assignees,
        SignCompletion signCompletion,
        RejectCompletion rejectCompletion,
        Set<String> rejectTargets,
        String formKey,
        Map<String, String> formSchema,
        boolean allowAddAssignee,
        Set<AddAssigneePosition> allowedAddPositions,
        boolean allowRemoveAssignee,
        boolean allowTransfer,
        boolean allowDelegate,
        boolean allowWithdraw,
        boolean allowUrge,
        boolean allowCc,
        Duration dueIn,
        Map<String, String> metadata) {
    public HumanTaskSpec {
        assignees = assignees == null ? List.of() : List.copyOf(assignees);
        Objects.requireNonNull(signCompletion, "signCompletion cannot be null");
        rejectCompletion = rejectCompletion == null ? new AnyRejectTerminates() : rejectCompletion;
        if (assignees.isEmpty()) throw new IllegalArgumentException("assignees cannot be empty");
        if (signCompletion instanceof AtLeastCount c && c.count() > assignees.size())
            throw new IllegalArgumentException("AtLeastCount exceeds assignee size");
        if (rejectCompletion instanceof AtLeastRejects r && r.count() > assignees.size())
            throw new IllegalArgumentException("AtLeastRejects exceeds assignee size");
        rejectTargets = rejectTargets == null ? Set.of() : Set.copyOf(rejectTargets);
        formSchema = formSchema == null ? Map.of() : Map.copyOf(formSchema);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        if (dueIn != null && (dueIn.isNegative() || dueIn.isZero()))
            throw new IllegalArgumentException("dueIn must be positive");

        Set<AddAssigneePosition> positions =
                allowedAddPositions == null ? Set.of() : Set.copyOf(allowedAddPositions);
        if (allowAddAssignee && positions.isEmpty()) {
            throw new IllegalArgumentException(
                    "allowAddAssignee=true requires explicit non-empty allowedAddPositions; "
                            + "pass Set.of(CURRENT) if only current-node add-assignee is allowed");
        }
        if (!allowAddAssignee && !positions.isEmpty()) {
            throw new IllegalArgumentException(
                    "allowAddAssignee=false conflicts with non-empty allowedAddPositions");
        }
        allowedAddPositions = positions;
    }
}
