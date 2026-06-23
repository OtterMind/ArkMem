package io.arkmem.memory;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class PostgresMemoryFilterSqlBuilder {

    private static final String NUMERIC_PATTERN = "'^[+-]?([0-9]+([.][0-9]*)?|[.][0-9]+)$'";
    private static final Set<String> SCOPE_COLUMNS = Set.of("user_id", "agent_id", "run_id");

    private PostgresMemoryFilterSqlBuilder() {
    }

    static SqlFilter build(MemoryFilter filter, boolean onlyActive) {
        SqlContext context = new SqlContext();
        List<String> predicates = new ArrayList<>();
        if (onlyActive) {
            predicates.add("deleted_at is null");
        }

        String metadataPredicate = toSql(MemoryFilterExpression.from(filter), context);
        if (!metadataPredicate.isBlank()) {
            predicates.add(metadataPredicate);
        }

        String whereClause = predicates.isEmpty() ? "where true" : "where " + String.join(" and ", predicates);
        return new SqlFilter(whereClause, context.parameters);
    }

    private static String toSql(MemoryFilterExpression.Node node, SqlContext context) {
        if (node instanceof MemoryFilterExpression.EmptyNode) {
            return "";
        }
        if (node instanceof MemoryFilterExpression.ConditionNode condition) {
            return conditionToSql(condition, context);
        }
        if (node instanceof MemoryFilterExpression.GroupNode group) {
            List<String> childSql = group.children().stream()
                    .map(child -> toSql(child, context))
                    .filter(sql -> !sql.isBlank())
                    .toList();
            if (childSql.isEmpty()) {
                return "";
            }
            if (childSql.size() == 1) {
                return childSql.get(0);
            }
            String operator = group.operator() == MemoryFilterExpression.LogicalOperator.OR ? " or " : " and ";
            return "(" + String.join(operator, childSql) + ")";
        }
        if (node instanceof MemoryFilterExpression.NotNode notNode) {
            String childSql = toSql(notNode.child(), context);
            return childSql.isBlank() ? "" : "(not " + childSql + ")";
        }
        throw new BadRequestException("Unsupported metadata filter expression");
    }

    @SuppressWarnings("unchecked")
    private static String conditionToSql(MemoryFilterExpression.ConditionNode condition, SqlContext context) {
        String scopeField = scopeField(condition.key());
        if (scopeField != null) {
            return conditionToSql(condition, context, scopeField, scopeField + " is not null");
        }

        String keyParameter = context.next("filterKey");
        context.parameters.addValue(keyParameter, condition.key());
        String field = "metadata ->> :" + keyParameter;
        return conditionToSql(condition, context, field, "jsonb_exists(metadata, :" + keyParameter + ")");
    }

    @SuppressWarnings("unchecked")
    private static String conditionToSql(
            MemoryFilterExpression.ConditionNode condition,
            SqlContext context,
            String field,
            String existsPredicate
    ) {
        return switch (condition.operator()) {
            case EXISTS -> existsPredicate;
            case EQ -> {
                String valueParameter = context.next("filterValue");
                context.parameters.addValue(valueParameter, condition.value());
                yield field + " = :" + valueParameter;
            }
            case NE -> {
                String valueParameter = context.next("filterValue");
                context.parameters.addValue(valueParameter, condition.value());
                yield "(not " + existsPredicate + " or " + field + " is null or " + field + " <> :" + valueParameter + ")";
            }
            case IN -> {
                String valuesParameter = context.next("filterValues");
                context.parameters.addValue(valuesParameter, condition.value());
                yield field + " in (:" + valuesParameter + ")";
            }
            case NIN -> {
                String valuesParameter = context.next("filterValues");
                context.parameters.addValue(valuesParameter, condition.value());
                yield "(not " + existsPredicate + " or " + field + " is null or " + field + " not in (:" + valuesParameter + "))";
            }
            case GT, GTE, LT, LTE -> {
                String valueParameter = context.next("filterNumber");
                context.parameters.addValue(valueParameter, condition.value());
                yield numericCondition(field, condition.operator(), valueParameter);
            }
            case CONTAINS, ICONTAINS -> {
                String valueParameter = context.next("filterPattern");
                context.parameters.addValue(valueParameter, likePattern(String.valueOf(condition.value())));
                String left = condition.operator() == MemoryFilterExpression.Operator.ICONTAINS ? "lower(" + field + ")" : field;
                String right = condition.operator() == MemoryFilterExpression.Operator.ICONTAINS ? "lower(:" + valueParameter + ")" : ":" + valueParameter;
                yield "(" + left + " like " + right + " escape '!')";
            }
        };
    }

    private static String scopeField(String key) {
        return SCOPE_COLUMNS.contains(key) ? key : null;
    }

    private static String numericCondition(
            String field,
            MemoryFilterExpression.Operator operator,
            String valueParameter
    ) {
        String comparison = switch (operator) {
            case GT -> ">";
            case GTE -> ">=";
            case LT -> "<";
            case LTE -> "<=";
            default -> throw new BadRequestException("Unsupported numeric metadata filter operator");
        };
        return "(" + field + " ~ " + NUMERIC_PATTERN + " and cast(" + field + " as numeric) " + comparison + " :" + valueParameter + ")";
    }

    private static String likePattern(String value) {
        String escaped = value
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }

    record SqlFilter(String whereClause, MapSqlParameterSource parameters) {
    }

    private static final class SqlContext {

        private final MapSqlParameterSource parameters = new MapSqlParameterSource();
        private int index;

        private String next(String prefix) {
            return prefix + index++;
        }
    }
}
