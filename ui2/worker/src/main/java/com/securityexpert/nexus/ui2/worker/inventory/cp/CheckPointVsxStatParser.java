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

    /** One {@code Virtual Devices Status} row: the VSID, kept type letter, and virtual system name. */
    public record VsxDevice(String vsid, String type, String name) {
        public VsxDevice(String vsid, String type) {
            this(vsid, type, "");
        }
    }

    /** {@code vsx} false and {@link #devices()} empty when CF-4's non-VSX text is present. */
    public record VsxStatResult(boolean vsx, List<VsxDevice> devices) {
    }

    private static final Pattern NOT_SUPPORTED = Pattern.compile("(?i)VSX is not supported on this platform");
    private static final Pattern DEVICE_ROW = Pattern.compile(
            "(?i)^\\s*\\|?\\s*(\\d+)\\s*(?:\\|\\s*|\\s+)([SBRW]|VS|VR|VW|VB)(?:\\s+VS0)?(?:(?:\\s*\\|\\s*|\\s+)([^|\\s]+))?");

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
                String vsid = row.group(1);
                String type = row.group(2);
                String name = row.group(3) != null ? row.group(3).trim() : "";
                devices.add(new VsxDevice(vsid, type, name));
            }
        }
        // A VSX gateway always lists at least VS0 in its Virtual Devices Status table, so output
        // with no parsable row at all is not VSX -- measured live, 2026-09-22, on a Quantum Spark
        // appliance whose Clish answered "vsx stat -v" with a short CLI error (not CF-4's "not
        // supported" text): treating that as vsx=true wrapped every later read in "vsenv 0 &&",
        // which that shell cannot run, so the whole collection returned junk.
        return new VsxStatResult(!devices.isEmpty(), devices);
    }

    /** The VSIDs alone, in row order, for callers that only need the id set (the executor's own use). */
    public static List<String> parseVsids(String output) {
        return parse(output).devices().stream().map(VsxDevice::vsid).toList();
    }
}
