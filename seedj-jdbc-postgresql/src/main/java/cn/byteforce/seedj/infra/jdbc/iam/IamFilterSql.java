package cn.byteforce.seedj.infra.jdbc.iam;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.ScimComparisonOperator;
import cn.byteforce.seedj.iam.ScimFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Translates the SCIM AST into parameterized SQL using a strict attribute whitelist. Attribute
 * names never come from user input; values always do, and they are bound as parameters.
 */
final class IamFilterSql {

    record Sql(String where, List<Object> arguments) {}

    private static final Map<String, String> USER_COLUMNS =
            Map.ofEntries(
                    Map.entry("id", "id"),
                    Map.entry("userName", "user_name_normalized"),
                    Map.entry("externalId", "external_id"),
                    Map.entry("displayName", "display_name"),
                    Map.entry("emails.value", "email"),
                    Map.entry("email", "email"),
                    Map.entry("active", "active"),
                    Map.entry("meta.version", "version"),
                    Map.entry("name.givenName", "attributes->>'givenName'"),
                    Map.entry("name.familyName", "attributes->>'familyName'"),
                    Map.entry("nickName", "attributes->>'nickName'"),
                    Map.entry("phoneNumbers.value", "attributes->>'phoneNumber'"));

    private static final Map<String, String> GROUP_COLUMNS =
            Map.of(
                    "id", "id",
                    "displayName", "display_name",
                    "externalId", "external_id",
                    "meta.version", "version");

    private IamFilterSql() {}

    static Sql user(ScimFilter filter) {
        if (filter == null) {
            return new Sql("TRUE", List.of());
        }
        return translate(filter, USER_COLUMNS, "iam_user", true);
    }

    static Sql group(ScimFilter filter) {
        if (filter == null) {
            return new Sql("TRUE", List.of());
        }
        return translate(filter, GROUP_COLUMNS, "iam_group", false);
    }

    private static Sql translate(
            ScimFilter filter, Map<String, String> columns, String table, boolean userTable) {
        if (filter instanceof ScimFilter.And and) {
            return combine(and.filters(), columns, table, userTable, "AND");
        }
        if (filter instanceof ScimFilter.Or or) {
            return combine(or.filters(), columns, table, userTable, "OR");
        }
        if (filter instanceof ScimFilter.Not not) {
            Sql inner = translate(not.filter(), columns, table, userTable);
            return new Sql("NOT (" + inner.where() + ")", inner.arguments());
        }
        if (filter instanceof ScimFilter.Present present) {
            String attribute = present.attribute();
            if (userTable || !"members.value".equals(attribute)) {
                String column = column(columns, attribute);
                return new Sql(column + " IS NOT NULL", List.of());
            }
            return new Sql(
                    "EXISTS (SELECT 1 FROM iam_group_member gm WHERE gm.group_id = "
                            + table
                            + ".id)",
                    List.of());
        }
        if (filter instanceof ScimFilter.Compare compare) {
            return comparison(compare, columns, table, userTable);
        }
        throw invalid("unsupported filter node");
    }

    private static Sql combine(
            List<ScimFilter> filters,
            Map<String, String> columns,
            String table,
            boolean userTable,
            String joiner) {
        List<String> parts = new ArrayList<>();
        List<Object> arguments = new ArrayList<>();
        for (ScimFilter filter : filters) {
            Sql sql = translate(filter, columns, table, userTable);
            parts.add("(" + sql.where() + ")");
            arguments.addAll(sql.arguments());
        }
        return new Sql(String.join(" " + joiner + " ", parts), arguments);
    }

    private static Sql comparison(
            ScimFilter.Compare compare,
            Map<String, String> columns,
            String table,
            boolean userTable) {
        String attribute = compare.attribute();
        if (!userTable && "members.value".equals(attribute)) {
            String exists =
                    "EXISTS (SELECT 1 FROM iam_group_member gm WHERE gm.group_id = "
                            + table
                            + ".id AND gm.user_id = ?)";
            return switch (compare.operator()) {
                case EQ -> new Sql(exists, List.of(compare.value()));
                case NE -> new Sql("NOT " + exists, List.of(compare.value()));
                default -> throw invalid("unsupported operator for members.value");
            };
        }
        String column = column(columns, attribute);
        Object value = normalize(attribute, compare.operator(), compare.value());
        String sql =
                switch (compare.operator()) {
                    case EQ -> column + " = ?";
                    case NE -> column + " IS DISTINCT FROM ?";
                    case CO -> column + " ILIKE '%' || ? || '%'";
                    case SW -> column + " ILIKE ? || '%'";
                    case EW -> column + " ILIKE '%' || ?";
                    case GT -> column + " > ?";
                    case LT -> column + " < ?";
                    case GE -> column + " >= ?";
                    case LE -> column + " <= ?";
                };
        return new Sql(sql, List.of(value));
    }

    private static String column(Map<String, String> columns, String attribute) {
        String column = columns.get(attribute);
        if (column == null) {
            throw invalid("unsupported attribute '" + attribute + "'");
        }
        return column;
    }

    private static Object normalize(
            String attribute, ScimComparisonOperator operator, Object value) {
        // Only the login identifier has a normalized database column. Other
        // string comparisons use case-insensitive SQL operators instead.
        if ("userName".equals(attribute)
                && value instanceof String text
                && (operator == ScimComparisonOperator.EQ
                        || operator == ScimComparisonOperator.NE)) {
            return text.toLowerCase(Locale.ROOT);
        }
        return value;
    }

    private static IamException invalid(String message) {
        return new IamException(
                IamErrorCode.SCIM_FILTER_INVALID, "invalid SCIM filter: " + message);
    }
}
