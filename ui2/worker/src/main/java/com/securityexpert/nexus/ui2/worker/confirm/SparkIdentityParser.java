package com.securityexpert.nexus.ui2.worker.confirm;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded Gaia Embedded identity projection; raw diagnostic output is discarded. */
public final class SparkIdentityParser {
    private static final Pattern UNIT_MODEL = Pattern.compile("(?im)^\\s*Unit model\\s*:\\s*([A-Za-z0-9_.-]{1,64})\\s*$");
    private static final Pattern HW_VERSION = Pattern.compile("(?im)^\\s*HW version\\s*:\\s*([A-Za-z0-9_.-]{1,64})\\s*$");
    private static final Pattern IMAGE_VERSION = Pattern.compile("(?im)^\\s*Current image version\\s*:\\s*([A-Za-z0-9_.-]{1,64})\\s*$");
    private static final Pattern IMAGE_NAME = Pattern.compile("(?im)^\\s*Current image name\\s*:\\s*([A-Za-z0-9_.-]{1,128})\\s*$");
    private static final Pattern SOFTWARE_VERSION = Pattern.compile("(?im)^\\s*This is Check Point's .+ Appliance (R[0-9]+(?:\\.[0-9]+)+(?:\\.[0-9]+)?)\\s*-\\s*Build ([0-9]{1,12})\\s*$");
    private static final Pattern PROMPT = Pattern.compile("^([A-Za-z0-9_.-]{1,64})>$");

    private SparkIdentityParser() {}

    private static Optional<String> field(Pattern pattern, String text) {
        if (text == null) return Optional.empty();
        Matcher match = pattern.matcher(text);
        return match.find() ? Optional.of(match.group(1)) : Optional.empty();
    }

    public static Optional<String> model(String diag) {
        // Unit model may be an internal revision (V1); HW version is not a model.
        return field(UNIT_MODEL, diag).filter(model -> !model.matches("[VH][0-9]+")).filter(model ->
                com.securityexpert.nexus.ui2.jobs.admission.CheckPointSparkModelHint
                        .isKnownSparkModel(Optional.of(model)));
    }

    /** Revision evidence is a Spark signal, never a device model. */
    public static Optional<String> sparkRevision(String diag) {
        return field(UNIT_MODEL, diag).filter(value -> value.matches("V[0-9]+"))
                .or(() -> field(HW_VERSION, diag).filter(value -> value.matches("V[0-9]+")));
    }

    public static Optional<String> version(String softwareVersion, String diag) {
        return field(SOFTWARE_VERSION, softwareVersion)
                .or(() -> field(IMAGE_VERSION, diag))
                .or(() -> field(IMAGE_NAME, diag));
    }

    /**
     * The firmware level of a Quantum Spark (PO 2026-11-06: the Spark hotfix level). Gaia Embedded has no jumbo
     * hotfix and no hotfix command: Check Point ships Spark fixes as firmware builds, and {@code show software-version}
     * "shows the version and the build of the current software" (R81.10.X Spark CLI Reference Guide, p. 1930). The
     * level is that version and build, e.g. {@code R81.10.17 Build 996004039}; empty when the line is not there.
     */
    public static Optional<String> firmwareLevel(String softwareVersion) {
        if (softwareVersion == null) return Optional.empty();
        Matcher match = SOFTWARE_VERSION.matcher(softwareVersion);
        return match.find() ? Optional.of(match.group(1) + " Build " + match.group(2)) : Optional.empty();
    }

    public static Optional<String> hostname(String prompt) {
        return field(PROMPT, prompt);
    }
}
