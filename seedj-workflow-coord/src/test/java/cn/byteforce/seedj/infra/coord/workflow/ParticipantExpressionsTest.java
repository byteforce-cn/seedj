package cn.byteforce.seedj.infra.coord.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.workflow.spi.ParticipantResolutionException;
import cn.byteforce.seedj.workflow.spi.ParticipantSpec;
import cn.byteforce.seedj.workflow.spi.ParticipantType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ParticipantExpressionsTest {

    @Test
    void bareExpressionIsRewrittenToContextJq() {
        assertThat(ParticipantExpressions.toJq("${approver}"))
                .isEqualTo("${ $context.approver }");
        assertThat(ParticipantExpressions.toJq("${ $input.x }")).isEqualTo("${ $input.x }");
        assertThat(ParticipantExpressions.toJq("${ .x }")).isEqualTo("${ .x }");
        assertThat(ParticipantExpressions.toJq("literal")).isEqualTo("literal");
    }

    @Test
    void resolveReadsVariablesAndSplitsCommaSeparatedParticipants() {
        assertThat(
                        ParticipantExpressions.resolve(
                                "${approver}", ParticipantType.USER, Map.of("approver", "u7")))
                .containsExactly(new ParticipantSpec(ParticipantType.USER, "u7", Map.of()));

        List<ParticipantSpec> many =
                ParticipantExpressions.resolve(
                        "${approver}", ParticipantType.USER, Map.of("approver", " u7, u8 ,"));
        assertThat(many)
                .extracting(ParticipantSpec::value)
                .containsExactly("u7", "u8");
    }

    @Test
    void missingVariableIsAParticipantResolutionFailure() {
        assertThatThrownBy(
                        () ->
                                ParticipantExpressions.resolve(
                                        "${approver}", ParticipantType.USER, Map.of()))
                .isInstanceOf(ParticipantResolutionException.class)
                .hasMessageContaining("${approver}");
    }

    @Test
    void blankResolutionFailsClosed() {
        assertThatThrownBy(
                        () ->
                                ParticipantExpressions.resolve(
                                        " , ", ParticipantType.USER, Map.of()))
                .isInstanceOf(ParticipantResolutionException.class);
    }
}
