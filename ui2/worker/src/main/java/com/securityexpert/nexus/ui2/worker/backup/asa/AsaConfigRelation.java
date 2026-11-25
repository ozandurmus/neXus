package com.securityexpert.nexus.ui2.worker.backup.asa;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The derived relation between the two configuration reads of one ASA backup: {@code more system:running-config} (keys in
 * clear) and {@code show startup-config} (keys masked, its own header lines). Computed in memory while both texts are
 * held; only this result is stored, never a line of either text.
 *
 * <p>Normalisation of both texts: lines beginning with ':' (the ASA's own header and comment lines such as
 * ": Saved", ": Written by ...") and {@code Cryptochecksum} lines are dropped, trailing whitespace is trimmed, blank
 * lines are ignored. The remaining lines are aligned with a Myers diff (see {@link #compare}); the differing lines are also
 * counted by their command keyword (counts only, never a line or a value).</p>
 *
 * <p>Masking assumption (needs confirmation on a real unit): the ASA prints a stored key as the five-character token
 * {@code *****} in {@code show startup-config} (and in {@code show running-config}), while {@code more system:running-config}
 * prints it in clear. Cisco ASA command reference, "more" and "show running-config" / "show startup-config":
 * https://www.cisco.com/c/en/us/td/docs/security/asa/asa-command-reference/I-R/cmdref2/m.html and
 * https://www.cisco.com/c/en/us/td/docs/security/asa/asa-command-reference/S/cmdref3/s1.html (the pages were not fetched
 * by the author; the token is the documented and widely observed form). Encrypted or hashed secrets
 * ({@code ... encrypted}, {@code pbkdf2}) print identically in both reads and need no special case.</p>
 */
public record AsaConfigRelation(Result result, int linesCompared, int linesDiffering, int linesEqualOnlyByMask,
        Map<String, Integer> differingKeywords) {

    public enum Result { MATCH, MISMATCH, NOT_EVALUABLE }

    /** The token an ASA prints in place of a stored key. */
    public static final String MASK = "*****";

    /** Largest edit distance the exact Myers diff is run for; beyond it the order-free multiset difference is used. */
    static final int MAX_EXACT_DISTANCE = 2500;
    /** How many keywords of the differing lines are kept. */
    static final int TOP_KEYWORDS = 10;
    /** Bound on mask-equality checks of one compare, so a pathological pair of texts cannot stall the backup. */
    private static final long MASK_CHECK_BUDGET = 4_000_000L;
    private static final java.util.regex.Pattern KEYWORD = java.util.regex.Pattern.compile("[a-z][a-z-]{0,23}");

    public AsaConfigRelation {
        Objects.requireNonNull(result, "result");
        differingKeywords = differingKeywords == null ? Map.of() : Map.copyOf(differingKeywords);
    }

    public AsaConfigRelation(Result result, int linesCompared, int linesDiffering, int linesEqualOnlyByMask) {
        this(result, linesCompared, linesDiffering, linesEqualOnlyByMask, Map.of());
    }

    public static final AsaConfigRelation NOT_EVALUABLE = new AsaConfigRelation(Result.NOT_EVALUABLE, 0, 0, 0);

    /**
     * Compares the two texts; either null or blank, or one that is not an ASA configuration, is NOT_EVALUABLE.
     * The lines are aligned with a Myers diff (a single inserted or removed line is one difference, not a cascade);
     * lines_differing is the number of inserted plus deleted lines, except that a deleted running line and an inserted
     * startup line of the same change that are equal once the mask is applied count as one equal-by-mask pair.
     */
    public static AsaConfigRelation compare(String running, String startup) {
        List<String> r = normalize(running);
        List<String> s = normalize(startup);
        if (!isAsaConfiguration(r) || !isAsaConfiguration(s)) {
            return NOT_EVALUABLE;
        }
        Edits edits = diff(r, s);
        // group by hunk
        Map<Integer, List<Integer>> deletesByHunk = new java.util.TreeMap<>();
        Map<Integer, List<Integer>> insertsByHunk = new java.util.TreeMap<>();
        for (int[] d : edits.deletes) {
            deletesByHunk.computeIfAbsent(d[0], k -> new ArrayList<>()).add(d[1]);
        }
        for (int[] i : edits.inserts) {
            insertsByHunk.computeIfAbsent(i[0], k -> new ArrayList<>()).add(i[1]);
        }
        List<String> rParents = parentKeywords(r);
        List<String> sParents = parentKeywords(s);
        Map<String, Integer> keywordCounts = new java.util.HashMap<>();
        int differing = 0;
        int maskOnly = 0;
        long budget = MASK_CHECK_BUDGET;
        for (Map.Entry<Integer, List<Integer>> hunk : insertsByHunk.entrySet()) {
            List<Integer> dels = deletesByHunk.getOrDefault(hunk.getKey(), List.of());
            boolean[] used = new boolean[dels.size()];
            // Deleted lines before this index are all paired already: a long run of masked lines is then not
            // rescanned from its start for every line (that made the check budget run out on ~3000 masked lines,
            // reading an otherwise identical pair of texts as MISMATCH purely because of the mask).
            int firstFree = 0;
            for (int j : hunk.getValue()) {
                String startupLine = s.get(j);
                boolean paired = false;
                while (firstFree < used.length && used[firstFree]) {
                    firstFree++;
                }
                if (startupLine.contains(MASK)) {
                    for (int d = firstFree; d < dels.size() && budget > 0; d++, budget--) {
                        if (!used[d] && matchesMasked(startupLine, r.get(dels.get(d)))) {
                            used[d] = true;
                            paired = true;
                            break;
                        }
                    }
                }
                if (paired) {
                    maskOnly++;
                } else {
                    differing++;
                    keywordCounts.merge(sParents.get(j), 1, Integer::sum);
                }
            }
            for (int d = 0; d < dels.size(); d++) {
                if (!used[d]) {
                    differing++;
                    keywordCounts.merge(rParents.get(dels.get(d)), 1, Integer::sum);
                }
            }
            deletesByHunk.remove(hunk.getKey());
        }
        for (List<Integer> dels : deletesByHunk.values()) {
            for (int d : dels) {
                differing++;
                keywordCounts.merge(rParents.get(d), 1, Integer::sum);
            }
        }
        int compared = Math.max(r.size(), s.size());
        return new AsaConfigRelation(differing == 0 ? Result.MATCH : Result.MISMATCH, compared, differing, maskOnly,
                topKeywords(keywordCounts));
    }

    private static Map<String, Integer> topKeywords(Map<String, Integer> counts) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((x, y) -> y.getValue() != x.getValue().intValue() ? Integer.compare(y.getValue(), x.getValue())
                : x.getKey().compareTo(y.getKey()));
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (int i = 0; i < Math.min(TOP_KEYWORDS, entries.size()); i++) {
            out.put(entries.get(i).getKey(), entries.get(i).getValue());
        }
        return out;
    }

    /**
     * The keyword of each line: the first word of the line's top-level (unindented) command, so the sub-lines of a block
     * (a certificate's hex rows, an object's members) count under the block's command. A word that is not a plain
     * lower-case command word becomes "other": a value never becomes a keyword. A leading "no" is skipped.
     */
    static List<String> parentKeywords(List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        String parent = "other";
        for (String line : lines) {
            if (!line.startsWith(" ")) {
                parent = keywordOf(line);
            }
            out.add(parent);
        }
        return out;
    }

    private static String keywordOf(String line) {
        String[] words = line.trim().split("\\s+", 3);
        String w = words[0];
        if (w.equals("no") && words.length > 1) {
            w = words[1];
        }
        return KEYWORD.matcher(w).matches() ? w : "other";
    }

    /** Deleted (running) and inserted (startup) line indices, each as {hunk, index}; a hunk is a run of changes between matched lines. */
    private record Edits(List<int[]> deletes, List<int[]> inserts) { }

    static Edits diff(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        int pre = 0;
        while (pre < n && pre < m && a.get(pre).equals(b.get(pre))) {
            pre++;
        }
        int suf = 0;
        while (suf < n - pre && suf < m - pre && a.get(n - 1 - suf).equals(b.get(m - 1 - suf))) {
            suf++;
        }
        int an = n - pre - suf;
        int bn = m - pre - suf;
        List<int[]> dels = new ArrayList<>();
        List<int[]> ins = new ArrayList<>();
        if (an == 0 && bn == 0) {
            return new Edits(dels, ins);
        }
        java.util.Map<String, Integer> ids = new java.util.HashMap<>();
        int[] x = new int[an];
        int[] y = new int[bn];
        for (int i = 0; i < an; i++) {
            x[i] = ids.computeIfAbsent(a.get(pre + i), k -> ids.size());
        }
        for (int j = 0; j < bn; j++) {
            y[j] = ids.computeIfAbsent(b.get(pre + j), k -> ids.size());
        }
        if (!myers(x, y, pre, dels, ins)) {
            dels.clear();
            ins.clear();
            multisetDifference(a, b, pre, an, bn, dels, ins);
        }
        return new Edits(dels, ins);
    }

    /** Order-free fallback for texts too different for the exact diff: every line is one hunk 0. */
    private static void multisetDifference(List<String> a, List<String> b, int pre, int an, int bn,
            List<int[]> dels, List<int[]> ins) {
        Map<String, Integer> inB = new java.util.HashMap<>();
        Map<String, Integer> inA = new java.util.HashMap<>();
        for (int j = 0; j < bn; j++) {
            inB.merge(b.get(pre + j), 1, Integer::sum);
        }
        for (int i = 0; i < an; i++) {
            inA.merge(a.get(pre + i), 1, Integer::sum);
        }
        for (int i = 0; i < an; i++) {
            String line = a.get(pre + i);
            int left = inB.getOrDefault(line, 0);
            if (left > 0) {
                inB.put(line, left - 1);
            } else {
                dels.add(new int[] {0, pre + i});
            }
        }
        for (int j = 0; j < bn; j++) {
            String line = b.get(pre + j);
            int left = inA.getOrDefault(line, 0);
            if (left > 0) {
                inA.put(line, left - 1);
            } else {
                ins.add(new int[] {0, pre + j});
            }
        }
    }

    /** Myers O(ND) shortest edit script; false when the distance exceeds {@link #MAX_EXACT_DISTANCE}. */
    private static boolean myers(int[] a, int[] b, int offset, List<int[]> dels, List<int[]> ins) {
        int n = a.length;
        int m = b.length;
        int max = Math.min(n + m, MAX_EXACT_DISTANCE);
        int[] v = new int[2 * max + 3];
        int o = max + 1;
        List<int[]> trace = new ArrayList<>();
        int found = -1;
        for (int d = 0; d <= max && found < 0; d++) {
            for (int k = -d; k <= d; k += 2) {
                int px = (k == -d || (k != d && v[o + k - 1] < v[o + k + 1])) ? v[o + k + 1] : v[o + k - 1] + 1;
                int py = px - k;
                while (px < n && py < m && a[px] == b[py]) {
                    px++;
                    py++;
                }
                v[o + k] = px;
                if (px >= n && py >= m) {
                    found = d;
                    break;
                }
            }
            int[] snap = new int[2 * d + 1];
            System.arraycopy(v, o - d, snap, 0, snap.length);
            trace.add(snap);
        }
        if (found < 0) {
            return false;
        }
        int px = n;
        int py = m;
        int hunk = 0;
        List<int[]> d1 = new ArrayList<>();
        List<int[]> i1 = new ArrayList<>();
        for (int d = found; d > 0; d--) {
            int[] prev = trace.get(d - 1);
            int k = px - py;
            boolean down = k == -d || (k != d && prev[k - 1 + d - 1] < prev[k + 1 + d - 1]);
            int prevK = down ? k + 1 : k - 1;
            int prevX = prev[prevK + d - 1];
            int prevY = prevX - prevK;
            int endX = down ? prevX : prevX + 1;
            int endY = down ? prevY + 1 : prevY;
            if (px > endX) {
                hunk++;
            }
            if (down) {
                i1.add(new int[] {hunk, offset + prevY});
            } else {
                d1.add(new int[] {hunk, offset + prevX});
            }
            px = prevX;
            py = prevY;
        }
        // hunks were numbered from the end; renumbering is unnecessary, equal ids mean the same hunk
        java.util.Collections.reverse(d1);
        java.util.Collections.reverse(i1);
        dels.addAll(d1);
        ins.addAll(i1);
        return true;
    }

    static List<String> normalize(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String raw : text.split("\\R", -1)) {
            String line = raw.stripTrailing();
            if (line.isBlank() || line.startsWith(":") || line.startsWith("Cryptochecksum")) {
                continue;
            }
            out.add(line);
        }
        return out;
    }

    private static boolean isAsaConfiguration(List<String> lines) {
        for (String l : lines) {
            if (l.contains("ASA Version")) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when {@code running} is {@code masked} with every mask token replaced by a non-empty value: the fixed pieces
     * between the tokens appear in order, the first at the start and the last at the end.
     */
    static boolean matchesMasked(String masked, String running) {
        String[] pieces = masked.split(java.util.regex.Pattern.quote(MASK), -1);
        String first = pieces[0];
        String last = pieces[pieces.length - 1];
        if (!running.startsWith(first)) {
            return false;
        }
        int pos = first.length();
        int end = running.length() - last.length();
        if (end < pos || !running.endsWith(last)) {
            return false;
        }
        for (int i = 1; i < pieces.length - 1; i++) {
            // each token stands for at least one character
            int at = running.indexOf(pieces[i], pos + 1);
            if (at < 0 || at + pieces[i].length() > end) {
                return false;
            }
            pos = at + pieces[i].length();
        }
        return end - pos >= 1;
    }
}
