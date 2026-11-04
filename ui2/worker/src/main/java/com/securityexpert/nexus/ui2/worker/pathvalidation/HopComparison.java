package com.securityexpert.nexus.ui2.worker.pathvalidation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.securityexpert.nexus.ui2.jobs.pathvalidation.HopPlan;
import com.securityexpert.nexus.ui2.jobs.pathvalidation.PathValidation.Check;
import com.securityexpert.nexus.ui2.jobs.pathvalidation.PathValidation.Result;

/**
 * Local comparison of one parsed matcher answer with the engine's expectation for the hop (AGENTS.md sensitive identity
 * reporting law: compare locally, report the relationship, not the values). Names and addresses are compared by exact
 * string equality only (identity law); an answer that does not establish the compared fact is {@code NOT_EVALUABLE},
 * never a MISMATCH. The outcome is an enum and a reason code; neither carries a value from the answer.
 */
final class HopComparison {

    /** One check's stored outcome: enum values and a reason code only. */
    record Outcome(Check check, Result result, String reason) {
    }

    /** PAN default rules the security-policy-match answer may not report (an empty answer is then not a mismatch). */
    static final Set<String> PAN_DEFAULT_RULES = Set.of("intrazone-default", "interzone-default");

    private HopComparison() {
    }

    static Outcome cpRoute(HopPlan.Expect expect, Optional<MatcherAnswers.RouteAnswer> parsed) {
        if (parsed.isEmpty()) {
            return new Outcome(Check.CP_ROUTE, Result.NOT_EVALUABLE, "ANSWER_NOT_RECOGNISED");
        }
        MatcherAnswers.RouteAnswer answer = parsed.get();
        String kind = expect.routeKind();
        if (kind == null) {
            return new Outcome(Check.CP_ROUTE, Result.NOT_EVALUABLE, "ENGINE_ROUTE_UNKNOWN");
        }
        if (answer.kind() == MatcherAnswers.RouteKind.LOCAL) {
            return new Outcome(Check.CP_ROUTE, Result.NOT_EVALUABLE, "DESTINATION_IS_LOCAL");
        }
        Outcome outcome = switch (kind) {
            case "NO_ROUTE" -> answer.kind() == MatcherAnswers.RouteKind.NO_ROUTE ? match(Check.CP_ROUTE, "NO_ROUTE_BOTH")
                    : mismatch(Check.CP_ROUTE, "KERNEL_HAS_ROUTE");
            case "DISCARD" -> answer.kind() == MatcherAnswers.RouteKind.DISCARD ? match(Check.CP_ROUTE, "DISCARD_BOTH")
                    : mismatch(Check.CP_ROUTE, "KERNEL_NOT_DISCARD");
            case "IP", "CONNECTED" -> forwarding(Check.CP_ROUTE, expect, answer.kind() == MatcherAnswers.RouteKind.UNICAST,
                    answer.kind() == MatcherAnswers.RouteKind.DISCARD, answer.device(), answer.via());
            default -> new Outcome(Check.CP_ROUTE, Result.NOT_EVALUABLE, "ENGINE_ROUTE_KIND_NOT_COMPARABLE");
        };
        return ecmpGuard(expect, outcome);
    }

    static Outcome panFib(HopPlan.Expect expect, Optional<MatcherAnswers.FibAnswer> parsed) {
        if (parsed.isEmpty()) {
            return new Outcome(Check.PAN_FIB, Result.NOT_EVALUABLE, "ANSWER_NOT_RECOGNISED");
        }
        MatcherAnswers.FibAnswer answer = parsed.get();
        String kind = expect.routeKind();
        if (kind == null) {
            return new Outcome(Check.PAN_FIB, Result.NOT_EVALUABLE, "ENGINE_ROUTE_UNKNOWN");
        }
        Outcome outcome = switch (kind) {
            case "DISCARD" -> answer.discard() ? match(Check.PAN_FIB, "DISCARD_BOTH") : mismatch(Check.PAN_FIB, "FIB_NOT_DISCARD");
            case "IP", "CONNECTED" -> forwarding(Check.PAN_FIB, expect, !answer.discard(), answer.discard(),
                    Optional.of(answer.egressInterface()).filter(s -> !s.isEmpty()), answer.nextHop());
            // A missing FIB answer shape for "no route" is not established: never compared.
            default -> new Outcome(Check.PAN_FIB, Result.NOT_EVALUABLE, "ENGINE_ROUTE_KIND_NOT_COMPARABLE");
        };
        return ecmpGuard(expect, outcome);
    }

    /** Egress interface first, then the next hop when both sides name one. */
    private static Outcome forwarding(Check check, HopPlan.Expect expect, boolean forwards, boolean discards,
            Optional<String> device, Optional<String> via) {
        if (discards) {
            return mismatch(check, "DEVICE_DISCARDS");
        }
        if (!forwards || device.isEmpty()) {
            return new Outcome(check, Result.NOT_EVALUABLE, "ANSWER_NOT_RECOGNISED");
        }
        if (expect.egressInterface() == null) {
            return new Outcome(check, Result.NOT_EVALUABLE, "ENGINE_INTERFACE_UNKNOWN");
        }
        if (!expect.egressInterface().equals(device.get())) {
            return mismatch(check, "EGRESS_INTERFACE_DIFFERS");
        }
        if ("CONNECTED".equals(expect.routeKind())) {
            return via.isPresent() ? mismatch(check, "DEVICE_ROUTES_VIA_GATEWAY") : match(check, "CONNECTED_SAME_INTERFACE");
        }
        if (expect.nextHop() == null || via.isEmpty()) {
            return match(check, "SAME_INTERFACE_NEXT_HOP_NOT_COMPARED");
        }
        return expect.nextHop().equals(via.get()) ? match(check, "SAME_INTERFACE_AND_NEXT_HOP")
                : mismatch(check, "NEXT_HOP_DIFFERS");
    }

    /** One ECMP branch against the device's one hashed choice: a difference is expected, not a mismatch. */
    private static Outcome ecmpGuard(HopPlan.Expect expect, Outcome outcome) {
        return expect.ecmp() && outcome.result() == Result.MISMATCH
                ? new Outcome(outcome.check(), Result.NOT_EVALUABLE, "ECMP_DEVICE_CHOICE") : outcome;
    }

    static Outcome panSecurity(HopPlan.Expect expect, Optional<List<String>> parsed) {
        if (parsed.isEmpty()) {
            return new Outcome(Check.PAN_SECURITY, Result.NOT_EVALUABLE, "ANSWER_NOT_RECOGNISED");
        }
        String rule = expect.securityRule();
        if (rule == null) {
            return new Outcome(Check.PAN_SECURITY, Result.NOT_EVALUABLE, "ENGINE_RULE_UNKNOWN");
        }
        if (parsed.get().isEmpty()) {
            return PAN_DEFAULT_RULES.contains(rule)
                    ? new Outcome(Check.PAN_SECURITY, Result.NOT_EVALUABLE, "DEFAULT_RULE_NOT_REPORTED")
                    : mismatch(Check.PAN_SECURITY, "DEVICE_MATCHED_NO_RULE");
        }
        return rule.equals(parsed.get().get(0)) ? match(Check.PAN_SECURITY, "SAME_FIRST_MATCH_RULE")
                : mismatch(Check.PAN_SECURITY, "FIRST_MATCH_RULE_DIFFERS");
    }

    static Outcome panNat(HopPlan.Expect expect, Optional<List<String>> parsed) {
        if (parsed.isEmpty()) {
            return new Outcome(Check.PAN_NAT, Result.NOT_EVALUABLE, "ANSWER_NOT_RECOGNISED");
        }
        String status = expect.natStatus();
        if (!"TRANSLATED".equals(status) && !"NO_TRANSLATION".equals(status)) {
            return new Outcome(Check.PAN_NAT, Result.NOT_EVALUABLE, "ENGINE_NAT_UNKNOWN");
        }
        List<String> rules = parsed.get();
        if (expect.natRule() == null) {
            return rules.isEmpty() ? match(Check.PAN_NAT, "NO_NAT_RULE_BOTH") : mismatch(Check.PAN_NAT, "DEVICE_MATCHED_NAT_RULE");
        }
        if (rules.isEmpty()) {
            return mismatch(Check.PAN_NAT, "DEVICE_MATCHED_NO_NAT_RULE");
        }
        return expect.natRule().equals(rules.get(0)) ? match(Check.PAN_NAT, "SAME_NAT_RULE")
                : mismatch(Check.PAN_NAT, "NAT_RULE_DIFFERS");
    }

    /** Hop result: any MISMATCH, else MATCH when at least one check matched, else NOT_EVALUABLE. */
    static Result hop(List<Outcome> outcomes) {
        if (outcomes.stream().anyMatch(o -> o.result() == Result.MISMATCH)) {
            return Result.MISMATCH;
        }
        return outcomes.stream().anyMatch(o -> o.result() == Result.MATCH) ? Result.MATCH : Result.NOT_EVALUABLE;
    }

    /** The hop's reason codes: every non-matching check's reason, in check order, without repeats. */
    static List<String> reasons(List<Outcome> outcomes) {
        Set<String> out = new LinkedHashSet<>();
        for (Outcome o : outcomes) {
            if (o.result() != Result.MATCH) {
                out.add(o.reason());
            }
        }
        return new ArrayList<>(out);
    }

    private static Outcome match(Check check, String reason) {
        return new Outcome(check, Result.MATCH, reason);
    }

    private static Outcome mismatch(Check check, String reason) {
        return new Outcome(check, Result.MISMATCH, reason);
    }
}
