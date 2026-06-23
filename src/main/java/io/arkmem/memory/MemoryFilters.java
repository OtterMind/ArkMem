package io.arkmem.memory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntPredicate;

final class MemoryFilters {

    private MemoryFilters() {
    }

    static boolean matches(MemoryRecord record, MemoryFilter filter) {
        if (record == null || record.isDeleted()) {
            return false;
        }
        if (filter == null || filter.isEmpty()) {
            return true;
        }
        return evaluate(MemoryFilterExpression.from(filter), record.getMetadata());
    }

    private static boolean evaluate(MemoryFilterExpression.Node node, Map<String, Object> metadata) {
        if (node instanceof MemoryFilterExpression.EmptyNode) {
            return true;
        }
        if (node instanceof MemoryFilterExpression.ConditionNode condition) {
            return evaluateCondition(condition, metadata);
        }
        if (node instanceof MemoryFilterExpression.GroupNode group) {
            return switch (group.operator()) {
                case AND -> group.children().stream().allMatch(child -> evaluate(child, metadata));
                case OR -> group.children().stream().anyMatch(child -> evaluate(child, metadata));
                case NOT -> throw new BadRequestException("NOT is not a group operator");
            };
        }
        if (node instanceof MemoryFilterExpression.NotNode notNode) {
            return !evaluate(notNode.child(), metadata);
        }
        throw new BadRequestException("Unsupported metadata filter expression");
    }

    @SuppressWarnings("unchecked")
    private static boolean evaluateCondition(
            MemoryFilterExpression.ConditionNode condition,
            Map<String, Object> metadata
    ) {
        boolean exists = metadata.containsKey(condition.key());
        String actual = stringValue(metadata.get(condition.key()));
        return switch (condition.operator()) {
            case EXISTS -> exists;
            case EQ -> exists && Objects.equals(actual, stringValue(condition.value()));
            case NE -> !exists || !Objects.equals(actual, stringValue(condition.value()));
            case IN -> exists && ((List<String>) condition.value()).contains(actual);
            case NIN -> !exists || !((List<String>) condition.value()).contains(actual);
            case CONTAINS -> exists && actual != null && actual.contains(stringValue(condition.value()));
            case ICONTAINS -> exists
                    && actual != null
                    && actual.toLowerCase(Locale.ROOT).contains(stringValue(condition.value()).toLowerCase(Locale.ROOT));
            case GT -> compareNumber(actual, (BigDecimal) condition.value(), comparison -> comparison > 0);
            case GTE -> compareNumber(actual, (BigDecimal) condition.value(), comparison -> comparison >= 0);
            case LT -> compareNumber(actual, (BigDecimal) condition.value(), comparison -> comparison < 0);
            case LTE -> compareNumber(actual, (BigDecimal) condition.value(), comparison -> comparison <= 0);
        };
    }

    private static boolean compareNumber(String actual, BigDecimal expected, IntPredicate predicate) {
        if (actual == null) {
            return false;
        }
        try {
            int comparison = Integer.signum(new BigDecimal(actual).compareTo(expected));
            return predicate.test(comparison);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
