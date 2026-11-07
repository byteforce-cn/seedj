package cn.byteforce.seedj.infra.jdbc.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.iam.PolicyComparisonOperator;
import cn.byteforce.seedj.iam.PolicyCondition;
import cn.byteforce.seedj.iam.PolicyOperand;
import cn.byteforce.seedj.iam.PolicyOperandSource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PolicyConditionCodecTest {

    private final PolicyConditionCodec codec = new PolicyConditionCodec();

    @Test
    void encodeTagsAlwaysExplicitly() {
        assertThat(codec.encode(PolicyCondition.Always.INSTANCE))
                .isEqualTo("{\"type\":\"always\"}");
    }

    @Test
    void blankJsonDecodesToAlways() {
        assertThat(codec.decode(null)).isSameAs(PolicyCondition.Always.INSTANCE);
        assertThat(codec.decode("")).isSameAs(PolicyCondition.Always.INSTANCE);
        assertThat(codec.decode("   ")).isSameAs(PolicyCondition.Always.INSTANCE);
    }

    @Test
    void alwaysRoundTrips() {
        assertThat(codec.decode(codec.encode(PolicyCondition.Always.INSTANCE)))
                .isSameAs(PolicyCondition.Always.INSTANCE);
    }

    @Test
    void compareWithLiteralRoundTrips() {
        PolicyCondition condition =
                new PolicyCondition.Compare(
                        PolicyOperand.resource("ownerId"),
                        PolicyComparisonOperator.EQ,
                        PolicyOperand.literal("u-1"));

        PolicyCondition decoded = codec.decode(codec.encode(condition));

        assertThat(decoded).isEqualTo(condition);
    }

    @Test
    void literalScalarTypesRoundTrip() {
        for (Object literal : List.of("text", 42, 4.5, true, List.of("a", "b"), Map.of("k", "v"))) {
            PolicyCondition condition =
                    new PolicyCondition.Compare(
                            PolicyOperand.subject("attributes"),
                            PolicyComparisonOperator.IN,
                            PolicyOperand.literal(literal));

            assertThat(codec.decode(codec.encode(condition))).isEqualTo(condition);
        }
    }

    @Test
    void nonLiteralOperandsRoundTrip() {
        PolicyCondition condition =
                new PolicyCondition.And(
                        List.of(
                                new PolicyCondition.Compare(
                                        PolicyOperand.subject("department"),
                                        PolicyComparisonOperator.EQ,
                                        PolicyOperand.literal("sales")),
                                new PolicyCondition.Compare(
                                        PolicyOperand.action(),
                                        PolicyComparisonOperator.IN,
                                        PolicyOperand.literal(List.of("read", "write"))),
                                new PolicyCondition.Compare(
                                        PolicyOperand.environment("time.hour"),
                                        PolicyComparisonOperator.GE,
                                        PolicyOperand.literal(9))));

        PolicyCondition decoded = codec.decode(codec.encode(condition));

        assertThat(decoded).isEqualTo(condition);
    }

    @Test
    void nestedBooleanAndExistsRoundTrip() {
        PolicyCondition condition =
                new PolicyCondition.Or(
                        List.of(
                                new PolicyCondition.Not(
                                        new PolicyCondition.Exists(
                                                PolicyOperand.resource("deletedAt"))),
                                new PolicyCondition.And(
                                        List.of(
                                                new PolicyCondition.Compare(
                                                        PolicyOperand.resource("status"),
                                                        PolicyComparisonOperator.EQ,
                                                        PolicyOperand.literal("active")),
                                                new PolicyCondition.Exists(
                                                        PolicyOperand.subject("groups"))))));

        PolicyCondition decoded = codec.decode(codec.encode(condition));

        assertThat(decoded).isEqualTo(condition);
    }

    @Test
    void unknownConditionTypeIsRejected() {
        assertThatThrownBy(() -> codec.decode("{\"type\":\"script\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported policy condition type: script");
    }

    @Test
    void unknownOperatorIsRejected() {
        String json =
                """
                {"type":"compare",
                 "left":{"source":"SUBJECT","path":"a"},
                 "operator":"MATCHES",
                 "right":{"source":"LITERAL","path":"","value":"b"}}
                """;

        assertThatThrownBy(() -> codec.decode(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MATCHES");
    }

    @Test
    void missingOperandPathDecodesToEmptyPath() {
        PolicyCondition decoded =
                codec.decode("{\"type\":\"exists\",\"operand\":{\"source\":\"SUBJECT\"}}");

        assertThat(decoded)
                .isEqualTo(
                        new PolicyCondition.Exists(
                                new PolicyOperand(PolicyOperandSource.SUBJECT, "", null)));
    }

    @Test
    void literalWithoutValueIsRejected() {
        assertThatThrownBy(
                        () ->
                                codec.decode(
                                        "{\"type\":\"exists\",\"operand\":{\"source\":\"LITERAL\"}}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("literal operand requires a value");
    }

    @Test
    void nonLiteralOperandIgnoresStrayValue() {
        PolicyCondition decoded =
                codec.decode(
                        """
                {"type":"compare",
                 "left":{"source":"RESOURCE","path":"ownerId","value":"ignored"},
                 "operator":"EQ",
                 "right":{"source":"LITERAL","path":"","value":"u-1"}}
                """);

        assertThat(decoded)
                .isEqualTo(
                        new PolicyCondition.Compare(
                                new PolicyOperand(PolicyOperandSource.RESOURCE, "ownerId", null),
                                PolicyComparisonOperator.EQ,
                                PolicyOperand.literal("u-1")));
    }
}
