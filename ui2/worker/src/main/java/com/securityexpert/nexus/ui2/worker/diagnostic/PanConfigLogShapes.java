package com.securityexpert.nexus.ui2.worker.diagnostic;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Counts-only shape telemetry of the config-log entries one run read (PO-session decision 2026-11-13 after the first Panorama
 * measurement): which <i>kind</i> of path the change entries carry, and which result values the commit entries carry. It
 * answers the two UNKNOWNs the first run left open (design section 8 items 2 and 3) without storing or printing a name, a
 * path, an administrator or a rule: every key below is a fixed word of this class or a regex-checked command or result class,
 * every value a count. Nothing is stored; the numbers go to the job transcript note only.
 */
final class PanConfigLogShapes {
    /** Path kinds of a change entry (see {@link PanRuleRef#shape}). */
    static final List<String> PATH_KINDS = List.of(PanRuleRef.RULE_PATH, PanRuleRef.SHARED_RULE_PATH, PanRuleRef.RULE_FIELD_PATH,
            PanRuleRef.OTHER_OBJECT, PanRuleRef.UNMATCHED);
    private static final Set<String> COMMIT_COMMANDS = Set.of("commit", "commit-all");
    private static final Set<String> COMPLETION_RESULTS = Set.of("Succeeded", "Failed");
    private static final Pattern RESULT_CLASS = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,31}");

    /**
     * @param paths change-class entries by path kind (every kind present, zero included)
     * @param commits commit and commit-all entries by {@code cmd/result}; a result that is not a plain word is {@code other}
     * @param submittedWithCompletion Submitted commit entries for which a later Succeeded or Failed commit entry of the same
     *        administrator is in the same read
     * @param submittedWithoutCompletion the other Submitted commit entries (a completion may be in a later read)
     */
    record Counts(Map<String, Integer> paths, Map<String, Integer> commits, int submittedWithCompletion, int submittedWithoutCompletion) {
        /** Fixed words and numbers only. */
        String note() {
            return "shapes paths=" + paths + " commits=" + commits + " submittedWithCompletion=" + submittedWithCompletion
                    + " submittedWithoutCompletion=" + submittedWithoutCompletion;
        }
        static final Counts NONE = new Counts(Map.of(), Map.of(), 0, 0);
    }

    private PanConfigLogShapes() {}

    static Counts of(List<PanLogPage.Entry> entries) {
        Map<String, Integer> paths = new TreeMap<>();
        for (String kind : PATH_KINDS) paths.put(kind, 0);
        Map<String, Integer> commits = new TreeMap<>();
        record Commit(BigInteger seqno, String time, String admin, String result) {}
        List<Commit> seen = new java.util.ArrayList<>();
        for (PanLogPage.Entry entry : entries) {
            Map<String, String> fields = PanLogRedactor.redact("pan_config", entry.fields()).fields();
            String command = strip(fields.get("cmd")).toLowerCase(java.util.Locale.ROOT);
            String result = strip(fields.get("result"));
            if (PanoramaConfigLogReader.CHANGE_COMMANDS.contains(command)) paths.merge(PanRuleRef.shape(strip(fields.get("path"))), 1, Integer::sum);
            if (COMMIT_COMMANDS.contains(command)) {
                String resultWord = RESULT_CLASS.matcher(result).matches() ? result : "other";
                commits.merge(command + "/" + resultWord, 1, Integer::sum);
                BigInteger seqno;
                try { seqno = new BigInteger(entry.seqno()); } catch (NumberFormatException unreadable) { seqno = BigInteger.ZERO; }
                seen.add(new Commit(seqno, entry.receiveTime(), strip(fields.get("admin")), result));
            }
        }
        int with = 0, without = 0;
        for (Commit submitted : seen) {
            if (!"Submitted".equals(submitted.result())) continue;
            boolean completed = seen.stream().anyMatch(later -> COMPLETION_RESULTS.contains(later.result())
                    && later.admin().equals(submitted.admin())
                    && (later.time().compareTo(submitted.time()) > 0
                        || later.time().equals(submitted.time()) && later.seqno().compareTo(submitted.seqno()) > 0));
            if (completed) with++; else without++;
        }
        return new Counts(paths, commits, with, without);
    }

    private static String strip(String value) { return value == null ? "" : value.strip(); }
}
