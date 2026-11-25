package com.securityexpert.nexus.ui2.service.logdetect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The closed list of hardware and kernel markers of layer A (LOG_DETECTION_ENGINE_DESIGN_2026_11_24.md section 3.2).
 * They match <b>masked</b> lines of {@code cp_messages}; each is a generic Linux kernel message form, not a scenario.
 *
 * <p>Every marker is {@link LogProvenance#PROVISIONAL} until two checks pass (design section 3.2): the census counts the
 * pattern on the pool, and the message form is cited from the kernel documentation or the vendor. A PROVISIONAL marker
 * with S = 1.0 therefore produces at most an ATTENTION finding ({@link LogSeverity}). Changing this list is a new
 * {@link #LIST_VERSION}; it is never edited ad hoc.</p>
 *
 * <p>Evidence keys: the memory slot label is stored as a key token only when it matches the closed form
 * {@code CPU_SrcID#n_MC#n_Chan#n_DIMM#n} (letters, digits and {@code # _} only: a hardware location, no identity);
 * any other word becomes a keyed opaque token, so slots are told apart by equality and never parsed (identity law).
 * The OOM-killed process name is <b>always</b> a keyed opaque token ({@link LogKeyToken}), never the name: a process name
 * is free text of the device (it may carry {@code +}, {@code :} or an operator-chosen word) and the key token is shown to
 * the aiview persona. The block-device name is a closed form ({@code [a-z0-9]{1,16}}). Every key this class returns fits
 * the V272 {@code key_token} CHECK ({@code ^[A-Za-z0-9#_.:-]{0,64}$}), so no input line can make an evidence insert fail.</p>
 */
public final class HardwareMarkers {
    /** The version of the marker list; part of a finding's version set (design section 6.3). */
    public static final String LIST_VERSION = "markers-p1-1";

    /** One marker: its id, severity weight S, provenance, whether it only anchors other detectors, and its pattern. */
    public record Marker(String id, double weight, LogProvenance provenance, boolean anchor, Pattern pattern) {
        /** S = 1.0 markers cannot be suppressed by rule (design section 7.3). */
        public boolean suppressible() {
            return weight < 1.0;
        }

        /** The hardware exemption of design section 3.4 rule 5: S = 1.0 markers are exempt from rules 3 and 4. */
        public boolean exemptFromChronic() {
            return weight >= 1.0;
        }

        /** {@code hw.edac.ce}, {@code hw.mce}, {@code kern.oom} and {@code io.error} are exempt from rule 4 only. */
        public boolean exemptFromFleetBackground() {
            return weight >= 1.0 || id.equals("hw.edac.ce") || id.equals("hw.mce") || id.equals("kern.oom")
                    || id.equals("io.error");
        }
    }

    /** One marker occurrence on a line: the marker and its evidence key token ({@code ""} when the marker has none). */
    public record Hit(Marker marker, String keyToken) {
    }

    private static final Pattern SLOT_LABEL = Pattern.compile("\\bon (CPU_SrcID#\\d{1,3}_(?:MC|Ha)#\\d{1,3}_Chan#\\d{1,3}_DIMM#\\d{1,3})\\b");
    private static final Pattern ON_WORD = Pattern.compile("\\bon ([^\\s(]{1,80})");
    private static final Pattern OOM_PROCESS = Pattern.compile("\\bKill(?:ed)? process \\d+ \\(([^)\\s]{1,64})\\)");
    private static final Pattern BLOCK_DEVICE = Pattern.compile("\\bdev ([a-z0-9]{1,16})\\b");

    /** The list, in the order of design section 3.2. */
    public static final List<Marker> LIST = List.of(
            marker("hw.edac.ce", 0.8, false, "\\bEDAC\\b[^:\\n]{0,40}:\\s+\\d+\\s+CE\\b"),
            marker("hw.edac.ue", 1.0, false, "\\bEDAC\\b[^:\\n]{0,40}:\\s+\\d+\\s+UE\\b"),
            marker("hw.mce", 0.8, false, "\\[Hardware Error\\]"),
            marker("kern.panic", 1.0, false, "\\bKernel panic\\b"),
            marker("kern.oops", 0.8, false, "(?:\\bOops:|\\bkernel BUG at\\b|\\bBUG: (?:unable to handle|kernel NULL|soft lockup))"),
            marker("kern.oom", 0.8, false, "\\bOut of memory: Kill(?:ed)? process\\b"),
            marker("io.error", 0.8, false, "(?:\\bblk_update_request: I/O error\\b|\\bBuffer I/O error\\b|\\bend_request: I/O error\\b)"),
            marker("fs.readonly", 1.0, false, "(?i:\\bRemounting filesystem read-only\\b|\\bfilesystem re-?mounted read-only\\b)"),
            marker("kern.boot", 0.15, true, "\\bkernel: Linux version \\d+\\.\\d+"));

    private static final Map<String, Marker> BY_ID;

    static {
        java.util.LinkedHashMap<String, Marker> m = new java.util.LinkedHashMap<>();
        for (Marker marker : LIST) m.put(marker.id(), marker);
        BY_ID = java.util.Collections.unmodifiableMap(m);
    }

    private final Function<String, String> opaqueToken;

    /** @param opaqueToken keyed one-way token of a word that is not in a closed vocabulary (equality only) */
    public HardwareMarkers(Function<String, String> opaqueToken) {
        this.opaqueToken = opaqueToken;
    }

    public static Optional<Marker> byId(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    private static Marker marker(String id, double weight, boolean anchor, String regex) {
        return new Marker(id, weight, LogProvenance.PROVISIONAL, anchor, Pattern.compile(regex));
    }

    /** The markers a masked {@code cp_messages} line carries, with their evidence keys. At most one hit per marker. */
    public List<Hit> match(String maskedLine) {
        List<Hit> hits = new ArrayList<>(1);
        if (maskedLine == null || maskedLine.isEmpty()) return hits;
        for (Marker marker : LIST) {
            if (!marker.pattern().matcher(maskedLine).find()) continue;
            hits.add(new Hit(marker, keyFor(marker, maskedLine)));
        }
        return hits;
    }

    private String keyFor(Marker marker, String line) {
        return switch (marker.id()) {
            case "hw.edac.ce", "hw.edac.ue" -> slotKey(line);
            case "kern.oom" -> {
                Matcher m = OOM_PROCESS.matcher(line);
                yield m.find() ? opaque(m.group(1)) : "";
            }
            case "io.error" -> {
                Matcher m = BLOCK_DEVICE.matcher(line);
                yield m.find() ? m.group(1) : "";
            }
            default -> "";
        };
    }

    private String slotKey(String line) {
        Matcher closed = SLOT_LABEL.matcher(line);
        if (closed.find()) return closed.group(1);
        Matcher any = ON_WORD.matcher(line);
        return any.find() ? opaque(any.group(1)) : "";
    }

    private String opaque(String value) {
        return opaqueToken == null ? "OTHER" : opaqueToken.apply(value);
    }
}
