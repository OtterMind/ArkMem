package io.arkmem.memory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class MemoryFilterExpression {

    private MemoryFilterExpression() {
    }

    static Node from(MemoryFilter filter) {
        if (filter == null || filter.isEmpty()) {
            return EmptyNode.INSTANCE;
        }

        List<Node> nodes = new ArrayList<>();
        addEntityNode(nodes, "user_id", filter.userId());
        addEntityNode(nodes, "agent_id", filter.agentId());
        addEntityNode(nodes, "run_id", filter.runId());
        nodes.add(parseMap(filter.metadata()));
        return and(nodes);
    }

    private static void addEntityNode(List<Node> nodes, String key, String value) {
        if (value != null && !value.isBlank()) {
            nodes.add(new ConditionNode(key, Operator.EQ, value.trim()));
        }
    }

    private static Node parseMap(Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return EmptyNode.INSTANCE;
        }

        List<Node> nodes = new ArrayList<>();
        for (Map.Entry<String, Object> entry : filters.entrySet()) {
            String key = requireKey(entry.getKey());
            Object value = entry.getValue();
            if (isEmptyValue(value)) {
                continue;
            }
            LogicalOperator logicalOperator = LogicalOperator.from(key);
            if (logicalOperator != null) {
                nodes.add(parseLogical(logicalOperator, value));
            } else {
                nodes.add(parseCondition(key, value));
            }
        }
        return and(nodes);
    }

    @SuppressWarnings("unchecked")
    private static Node parseLogical(LogicalOperator operator, Object value) {
        List<Map<String, Object>> maps = new ArrayList<>();
        if (value instanceof Map<?, ?> map) {
            maps.add(copyStringKeyMap(map));
        } else if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (!(item instanceof Map<?, ?> map)) {
                    throw new BadRequestException(operator.name() + " filter requires objects");
                }
                maps.add(copyStringKeyMap(map));
            }
        } else {
            throw new BadRequestException(operator.name() + " filter requires an object or list of objects");
        }

        if (maps.isEmpty()) {
            throw new BadRequestException(operator.name() + " filter cannot be empty");
        }

        List<Node> children = maps.stream()
                .map(MemoryFilterExpression::parseMap)
                .filter(node -> !(node instanceof EmptyNode))
                .toList();
        if (children.isEmpty()) {
            return EmptyNode.INSTANCE;
        }

        return switch (operator) {
            case AND -> and(children);
            case OR -> or(children);
            case NOT -> new NotNode(or(children));
        };
    }

    private static Map<String, Object> copyStringKeyMap(Map<?, ?> source) {
        Map<String, Object> copied = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new BadRequestException("metadata filter keys must be strings");
            }
            copied.put(key, entry.getValue());
        }
        return copied;
    }

    private static Node parseCondition(String key, Object value) {
        if (isWildcard(value)) {
            return new ConditionNode(key, Operator.EXISTS, null);
        }
        if (!(value instanceof Map<?, ?> operatorMap)) {
            return new ConditionNode(key, Operator.EQ, stringValue(value));
        }
        if (operatorMap.isEmpty()) {
            throw new BadRequestException("metadata filter operator cannot be empty");
        }

        List<Node> nodes = new ArrayList<>();
        for (Map.Entry<?, ?> entry : operatorMap.entrySet()) {
            if (!(entry.getKey() instanceof String operatorName)) {
                throw new BadRequestException("metadata filter operator must be a string");
            }
            Operator operator = Operator.from(operatorName);
            nodes.add(new ConditionNode(key, operator, normalizeValue(operator, entry.getValue())));
        }
        return and(nodes);
    }

    private static Object normalizeValue(Operator operator, Object value) {
        return switch (operator) {
            case EXISTS -> null;
            case GT, GTE, LT, LTE -> decimalValue(value);
            case IN, NIN -> stringListValue(value);
            case EQ, NE, CONTAINS, ICONTAINS -> {
                if (value == null) {
                    throw new BadRequestException("metadata filter value cannot be null");
                }
                yield stringValue(value);
            }
        };
    }

    private static BigDecimal decimalValue(Object value) {
        if (value == null) {
            throw new BadRequestException("numeric metadata filter value cannot be null");
        }
        try {
            return new BigDecimal(stringValue(value));
        } catch (NumberFormatException e) {
            throw new BadRequestException("numeric metadata filter value must be a number");
        }
    }

    private static List<String> stringListValue(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            throw new BadRequestException("metadata filter value must be a list");
        }
        if (collection.isEmpty()) {
            throw new BadRequestException("metadata filter list cannot be empty");
        }
        return collection.stream()
                .map(item -> {
                    if (item == null) {
                        throw new BadRequestException("metadata filter list cannot contain null");
                    }
                    return stringValue(item);
                })
                .toList();
    }

    private static Node and(List<Node> nodes) {
        List<Node> filtered = nodes.stream()
                .filter(node -> !(node instanceof EmptyNode))
                .toList();
        if (filtered.isEmpty()) {
            return EmptyNode.INSTANCE;
        }
        if (filtered.size() == 1) {
            return filtered.get(0);
        }
        return new GroupNode(LogicalOperator.AND, filtered);
    }

    private static Node or(List<Node> nodes) {
        List<Node> filtered = nodes.stream()
                .filter(node -> !(node instanceof EmptyNode))
                .toList();
        if (filtered.isEmpty()) {
            return EmptyNode.INSTANCE;
        }
        if (filtered.size() == 1) {
            return filtered.get(0);
        }
        return new GroupNode(LogicalOperator.OR, filtered);
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new BadRequestException("metadata filter key cannot be empty");
        }
        return key;
    }

    private static boolean isEmptyValue(Object value) {
        return value == null || (value instanceof String text && text.isBlank());
    }

    private static boolean isWildcard(Object value) {
        return value instanceof String text && "*".equals(text);
    }

    static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    interface Node {
    }

    enum Operator {
        EQ,
        NE,
        IN,
        NIN,
        GT,
        GTE,
        LT,
        LTE,
        CONTAINS,
        ICONTAINS,
        EXISTS;

        static Operator from(String value) {
            String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("$")) {
                normalized = normalized.substring(1);
            }
            return switch (normalized) {
                case "eq" -> EQ;
                case "ne" -> NE;
                case "in" -> IN;
                case "nin" -> NIN;
                case "gt" -> GT;
                case "gte" -> GTE;
                case "lt" -> LT;
                case "lte" -> LTE;
                case "contains" -> CONTAINS;
                case "icontains" -> ICONTAINS;
                case "exists" -> EXISTS;
                default -> throw new BadRequestException("Unsupported metadata filter operator: " + value);
            };
        }
    }

    enum LogicalOperator {
        AND,
        OR,
        NOT;

        static LogicalOperator from(String value) {
            String normalized = value.strip().toUpperCase(Locale.ROOT);
            if (normalized.startsWith("$")) {
                normalized = normalized.substring(1);
            }
            return switch (normalized) {
                case "AND" -> AND;
                case "OR" -> OR;
                case "NOT" -> NOT;
                default -> null;
            };
        }
    }

    enum EmptyNode implements Node {
        INSTANCE
    }

    record ConditionNode(String key, Operator operator, Object value) implements Node {
    }

    record GroupNode(LogicalOperator operator, List<Node> children) implements Node {
    }

    record NotNode(Node child) implements Node {
    }
}
