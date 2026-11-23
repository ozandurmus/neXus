package com.securityexpert.nexus.ui2.service.policy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Cell;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.PolicyObject;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Rule;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Section;

/**
 * Compares the policy content of the two members of a Cisco ASA or FortiGate HA pair (PO decision record 2026-11-14
 * ASA/FortiGate policy model, decision 6; ASA_FORTIGATE_POLICY_DESIGN_2026_11_14.md section 5.1): per pair and per
 * context (ASA {@code single}, FortiGate one per VDOM) the outcome is {@code SAME}, {@code HA_POLICY_DIVERGENT}, or
 * {@code UNKNOWN} with a reason. A difference is reported, never merged; this class changes no count anywhere.
 *
 * <h2>Order of the checks (fail closed)</h2>
 * <ol>
 *   <li>The pair's recorded serial identity ({@code ha_pair_identity}) is not MATCH: every context is UNKNOWN
 *       {@code PAIR_IDENTITY_NOT_MATCH}; the content is not compared at all (no equality inferred from hostnames or
 *       ordinal suffixes).</li>
 *   <li>A member has no stored snapshot at all: UNKNOWN {@code MEMBER_POLICY_MISSING}.</li>
 *   <li>A context exists on one member only (FortiGate VDOM sets differ): that context is UNKNOWN
 *       {@code CONTEXT_SET_DIFFERS}. Contexts present on both members are still compared.</li>
 *   <li>Either member's snapshot of the context is not rule-complete ({@link PolicySnapshot#ruleComplete()}: any
 *       failure other than an advisory hit/inventory one, e.g. an unsupported mode or unproven FortiOS defaults):
 *       UNKNOWN {@code SNAPSHOT_HAS_COLLECTION_FAILURES}.</li>
 *   <li>Otherwise the two canonical hashes are compared: equal is {@code SAME}, different is
 *       {@code HA_POLICY_DIVERGENT}.</li>
 * </ol>
 *
 * <h2>Canonical policy-relevant serialisation ({@code HAPOL1})</h2>
 * SHA-256 over a sequence of tokens. Every token is written as {@code <UTF-8 byte length>:<bytes>}; a null is the
 * single byte {@code ~}; structure markers are tokens too, so no two different inputs share a serialisation.
 * <ul>
 *   <li>Header: {@code HAPOL1}, metadata {@code vendor}, {@code name} (the mapper's policy kind) and
 *       {@code containerName} (the context).</li>
 *   <li>Sections in stored order: {@code S}, section {@code name}, {@code source}, the parent rule as the position
 *       {@code <section index>/<rule index>} of the rule it names (or {@code ~} when absent, {@code UNRESOLVED} when the
 *       id names no rule in this snapshot), the rule count.</li>
 *   <li>Rules in stored order within their section: {@code R}, the index in the section, {@code number} (the
 *       position), {@code name}, {@code uuid} (FortiGate policy uuid; part of the synchronised configuration),
 *       {@code enabled}, {@code action}, {@code log}, {@code comment}, then the cells {@code source},
 *       {@code destination}, {@code service}, {@code application} (each: the negated flag, the ref count, each ref
 *       resolved as below), then every extras key in sorted order with its values resolved as below (this carries
 *       the from/to placement, users, schedule, ACL name and direction, NAT translations and every other mapped
 *       criterion).</li>
 *   <li>A ref or extras value that names an object of the snapshot is replaced by that object's content digest:
 *       SHA-256 over {@code O}, object {@code name}, {@code type}, {@code status}, the {@code schedule}
 *       ({@code String.valueOf} of the record), the {@code values} in stored order, and the {@code members} in stored
 *       order, each member resolved the same way (recursively; a reference cycle is the token {@code CYCLE} plus the
 *       object name). Any other string is taken literally, behind the token {@code L}.</li>
 * </ul>
 * Excluded, because they are per member or per read: snapshot, section, rule and object ids (they are derived from the
 * device id), {@code sourceId}, {@code sourceName}, {@code containerId}, {@code collectedAt}, {@code artefactRef},
 * targets (device ids, names, contexts, sync state), inventory provenance, rule hit counters, and collection failures
 * (they gate the comparison instead). Objects no rule or extras value references are not part of the hash. Names are
 * compared exactly as the parser produced them: no case folding, no trimming (identity law).
 *
 * <p>Inclusions chosen under doubt (a false DIVERGENT is visible, a false SAME hides a difference): rule name,
 * comment, log setting, FortiGate policy uuid, every extras key, object status and schedule, and the order of values
 * and members as stored.</p>
 */
public final class HaPolicyPairing {
    private HaPolicyPairing() {}

    public enum Outcome { SAME, HA_POLICY_DIVERGENT, UNKNOWN }

    public enum Reason { MEMBER_POLICY_MISSING, PAIR_IDENTITY_NOT_MATCH, SNAPSHOT_HAS_COLLECTION_FAILURES, CONTEXT_SET_DIFFERS }

    /** The snapshot vendors this comparison covers (metadata vendor of the ASA and FortiGate mappers). */
    public static final Set<String> VENDORS = Set.of("ASA", "FGT");

    /**
     * One HA pair: an opaque reference, the snapshot vendor ({@code ASA} or {@code FGT}), the two member device ids,
     * and whether the recorded pair identity is MATCH.
     */
    public record Pair(String pairRef, String vendor, String memberA, String memberB, boolean identityMatch) {
        public Pair {
            Objects.requireNonNull(pairRef, "pairRef");
            if (!VENDORS.contains(vendor)) throw new IllegalArgumentException("HA_POLICY_PAIR_VENDOR_UNSUPPORTED");
            Objects.requireNonNull(memberA, "memberA");
            Objects.requireNonNull(memberB, "memberB");
            if (memberA.equals(memberB)) throw new IllegalArgumentException("HA_POLICY_PAIR_MEMBERS_IDENTICAL");
        }
    }

    /**
     * One context of one pair. {@code context} is null only when neither member has any snapshot to name one.
     * {@code reason} is null unless the outcome is UNKNOWN. The collection times are the newer and older of the
     * member snapshots of this context (the older is null when only one member has it).
     */
    public record Result(String pairRef, String vendor, String context, Outcome outcome, Reason reason,
            String memberA, String memberB, String newerCollectedAt, String olderCollectedAt) {}

    /** Evaluates every pair against the stored snapshots; the rows are ordered by pair (input order), then context. */
    public static List<Result> evaluate(List<Pair> pairs, List<PolicySnapshot> snapshots) {
        Map<String, Map<String, PolicySnapshot>> byMember = new HashMap<>();
        for (PolicySnapshot snapshot : snapshots) {
            var metadata = snapshot.metadata();
            if (metadata == null || !VENDORS.contains(metadata.vendor()) || metadata.sourceId() == null) continue;
            byMember.computeIfAbsent(metadata.vendor() + "|" + metadata.sourceId(), k -> new LinkedHashMap<>())
                    .putIfAbsent(metadata.containerName(), snapshot);
        }
        List<Result> results = new ArrayList<>();
        for (Pair pair : pairs) {
            Map<String, PolicySnapshot> a = byMember.getOrDefault(pair.vendor() + "|" + pair.memberA(), Map.of());
            Map<String, PolicySnapshot> b = byMember.getOrDefault(pair.vendor() + "|" + pair.memberB(), Map.of());
            TreeSet<String> contexts = new TreeSet<>(java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()));
            contexts.addAll(a.keySet());
            contexts.addAll(b.keySet());
            if (contexts.isEmpty()) contexts.add(null);
            for (String context : contexts) {
                PolicySnapshot left = context == null ? null : a.get(context);
                PolicySnapshot right = context == null ? null : b.get(context);
                Outcome outcome;
                Reason reason = null;
                if (!pair.identityMatch()) reason = Reason.PAIR_IDENTITY_NOT_MATCH;
                else if (a.isEmpty() || b.isEmpty()) reason = Reason.MEMBER_POLICY_MISSING;
                else if (left == null || right == null) reason = Reason.CONTEXT_SET_DIFFERS;
                else if (!left.ruleComplete() || !right.ruleComplete()) reason = Reason.SNAPSHOT_HAS_COLLECTION_FAILURES;
                if (reason != null) outcome = Outcome.UNKNOWN;
                else outcome = canonicalHash(left).equals(canonicalHash(right)) ? Outcome.SAME : Outcome.HA_POLICY_DIVERGENT;
                String[] times = newerOlder(left, right);
                results.add(new Result(pair.pairRef(), pair.vendor(), context, outcome, reason, pair.memberA(), pair.memberB(),
                        times[0], times[1]));
            }
        }
        return results;
    }

    /** Newer first, compared as parsed instants; an unparseable time keeps the member order. */
    private static String[] newerOlder(PolicySnapshot left, PolicySnapshot right) {
        String l = left == null ? null : left.metadata().collectedAt();
        String r = right == null ? null : right.metadata().collectedAt();
        if (l == null) return new String[] {r, null};
        if (r == null) return new String[] {l, null};
        try {
            return java.time.Instant.parse(l).isBefore(java.time.Instant.parse(r)) ? new String[] {r, l} : new String[] {l, r};
        } catch (RuntimeException unparseable) {
            return new String[] {l, r};
        }
    }

    /** The {@code HAPOL1} canonical policy-relevant hash of one snapshot, lower-case hex (see the class javadoc). */
    public static String canonicalHash(PolicySnapshot snapshot) {
        return new Canonicaliser(snapshot).hash();
    }

    private static final class Canonicaliser {
        private final PolicySnapshot snapshot;
        private final MessageDigest digest = sha256();
        private final Map<String, String> objectDigests = new HashMap<>();
        private final Set<String> inProgress = new HashSet<>();
        private final Map<String, String> rulePositions = new HashMap<>();

        Canonicaliser(PolicySnapshot snapshot) {
            this.snapshot = snapshot;
            List<Section> sections = snapshot.sections();
            for (int s = 0; s < sections.size(); s++) {
                List<Rule> rules = sections.get(s).rules();
                for (int r = 0; r < rules.size(); r++) rulePositions.putIfAbsent(rules.get(r).id(), s + "/" + r);
            }
        }

        String hash() {
            var metadata = snapshot.metadata();
            token(digest, "HAPOL1");
            token(digest, metadata == null ? null : metadata.vendor());
            token(digest, metadata == null ? null : metadata.name());
            token(digest, metadata == null ? null : metadata.containerName());
            for (Section section : snapshot.sections()) {
                token(digest, "S");
                token(digest, section.name());
                token(digest, section.source());
                token(digest, section.parentRuleId() == null ? null : rulePositions.getOrDefault(section.parentRuleId(), "UNRESOLVED"));
                token(digest, Integer.toString(section.rules().size()));
                List<Rule> rules = section.rules();
                for (int index = 0; index < rules.size(); index++) rule(index, rules.get(index));
            }
            token(digest, "END");
            return HexFormat.of().formatHex(digest.digest());
        }

        private void rule(int index, Rule rule) {
            token(digest, "R");
            token(digest, Integer.toString(index));
            token(digest, Integer.toString(rule.number()));
            token(digest, rule.name());
            token(digest, rule.uuid());
            token(digest, rule.enabled() == null ? null : rule.enabled().toString());
            token(digest, rule.action());
            token(digest, rule.log());
            token(digest, rule.comment());
            cell("source", rule.source());
            cell("destination", rule.destination());
            cell("service", rule.service());
            cell("application", rule.application());
            token(digest, "X");
            token(digest, Integer.toString(rule.extras().size()));
            // Rule extras are a sorted map already (PolicySnapshot.Rule); iterate in that fixed order.
            for (var entry : rule.extras().entrySet()) {
                token(digest, entry.getKey());
                token(digest, Integer.toString(entry.getValue().size()));
                for (String value : entry.getValue()) token(digest, resolve(value));
            }
        }

        private void cell(String label, Cell cell) {
            token(digest, "C");
            token(digest, label);
            if (cell == null) { token(digest, null); return; }
            token(digest, Boolean.toString(cell.negated()));
            token(digest, Integer.toString(cell.refs().size()));
            for (String ref : cell.refs()) token(digest, resolve(ref));
        }

        /** An object reference becomes the object's content digest; anything else stays a literal. */
        private String resolve(String value) {
            if (value == null) return null;
            PolicyObject object = snapshot.objects().get(value);
            if (object == null) return "L" + value;
            String cached = objectDigests.get(value);
            if (cached != null) return cached;
            if (!inProgress.add(value)) return "CYCLE" + object.name();
            MessageDigest inner = sha256();
            token(inner, "O");
            token(inner, object.name());
            token(inner, object.type());
            token(inner, object.status());
            token(inner, object.schedule() == null ? null : String.valueOf(object.schedule()));
            token(inner, Integer.toString(object.values().size()));
            for (String v : object.values()) token(inner, v);
            token(inner, Integer.toString(object.members().size()));
            for (String member : object.members()) token(inner, resolve(member));
            inProgress.remove(value);
            String result = "D" + HexFormat.of().formatHex(inner.digest());
            objectDigests.put(value, result);
            return result;
        }
    }

    private static void token(MessageDigest digest, String text) {
        if (text == null) { digest.update((byte) '~'); return; }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
