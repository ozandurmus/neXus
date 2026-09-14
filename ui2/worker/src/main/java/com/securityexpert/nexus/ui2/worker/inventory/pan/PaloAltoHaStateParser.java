package com.securityexpert.nexus.ui2.worker.inventory.pan;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code show high-availability state} (14E PF-1). This read already runs
 * on every {@code inventory_collect} pass (its output was discarded before
 * this movement -- 14D CF-1..CF-4's session-sequence fidelity note applies
 * equally to Palo Alto's PF-1 order); this class is the parse-scope
 * extension that keeps the role it carries, mirroring what {@link
 * com.securityexpert.nexus.ui2.worker.inventory.cp.CheckPointHaStateParser}
 * already does for Check Point (AGENTS.md: "a parse-scope extension of a
 * command already issued... is not a command addition").
 *
 * <p>The XML shape read here ({@code result/enabled}, {@code
 * result/group/local-info/state}, {@code result/group/mode}) is PAN-OS's
 * own documented response shape, not yet corroborated against a real
 * device in this repository (AGENTS.md vendor-semantics law: field
 * presence is not field semantic proof). {@code enabled=no} maps to {@link
 * #STANDALONE}; otherwise the role is {@code local-info/state}'s own text,
 * upper-cased, or {@link #UNKNOWN} when the shape does not match.</p>
 */
public final class PaloAltoHaStateParser {

    public static final String STANDALONE = "STANDALONE";
    public static final String UNKNOWN = "UNKNOWN";

    public record HaState(String role, Optional<String> clusterMode) {
    }

    private static final Pattern ENABLED = Pattern.compile("(?is)<enabled>\\s*([^<]*?)\\s*</enabled>");
    private static final Pattern LOCAL_STATE = Pattern.compile("(?is)<local-info>.*?<state>\\s*([^<]*?)\\s*</state>");
    private static final Pattern MODE = Pattern.compile("(?is)<group>.*?<mode>\\s*([^<]*?)\\s*</mode>");

    private PaloAltoHaStateParser() {
    }

    public static HaState parse(String output) {
        if (output == null || output.isBlank()) {
            return new HaState(UNKNOWN, Optional.empty());
        }
        Optional<String> enabled = firstMatch(ENABLED, output);
        if (enabled.isPresent() && "no".equalsIgnoreCase(enabled.get())) {
            return new HaState(STANDALONE, Optional.empty());
        }
        String role = firstMatch(LOCAL_STATE, output).map(s -> s.toUpperCase(Locale.ROOT)).orElse(UNKNOWN);
        return new HaState(role, firstMatch(MODE, output));
    }

    private static Optional<String> firstMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() && !matcher.group(1).isBlank() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
}
