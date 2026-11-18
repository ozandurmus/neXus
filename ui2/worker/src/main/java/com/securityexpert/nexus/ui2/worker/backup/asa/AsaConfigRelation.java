package com.securityexpert.nexus.ui2.worker.backup.asa;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The derived relation between the two configuration reads of one ASA backup: {@code more system:running-config} (keys in
 * clear) and {@code show startup-config} (keys masked, its own header lines). Computed in memory while both texts are
 * held; only this result is stored, never a line of either text.
 *
 * <p>Normalisation of both texts: lines beginning with ':' (the ASA's own header and comment lines such as
 * ": Saved", ": Written by ...") and {@code Cryptochecksum} lines are dropped, trailing whitespace is trimmed, blank
 * lines are ignored. The remaining lines are compared in order.</p>
 *
 * <p>Masking assumption (needs confirmation on a real unit): the ASA prints a stored key as the five-character token
 * {@code *****} in {@code show startup-config} (and in {@code show running-config}), while {@code more system:running-config}
 * prints it in clear. Cisco ASA command reference, "more" and "show running-config" / "show startup-config":
 * https://www.cisco.com/c/en/us/td/docs/security/asa/asa-command-reference/I-R/cmdref2/m.html and
 * https://www.cisco.com/c/en/us/td/docs/security/asa/asa-command-reference/S/cmdref3/s1.html (the pages were not fetched
 * by the author; the token is the documented and widely observed form). Encrypted or hashed secrets
 * ({@code ... encrypted}, {@code pbkdf2}) print identically in both reads and need no special case.</p>
 */
public record AsaConfigRelation(Result result, int linesCompared, int linesDiffering, int linesEqualOnlyByMask) {

    public enum Result { MATCH, MISMATCH, NOT_EVALUABLE }

    /** The token an ASA prints in place of a stored key. */
    public static final String MASK = "*****";

    public AsaConfigRelation {
        Objects.requireNonNull(result, "result");
    }

    public static final AsaConfigRelation NOT_EVALUABLE = new AsaConfigRelation(Result.NOT_EVALUABLE, 0, 0, 0);

    /** Compares the two texts; either null or blank, or one that is not an ASA configuration, is NOT_EVALUABLE. */
    public static AsaConfigRelation compare(String running, String startup) {
        List<String> r = normalize(running);
        List<String> s = normalize(startup);
        if (!isAsaConfiguration(r) || !isAsaConfiguration(s)) {
            return NOT_EVALUABLE;
        }
        int pairs = Math.min(r.size(), s.size());
        int differing = Math.abs(r.size() - s.size());
        int maskOnly = 0;
        for (int i = 0; i < pairs; i++) {
            String running1 = r.get(i);
            String startup1 = s.get(i);
            if (running1.equals(startup1)) {
                continue;
            }
            if (startup1.contains(MASK) && matchesMasked(startup1, running1)) {
                maskOnly++;
            } else {
                differing++;
            }
        }
        int compared = Math.max(r.size(), s.size());
        return new AsaConfigRelation(differing == 0 ? Result.MATCH : Result.MISMATCH, compared, differing, maskOnly);
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
