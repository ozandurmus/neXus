package com.securityexpert.nexus.ui2.worker.compliance.model;

import java.util.List;

public record AssertionRule(
        String op, // present, absent, equals, not_equals, matches, not_match, any_match, none_match, gte, lte, in, not_in, count_gte, count_lte
        String targetString,
        Long targetNumber,
        List<String> targetList
) {
    public static AssertionRule present() {
        return new AssertionRule("present", null, null, null);
    }

    public static AssertionRule absent() {
        return new AssertionRule("absent", null, null, null);
    }

    public static AssertionRule equalsStr(String value) {
        return new AssertionRule("equals", value, null, null);
    }

    public static AssertionRule notEqualsStr(String value) {
        return new AssertionRule("not_equals", value, null, null);
    }

    public static AssertionRule matches(String regex) {
        return new AssertionRule("matches", regex, null, null);
    }

    public static AssertionRule noneMatch(String regex) {
        return new AssertionRule("none_match", regex, null, null);
    }

    public static AssertionRule gte(long number) {
        return new AssertionRule("gte", null, number, null);
    }

    public static AssertionRule lte(long number) {
        return new AssertionRule("lte", null, number, null);
    }

    public static AssertionRule count_gte(long number) {
        return new AssertionRule("count_gte", null, number, null);
    }

    public static AssertionRule count_lte(long number) {
        return new AssertionRule("count_lte", null, number, null);
    }

    public static AssertionRule countGte(long number) {
        return count_gte(number);
    }

    public static AssertionRule countLte(long number) {
        return count_lte(number);
    }

    public static AssertionRule inList(List<String> values) {
        return new AssertionRule("in", null, null, values);
    }
}
