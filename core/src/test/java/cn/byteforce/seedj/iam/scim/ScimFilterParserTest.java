package cn.byteforce.seedj.iam.scim;

import cn.byteforce.seedj.iam.ScimComparisonOperator;

import cn.byteforce.seedj.iam.ScimFilter;

import cn.byteforce.seedj.iam.IamException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScimFilterParserTest {

    @Test
    void respectsAndBeforeOrAndParentheses() {
        ScimFilter filter = ScimFilterParser.parse(
                "userName eq \"alice\" or userName eq \"bob\" and active eq true");
        assertThat(filter).isInstanceOf(ScimFilter.Or.class);
        ScimFilter.Or or = (ScimFilter.Or) filter;
        assertThat(or.filters()).hasSize(2);
        assertThat(or.filters().get(1)).isInstanceOf(ScimFilter.And.class);

        ScimFilter grouped = ScimFilterParser.parse(
                "(userName eq \"alice\" or userName eq \"bob\") and active eq true");
        assertThat(grouped).isInstanceOf(ScimFilter.And.class);
    }

    @Test
    void parsesPresentAndComparisonOperators() {
        assertThat(ScimFilterParser.parse("externalId pr")).isInstanceOf(ScimFilter.Present.class);
        ScimFilter.Compare compare = (ScimFilter.Compare) ScimFilterParser.parse(
                "emails.value co \"@example.com\"");
        assertThat(compare.attribute()).isEqualTo("emails.value");
        assertThat(compare.operator()).isEqualTo(ScimComparisonOperator.CO);
        assertThat(compare.value()).isEqualTo("@example.com");
    }

    @Test
    void keepsQuotedInjectionPayloadAsLiteral() {
        ScimFilter.Compare compare = (ScimFilter.Compare) ScimFilterParser.parse(
                "userName eq \"x\\\" or 1=1 --\"");
        assertThat(compare.value()).isEqualTo("x\" or 1=1 --");
    }

    @Test
    void parsesNotAndNumericValues() {
        ScimFilter.Not not = (ScimFilter.Not) ScimFilterParser.parse("not (active eq false)");
        assertThat(not.filter()).isInstanceOf(ScimFilter.Compare.class);
        ScimFilter.Compare compare = (ScimFilter.Compare) ScimFilterParser.parse("version gt 3");
        assertThat(compare.value()).isEqualTo(3L);
    }

    @Test
    void rejectsMalformedFilters() {
        assertThatThrownBy(() -> ScimFilterParser.parse("userName eq"))
                .isInstanceOf(IamException.class);
        assertThatThrownBy(() -> ScimFilterParser.parse("(userName eq \"x\""))
                .isInstanceOf(IamException.class);
        assertThatThrownBy(() -> ScimFilterParser.parse("userName xx \"x\""))
                .isInstanceOf(IamException.class);
    }

    @Test
    void parsesPatchOperationsAndMemberPath() {
        List<ScimPatchOperation> operations = ScimPatchParser.parse(List.of(
                java.util.Map.of("op", "replace", "path", "active", "value", false),
                java.util.Map.of("op", "remove", "path", "members[value eq \"u-1\"]")));

        assertThat(operations).hasSize(2);
        ScimPatchPath path = ScimPatchPath.parse(operations.get(1).path());
        assertThat(path.attribute()).isEqualTo("members");
        assertThat(path.valueFilter()).isInstanceOf(ScimFilter.Compare.class);
    }
}
