package cn.byteforce.seedj.infra.jdbc.iam;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.ScimComparisonOperator;
import cn.byteforce.seedj.iam.ScimFilter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IamFilterSqlTest {

    private static ScimFilter.Compare compare(String attribute, ScimComparisonOperator operator,
                                             Object value) {
        return new ScimFilter.Compare(attribute, operator, value);
    }

    @Test
    void missingFilterMatchesEveryRow() {
        IamFilterSql.Sql users = IamFilterSql.user(null);
        IamFilterSql.Sql groups = IamFilterSql.group(null);

        assertThat(users.where()).isEqualTo("TRUE");
        assertThat(users.arguments()).isEmpty();
        assertThat(groups.where()).isEqualTo("TRUE");
        assertThat(groups.arguments()).isEmpty();
    }

    @Test
    void userNameEqualityTargetsNormalizedColumn() {
        IamFilterSql.Sql sql = IamFilterSql.user(
                compare("userName", ScimComparisonOperator.EQ, "Alice.Smith"));

        assertThat(sql.where()).isEqualTo("user_name_normalized = ?");
        assertThat(sql.arguments()).containsExactly("alice.smith");
    }

    @Test
    void userNameInequalityTargetsNormalizedColumn() {
        IamFilterSql.Sql sql = IamFilterSql.user(
                compare("userName", ScimComparisonOperator.NE, "Alice"));

        assertThat(sql.where()).isEqualTo("user_name_normalized IS DISTINCT FROM ?");
        assertThat(sql.arguments()).containsExactly("alice");
    }

    @Test
    void stringOperatorsUseCaseInsensitivePatterns() {
        assertThat(IamFilterSql.user(compare("email", ScimComparisonOperator.CO, "Example"))
                .where()).isEqualTo("email ILIKE '%' || ? || '%'");
        assertThat(IamFilterSql.user(compare("email", ScimComparisonOperator.SW, "alice"))
                .where()).isEqualTo("email ILIKE ? || '%'");
        assertThat(IamFilterSql.user(compare("email", ScimComparisonOperator.EW, "example.com"))
                .where()).isEqualTo("email ILIKE '%' || ?");
    }

    @Test
    void containmentDoesNotLowercaseItsArgument() {
        IamFilterSql.Sql sql = IamFilterSql.user(
                compare("userName", ScimComparisonOperator.CO, "Alice"));

        assertThat(sql.arguments()).containsExactly("Alice");
    }

    @Test
    void orderingOperatorsKeepTheRawValue() {
        assertThat(IamFilterSql.user(compare("meta.version", ScimComparisonOperator.GT, 3L))
                .where()).isEqualTo("version > ?");
        assertThat(IamFilterSql.user(compare("meta.version", ScimComparisonOperator.LT, 3L))
                .where()).isEqualTo("version < ?");
        assertThat(IamFilterSql.user(compare("meta.version", ScimComparisonOperator.GE, 3L))
                .where()).isEqualTo("version >= ?");
        assertThat(IamFilterSql.user(compare("meta.version", ScimComparisonOperator.LE, 3L))
                .where()).isEqualTo("version <= ?");
        assertThat(IamFilterSql.user(compare("meta.version", ScimComparisonOperator.LE, 3L))
                .arguments()).containsExactly(3L);
    }

    @Test
    void nonStringAttributesAreNotLowercased() {
        IamFilterSql.Sql sql = IamFilterSql.user(
                compare("active", ScimComparisonOperator.EQ, Boolean.TRUE));

        assertThat(sql.where()).isEqualTo("active = ?");
        assertThat(sql.arguments()).containsExactly(Boolean.TRUE);
    }

    @Test
    void nestedAttributesMapToJsonbColumns() {
        assertThat(IamFilterSql.user(new ScimFilter.Present("name.givenName")).where())
                .isEqualTo("attributes->>'givenName' IS NOT NULL");
        assertThat(IamFilterSql.user(new ScimFilter.Present("name.familyName")).where())
                .isEqualTo("attributes->>'familyName' IS NOT NULL");
        assertThat(IamFilterSql.user(new ScimFilter.Present("nickName")).where())
                .isEqualTo("attributes->>'nickName' IS NOT NULL");
        assertThat(IamFilterSql.user(new ScimFilter.Present("phoneNumbers.value")).where())
                .isEqualTo("attributes->>'phoneNumber' IS NOT NULL");
    }

    @Test
    void presentOnScalarColumnUsesIsNotNull() {
        IamFilterSql.Sql sql = IamFilterSql.user(new ScimFilter.Present("displayName"));

        assertThat(sql.where()).isEqualTo("display_name IS NOT NULL");
        assertThat(sql.arguments()).isEmpty();
    }

    @Test
    void andWrapsEachClauseAndKeepsArgumentOrder() {
        IamFilterSql.Sql sql = IamFilterSql.user(new ScimFilter.And(List.of(
                compare("userName", ScimComparisonOperator.EQ, "Alice"),
                compare("active", ScimComparisonOperator.EQ, Boolean.TRUE))));

        assertThat(sql.where()).isEqualTo(
                "(user_name_normalized = ?) AND (active = ?)");
        assertThat(sql.arguments()).containsExactly("alice", Boolean.TRUE);
    }

    @Test
    void orWrapsEachClauseAndKeepsArgumentOrder() {
        IamFilterSql.Sql sql = IamFilterSql.user(new ScimFilter.Or(List.of(
                compare("email", ScimComparisonOperator.EW, "example.com"),
                compare("userName", ScimComparisonOperator.SW, "svc"))));

        assertThat(sql.where()).isEqualTo("(email ILIKE '%' || ?) OR (user_name_normalized ILIKE ? || '%')");
        assertThat(sql.arguments()).containsExactly("example.com", "svc");
    }

    @Test
    void notNegatesTheInnerClause() {
        IamFilterSql.Sql sql = IamFilterSql.user(new ScimFilter.Not(
                new ScimFilter.Or(List.of(
                        compare("active", ScimComparisonOperator.EQ, Boolean.FALSE),
                        new ScimFilter.Present("externalId")))));

        assertThat(sql.where()).isEqualTo(
                "NOT ((active = ?) OR (external_id IS NOT NULL))");
        assertThat(sql.arguments()).containsExactly(Boolean.FALSE);
    }

    @Test
    void groupFilterUsesGroupColumns() {
        IamFilterSql.Sql sql = IamFilterSql.group(new ScimFilter.And(List.of(
                compare("displayName", ScimComparisonOperator.EQ, "Sales"),
                compare("externalId", ScimComparisonOperator.SW, "ext-"))));

        assertThat(sql.where()).isEqualTo("(display_name = ?) AND (external_id ILIKE ? || '%')");
        assertThat(sql.arguments()).containsExactly("Sales", "ext-");
    }

    @Test
    void groupPresentMembershipUsesExistsSubquery() {
        IamFilterSql.Sql sql = IamFilterSql.group(new ScimFilter.Present("members.value"));

        assertThat(sql.where()).isEqualTo(
                "EXISTS (SELECT 1 FROM iam_group_member gm WHERE gm.group_id = iam_group.id)");
        assertThat(sql.arguments()).isEmpty();
    }

    @Test
    void groupMembershipEqualityBindsTheMemberId() {
        IamFilterSql.Sql sql = IamFilterSql.group(
                compare("members.value", ScimComparisonOperator.EQ, "u-1"));

        assertThat(sql.where()).isEqualTo(
                "EXISTS (SELECT 1 FROM iam_group_member gm WHERE gm.group_id = iam_group.id"
                        + " AND gm.user_id = ?)");
        assertThat(sql.arguments()).containsExactly("u-1");
    }

    @Test
    void groupMembershipInequalityNegatesTheSubquery() {
        IamFilterSql.Sql sql = IamFilterSql.group(
                compare("members.value", ScimComparisonOperator.NE, "u-1"));

        assertThat(sql.where()).startsWith("NOT EXISTS (SELECT 1 FROM iam_group_member gm");
        assertThat(sql.arguments()).containsExactly("u-1");
    }

    @Test
    void groupMembershipRejectsOtherOperators() {
        ScimFilter.Compare filter = compare("members.value", ScimComparisonOperator.GT, "u-1");

        assertThatThrownBy(() -> IamFilterSql.group(filter))
                .isInstanceOf(IamException.class)
                .hasMessageContaining("unsupported operator for members.value")
                .extracting(error -> ((IamException) error).getCode())
                .isEqualTo(IamErrorCode.SCIM_FILTER_INVALID);
    }

    @Test
    void userFilterHasNoMembershipColumn() {
        ScimFilter.Present filter = new ScimFilter.Present("members.value");

        assertThatThrownBy(() -> IamFilterSql.user(filter))
                .isInstanceOf(IamException.class)
                .hasMessageContaining("unsupported attribute 'members.value'");
    }

    @Test
    void unknownUserAttributeIsRejected() {
        ScimFilter.Compare filter = compare("addresses.country", ScimComparisonOperator.EQ, "NL");

        assertThatThrownBy(() -> IamFilterSql.user(filter))
                .isInstanceOf(IamException.class)
                .hasMessageContaining("unsupported attribute 'addresses.country'")
                .extracting(error -> ((IamException) error).getCode())
                .isEqualTo(IamErrorCode.SCIM_FILTER_INVALID);
    }

    @Test
    void userOnlyAttributeIsRejectedForGroups() {
        ScimFilter.Compare filter = compare("email", ScimComparisonOperator.EQ, "a@example.com");

        assertThatThrownBy(() -> IamFilterSql.group(filter))
                .isInstanceOf(IamException.class)
                .hasMessageContaining("unsupported attribute 'email'");
    }
}
