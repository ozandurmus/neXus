package com.securityexpert.nexus.ui2.worker.inventory.cp;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code vsx stat -v} (14D CF-3, CF-4). VSX detection is by text: a
 * non-VSX gateway prints {@code VSX is not supported on this platform}
 * with exit code 0, so the exit code proves nothing and is never consulted
 * here -- {@link #parse} returns an empty, {@link VsxStatResult#vsx()
 * false}-flagged result for that text. Otherwise the VSID set comes only
 * from the {@code Virtual Devices Status} table's rows, matched by their
 * {@code <ID> | <Type letter> <name> | ...} shape regardless of the
 * surrounding column layout -- the ID column is the only trustworthy one,
 * the type letter ({@code S} virtual system, {@code B} bridge mode,
 * {@code R} virtual router, {@code W} virtual switch) is kept alongside
 * it, and the caller (not this parser) decides not to re-enter VS0.
 */
public final class CheckPointVsxStatParser {

    /** One {@code Virtual Devices Status} row: the VSID and its kept type letter. */
    public record VsxDevice(String vsid, String type) {
    }

    /** {@code vsx} false and {@link #devices()} empty when CF-4's non-VSX text is present. */
    public record VsxStatResult(boolean vsx, List<VsxDevice> devices) {
    }

    private static final Pattern NOT_SUPPORTED = Pattern.compile("(?i)VSX is not supported on this platform");
    private static final Pattern DEVICE_ROW = Pattern.compile("^(\\d+)\\s*\\|\\s*([SBRW])\\b");

    private CheckPointVsxStatParser() {
    }

    public static VsxStatResult parse(String output) {
        if (output == null || output.isBlank() || NOT_SUPPORTED.matcher(output).find()) {
            return new VsxStatResult(false, List.of());
        }
        List<VsxDevice> devices = new ArrayList<>();
        for (String rawLine : output.split("\\R")) {
            Matcher row = DEVICE_ROW.matcher(rawLine.trim());
            if (row.find()) {
                devices.add(new VsxDevice(row.group(1), row.group(2)));
            }
        }
        return new VsxStatResult(true, devices);
    }

    /** The VSIDs alone, in row order, for callers that only need the id set (the executor's own use). */
    public static List<String> parseVsids(String output) {
        return parse(output).devices().stream().map(VsxDevice::vsid).toList();
    }
}
