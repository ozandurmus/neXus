package com.securityexpert.nexus.ui2.worker.compliance.engine;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.securityexpert.nexus.ui2.worker.compliance.model.AssertionRule;

public final class ComplianceAssertionEngine {

    private ComplianceAssertionEngine() {
    }

    public static boolean evaluate(String actualValue, AssertionRule rule) {
        if (rule == null) {
            return true;
        }

        String op = rule.op().toLowerCase();
        return switch (op) {
            case "present" -> actualValue != null && !actualValue.isBlank();
            case "absent" -> actualValue == null || actualValue.isBlank();
            case "equals" -> actualValue != null && actualValue.equals(rule.targetString());
            case "not_equals" -> actualValue == null || !actualValue.equals(rule.targetString());
            case "matches" -> {
                if (actualValue == null || rule.targetString() == null) yield false;
                try {
                    yield Pattern.compile(rule.targetString(), Pattern.CASE_INSENSITIVE).matcher(actualValue).find();
                } catch (PatternSyntaxException e) {
                    yield false;
                }
            }
            case "not_match", "none_match" -> {
                if (actualValue == null) yield true;
                if (rule.targetString() == null) yield false;
                try {
                    yield !Pattern.compile(rule.targetString(), Pattern.CASE_INSENSITIVE).matcher(actualValue).find();
                } catch (PatternSyntaxException e) {
                    yield false;
                }
            }
            case "gte" -> {
                if (actualValue == null || rule.targetNumber() == null) yield false;
                Long num = parseNumeric(actualValue);
                yield num != null && num >= rule.targetNumber();
            }
            case "lte" -> {
                if (actualValue == null || rule.targetNumber() == null) yield false;
                Long num = parseNumeric(actualValue);
                yield num != null && num <= rule.targetNumber();
            }
            case "in" -> {
                if (actualValue == null || rule.targetList() == null) yield false;
                yield rule.targetList().contains(actualValue);
            }
            case "not_in" -> {
                if (actualValue == null) yield true;
                if (rule.targetList() == null) yield false;
                yield !rule.targetList().contains(actualValue);
            }
            default -> false;
        };
    }

    public static boolean evaluateList(List<String> values, AssertionRule rule) {
        if (rule == null) return true;
        String op = rule.op().toLowerCase();
        if (values == null) {
            return "absent".equals(op);
        }

        return switch (op) {
            case "present" -> !values.isEmpty();
            case "absent" -> values.isEmpty();
            case "count_gte" -> rule.targetNumber() != null && values.size() >= rule.targetNumber();
            case "count_lte" -> rule.targetNumber() != null && values.size() <= rule.targetNumber();
            case "any_match" -> {
                if (rule.targetString() == null) yield false;
                try {
                    Pattern p = Pattern.compile(rule.targetString(), Pattern.CASE_INSENSITIVE);
                    yield values.stream().anyMatch(v -> p.matcher(v).find());
                } catch (PatternSyntaxException e) {
                    yield false;
                }
            }
            case "none_match" -> {
                if (rule.targetString() == null) yield true;
                try {
                    Pattern p = Pattern.compile(rule.targetString(), Pattern.CASE_INSENSITIVE);
                    yield values.stream().noneMatch(v -> p.matcher(v).find());
                } catch (PatternSyntaxException e) {
                    yield false;
                }
            }
            default -> false;
        };
    }

    private static Long parseNumeric(String text) {
        try {
            // Extract leading or embedded digits
            String digits = text.replaceAll("[^0-9-]", "").trim();
            if (digits.isEmpty()) return null;
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
