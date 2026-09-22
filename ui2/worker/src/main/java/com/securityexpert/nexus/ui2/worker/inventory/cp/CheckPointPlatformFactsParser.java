package com.securityexpert.nexus.ui2.worker.inventory.cp;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Check Point platform facts (PLATFORM_IDENTITY_FACTS_CONTRACT §3, measured
 * 2026-09-22 -- docs/design/CP_PLATFORM_IDENTITY_MEASUREMENTS.md):
 * <ul>
 * <li>{@code cpinfo -y all}: the installed hotfix level. The load-bearing line
 * is the main jumbo, {@code HOTFIX_R81_20_JUMBO_HF_MAIN     Take:  122},
 * repeated under several package headings with the same take; the first
 * occurrence with a take number is the level. A device with no jumbo line
 * reports {@code no jumbo hotfix}.</li>
 * <li>{@code uptime}: {@code 21:43:46 up 237 days, 21:10,  2 users, ...} --
 * the text between {@code up } and the user count.</li>
 * </ul>
 * Nothing else in either output is kept (raw-evidence law).
 */
public final class CheckPointPlatformFactsParser {

    private static final Pattern JUMBO_TAKE =
            Pattern.compile("(?im)^\\s*HOTFIX_(R\\d+(?:_\\d+)*)_JUMBO_HF_MAIN\\s+Take:\\s*(\\d+)\\s*$");
    private static final Pattern UPTIME = Pattern.compile("(?i)\\bup\\s+(.+?),\\s*\\d+\\s+users?\\b");

    private CheckPointPlatformFactsParser() {
    }

    /** {@code Optional.empty()} when the output carries no main-jumbo line with a take number. */
    public static Optional<String> jumboTake(String cpinfoOutput) {
        if (cpinfoOutput == null || cpinfoOutput.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = JUMBO_TAKE.matcher(cpinfoOutput);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(matcher.group(1).replace('_', '.') + " Jumbo Take " + matcher.group(2));
    }

    public static Optional<String> uptime(String uptimeOutput) {
        if (uptimeOutput == null || uptimeOutput.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = UPTIME.matcher(uptimeOutput);
        return matcher.find() ? Optional.of(matcher.group(1).strip()) : Optional.empty();
    }
}
