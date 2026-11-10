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
    private static final Pattern LIVE_PATCH_TAKE =
            Pattern.compile("(?im)^\\s*BUNDLE_URGENT_SECURITY_UPDATE_\\S*\\s+Take:\\s*(\\d+)\\s*$");
    private static final Pattern UPTIME = Pattern.compile("(?i)\\bup\\s+(.+?),\\s*\\d+\\s+users?\\b");
    private static final Pattern ASSET_SERIAL = Pattern.compile("(?im)^\\s*Serial Number:\\s*(\\S.*?)\\s*$");
    private static final Pattern ASSET_MODEL = Pattern.compile("(?im)^\\s*Model:\\s*(\\S.*?)\\s*$");
    private static final Pattern ASSET_PLATFORM = Pattern.compile("(?im)^\\s*Platform:\\s*(\\S.*?)\\s*$");

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

    /** The key under which the Live Patch package take travels in the platform facts' content versions. */
    public static final String LIVE_PATCH_PACKAGE_TAKE_KEY = "live_patch_package_take";

    /**
     * The Check Point Live Patch package take from the same {@code cpinfo -y all} answer (no extra command): a line
     * {@code BUNDLE_URGENT_SECURITY_UPDATE_<token>   Take:  <digits>}. Several matching lines: the highest take.
     * {@code Optional.empty()} when no line matches.
     */
    public static Optional<String> livePatchPackageTake(String cpinfoOutput) {
        if (cpinfoOutput == null || cpinfoOutput.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = LIVE_PATCH_TAKE.matcher(cpinfoOutput);
        java.math.BigInteger best = null;
        while (matcher.find()) {
            java.math.BigInteger take = new java.math.BigInteger(matcher.group(1));
            if (best == null || take.compareTo(best) > 0) {
                best = take;
            }
        }
        return best == null ? Optional.empty() : Optional.of(best.toString());
    }

    private static final Pattern URGENT_LINE = Pattern.compile("(?im)^.*URGENT.*$");

    /**
     * Shape of the lines of a {@code cpinfo -y all} answer that name URGENT but did not match {@link #livePatchPackageTake}:
     * every digit run replaced by {@code 9}, characters outside a closed set dropped (same masking as {@link #noJumboShape}).
     */
    public static String urgentShape(String cpinfoOutput) {
        if (cpinfoOutput == null) return "urgentLines=0";
        java.util.Set<String> shapes = new java.util.TreeSet<>();
        long count = 0;
        Matcher m = URGENT_LINE.matcher(cpinfoOutput);
        while (m.find()) {
            count++;
            if (shapes.size() < 12) {
                String shape = SHAPE_SAFE.matcher(m.group().strip().replaceAll("\\d+", "9")).replaceAll("").replaceAll("\\s+", " ");
                if (shape.length() > 80) shape = shape.substring(0, 80);
                if (!shape.isBlank()) shapes.add(shape);
            }
        }
        return "urgentLines=" + count + " shapes=" + String.join(" | ", shapes);
    }

    private static final Pattern SHAPE_LINE = Pattern.compile("(?im)^.*(?:JUMBO|HOTFIX_R\\d|Take:).*$");
    private static final Pattern SHAPE_SAFE = Pattern.compile("[^A-Za-z0-9_:. \\-\\[\\]]");

    /**
     * Shape of a {@code cpinfo -y all} answer that carried no main-jumbo line (PO 2026-11-06: the R82 management server
     * shows no hotfix level). Counts plus the distinct jumbo/take lines with every digit run replaced by {@code 9} and any
     * other character outside a closed set dropped: vendor package tokens only, never a host name, address or serial.
     */
    public static String noJumboShape(String cpinfoOutput) {
        if (cpinfoOutput == null) return "len=0 lines=0";
        String[] lines = cpinfoOutput.split("\\R", -1);
        long hotfix = java.util.Arrays.stream(lines).filter(l -> l.strip().startsWith("HOTFIX_")).count();
        java.util.Set<String> shapes = new java.util.TreeSet<>();
        Matcher m = SHAPE_LINE.matcher(cpinfoOutput);
        while (m.find() && shapes.size() < 12) {
            String shape = SHAPE_SAFE.matcher(m.group().strip().replaceAll("\\d+", "9")).replaceAll("").replaceAll("\\s+", " ");
            if (shape.length() > 80) shape = shape.substring(0, 80);
            if (!shape.isBlank()) shapes.add(shape);
        }
        return "len=" + cpinfoOutput.length() + " lines=" + lines.length + " hotfixLines=" + hotfix + " shapes=" + String.join(" | ", shapes);
    }

    /** {@code clish -c "show asset system"}: the appliance serial number; empty on an open server or a read that failed. */
    public static Optional<String> assetSerial(String assetOutput) {
        return firstGroup(ASSET_SERIAL, assetOutput).filter(v -> !v.equalsIgnoreCase("N/A"));
    }

    /** The appliance family as {@code Model (Platform)}, e.g. {@code Smart-1 5150 (ST-4150-00)}; model alone when no platform line. */
    public static Optional<String> assetFamily(String assetOutput) {
        Optional<String> model = firstGroup(ASSET_MODEL, assetOutput);
        Optional<String> platform = firstGroup(ASSET_PLATFORM, assetOutput);
        return model.map(m -> platform.map(p -> m + " (" + p + ")").orElse(m)).or(() -> platform);
    }

    private static Optional<String> firstGroup(Pattern pattern, String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1).strip()) : Optional.empty();
    }

    public static Optional<String> uptime(String uptimeOutput) {
        if (uptimeOutput == null || uptimeOutput.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = UPTIME.matcher(uptimeOutput);
        return matcher.find() ? Optional.of(matcher.group(1).strip()) : Optional.empty();
    }
}
