package cn.byteforce.seedj.iam;

import java.util.List;
import java.util.Objects;

/**
 * SCIM filter AST. Attribute paths are validated by the SQL translator against a whitelist; this
 * AST never carries SQL.
 */
public sealed interface ScimFilter
        permits ScimFilter.And,
                ScimFilter.Or,
                ScimFilter.Not,
                ScimFilter.Compare,
                ScimFilter.Present {

    record And(List<ScimFilter> filters) implements ScimFilter {
        public And {
            filters = List.copyOf(Objects.requireNonNull(filters, "filters"));
            if (filters.isEmpty()) {
                throw new IllegalArgumentException("and requires at least one filter");
            }
        }
    }

    record Or(List<ScimFilter> filters) implements ScimFilter {
        public Or {
            filters = List.copyOf(Objects.requireNonNull(filters, "filters"));
            if (filters.isEmpty()) {
                throw new IllegalArgumentException("or requires at least one filter");
            }
        }
    }

    record Not(ScimFilter filter) implements ScimFilter {
        public Not {
            Objects.requireNonNull(filter, "filter");
        }
    }

    record Compare(String attribute, ScimComparisonOperator operator, Object value)
            implements ScimFilter {
        public Compare {
            attribute = Objects.requireNonNull(attribute, "attribute");
            operator = Objects.requireNonNull(operator, "operator");
        }
    }

    record Present(String attribute) implements ScimFilter {
        public Present {
            attribute = Objects.requireNonNull(attribute, "attribute");
        }
    }
}
