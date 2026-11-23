package cn.byteforce.seedj.infra.coord.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.coord.sdk.ErrorCode;
import cn.byteforce.seedj.infra.coord.workflow.CoordWorkflowErrors.ActionKind;
import cn.byteforce.seedj.workflow.spi.AssigneeNotAllowedException;
import cn.byteforce.seedj.workflow.spi.HumanTaskNotFoundException;
import cn.byteforce.seedj.workflow.spi.HumanTaskStateConflictException;
import cn.byteforce.seedj.workflow.spi.ParticipantResolutionException;
import cn.byteforce.seedj.workflow.spi.RejectTargetNotAllowedException;
import cn.byteforce.seedj.workflow.spi.WithdrawNotAllowedException;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionNotFoundException;
import cn.byteforce.seedj.workflow.spi.WorkflowProviderException;
import cn.byteforce.seedj.workflow.spi.WorkflowStateConflictException;
import cn.byteforce.seedj.workflow.spi.WorkflowVersionConflictException;
import org.junit.jupiter.api.Test;

class CoordWorkflowErrorsTest {

    private static CoordException error(ErrorCode code, String message) {
        return new CoordException(code, message);
    }

    @Test
    void notFoundDependsOnActionContext() {
        assertThat(CoordWorkflowErrors.toSeedj(error(ErrorCode.NOT_FOUND, "x"), ActionKind.VOTE))
                .isInstanceOf(HumanTaskNotFoundException.class);
        assertThat(
                        CoordWorkflowErrors.toSeedj(
                                error(ErrorCode.NOT_FOUND, "x"), ActionKind.DEFINITION))
                .isInstanceOf(WorkflowDefinitionNotFoundException.class);
    }

    @Test
    void versionConflictIsOnlyForDefinitionsAndStarts() {
        assertThat(
                        CoordWorkflowErrors.toSeedj(
                                error(ErrorCode.ALREADY_EXISTS, "x"), ActionKind.DEFINITION))
                .isInstanceOf(WorkflowVersionConflictException.class);
        assertThat(
                        CoordWorkflowErrors.toSeedj(
                                error(ErrorCode.ALREADY_EXISTS, "x"), ActionKind.ADMIN))
                .isInstanceOf(WorkflowStateConflictException.class);
    }

    @Test
    void failedPreconditionOnVoteIsASeatConflict() {
        assertThat(
                        CoordWorkflowErrors.toSeedj(
                                error(ErrorCode.FAILED_PRECONDITION, "x"), ActionKind.VOTE))
                .isInstanceOf(HumanTaskStateConflictException.class);
        assertThat(
                        CoordWorkflowErrors.toSeedj(
                                error(ErrorCode.FAILED_PRECONDITION, "x"), ActionKind.ADMIN))
                .isInstanceOf(WorkflowStateConflictException.class);
    }

    @Test
    void invalidArgumentClassifiesTargetAndParticipantFailures() {
        assertThat(
                        CoordWorkflowErrors.toSeedj(
                                error(
                                        ErrorCode.INVALID_ARGUMENT,
                                        "target node n is not in the reached-node whitelist"),
                                ActionKind.VOTE))
                .isInstanceOf(RejectTargetNotAllowedException.class);
        assertThat(
                        CoordWorkflowErrors.toSeedj(
                                error(ErrorCode.INVALID_ARGUMENT, "approve.approvers is empty"),
                                ActionKind.DEFINITION))
                .isInstanceOf(ParticipantResolutionException.class);
    }

    @Test
    void permissionDeniedIsSplitByAction() {
        assertThat(
                        CoordWorkflowErrors.toSeedj(
                                error(ErrorCode.PERMISSION_DENIED, "not your seat"),
                                ActionKind.VOTE))
                .isInstanceOf(AssigneeNotAllowedException.class);
        assertThat(
                        CoordWorkflowErrors.toSeedj(
                                error(ErrorCode.PERMISSION_DENIED, "no approve record"),
                                ActionKind.WITHDRAW))
                .isInstanceOf(WorkflowStateConflictException.class);
        assertThat(
                        CoordWorkflowErrors.toSeedj(
                                error(ErrorCode.PERMISSION_DENIED, "not initiator"),
                                ActionKind.WITHDRAW))
                .isInstanceOf(WithdrawNotAllowedException.class);
        var other =
                CoordWorkflowErrors.toSeedj(
                        error(ErrorCode.PERMISSION_DENIED, "not in on-behalf whitelist"),
                        ActionKind.ADMIN);
        assertThat(other).isInstanceOf(WorkflowProviderException.class);
        assertThat(other.getMessage())
                .contains("services.workflow_on_behalf_services")
                .contains("seedj.workflow.coord.sign-admin");
    }

    @Test
    void unauthenticatedPointsAtConfiguration() {
        var mapped =
                CoordWorkflowErrors.toSeedj(
                        error(ErrorCode.UNAUTHENTICATED, "bad token"), ActionKind.READ);
        assertThat(mapped).isInstanceOf(WorkflowProviderException.class);
        assertThat(mapped.getMessage()).contains("services.workflow_on_behalf_services");
    }

    @Test
    void transportFailuresKeepTheCause() {
        CoordException cause = error(ErrorCode.UNAVAILABLE, "agent down");
        var mapped = CoordWorkflowErrors.toSeedj(cause, ActionKind.VOTE);
        assertThat(mapped).isInstanceOf(WorkflowProviderException.class);
        assertThat(mapped.getCause()).isSameAs(cause);
    }
}
