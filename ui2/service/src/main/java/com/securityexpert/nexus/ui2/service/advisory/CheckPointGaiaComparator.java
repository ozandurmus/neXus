package com.securityexpert.nexus.ui2.service.advisory;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Check Point Gaia (amendment A-2026-11-13). A Gaia device is compared by its train and its Jumbo Hotfix Accumulator take,
 * both as neXus already stores them: the software version ({@code R81.20}) and the hotfix level the inventory reads from
 * {@code cpinfo -y all} ({@code R81.20 Jumbo Take 119}). The canonical form is that hotfix level text, accepted only when
 * its train equals the software version. Takes are compared as whole numbers inside one train; two trains are never
 * compared. Nothing is trimmed or guessed: a version without a take is not a version of take 0.
 */
public final class CheckPointGaiaComparator implements ProductComparator {

    /** The trains whose resolved-issues page the source reads (amendment A-2026-11-13, section 2). */
    public static final List<String> COVERED_TRAINS = List.of("R81.20", "R82", "R82.10", "R82.20");

    static final String TRAIN_TEXT = "R[0-9]{2}(?:\\.[0-9]{1,2})?";
    private static final Pattern CANONICAL = Pattern.compile("(" + TRAIN_TEXT + ") Jumbo Take ([1-9][0-9]{0,4})");
    private static final Pattern BARE_TRAIN = Pattern.compile(TRAIN_TEXT);
    /** Quantum Spark (Gaia Embedded) firmware, for example {@code R81.10.15}: not a Gaia train with a Jumbo take. */
    private static final Pattern SPARK = Pattern.compile("R[0-9]{2}\\.[0-9]{1,2}\\.[0-9A-Za-z]{1,6}(?:\\s.*)?");
    /** The form {@link #compose} gives when the hotfix level names another train than the software version. */
    private static final String MISMATCH_MARK = " (hotfix level of ";
    /** The form {@link #compose} gives when a stored hotfix level is not a Jumbo take this class reads. */
    private static final String UNREAD_MARK = " (hotfix level not recognised)";

    private final String productKey;

    public CheckPointGaiaComparator(String productKey) {
        this.productKey = productKey;
    }

    /**
     * The version a Check Point device is compared by: its hotfix level when that names the same train as its software
     * version; the software version alone when no hotfix level is stored (it then reads {@code JUMBO_TAKE_MISSING}); a marked
     * text when the two disagree ({@code JUMBO_TRAIN_MISMATCH}). Null when no software version is stored.
     */
    public static String compose(String softwareVersion, String hotfixLevel) {
        if (softwareVersion == null || softwareVersion.isBlank()) {
            return null;
        }
        String version = softwareVersion.strip();
        if (hotfixLevel == null || hotfixLevel.isBlank()) {
            return version;
        }
        Matcher m = CANONICAL.matcher(hotfixLevel.strip());
        if (!m.matches()) {
            // A hotfix level that is stored but not read is not "no take": it is a version not understood.
            return version + UNREAD_MARK;
        }
        return m.group(1).equals(version) ? m.group() : version + MISMATCH_MARK + m.group(1) + ")";
    }

    @Override
    public String productKey() {
        return productKey;
    }

    @Override
    public Optional<String> canonical(String storedVersion) {
        return storedVersion != null && CANONICAL.matcher(storedVersion).matches() ? Optional.of(storedVersion) : Optional.empty();
    }

    @Override
    public Optional<String> train(String canonicalVersion) {
        Matcher m = canonicalVersion == null ? null : CANONICAL.matcher(canonicalVersion);
        return m != null && m.matches() ? Optional.of(m.group(1)) : Optional.empty();
    }

    @Override
    public int compare(String canonicalA, String canonicalB) {
        Matcher a = canonicalA == null ? null : CANONICAL.matcher(canonicalA);
        Matcher b = canonicalB == null ? null : CANONICAL.matcher(canonicalB);
        if (a == null || b == null || !a.matches() || !b.matches()) {
            throw new IllegalArgumentException("not a canonical Check Point version");
        }
        if (!a.group(1).equals(b.group(1))) {
            throw new IllegalArgumentException("Check Point versions of two trains are not compared");
        }
        return Integer.compare(Integer.parseInt(a.group(2)), Integer.parseInt(b.group(2)));
    }

    @Override
    public Optional<String> notCoveredReason(String storedVersion) {
        if (storedVersion == null) {
            return Optional.empty();
        }
        String text = storedVersion.strip();
        // The software version decides coverage, whatever the hotfix level says (review 2026-11-14, finding 7).
        int mark = text.indexOf(" (hotfix level");
        if (mark > 0) {
            text = text.substring(0, mark);
        }
        if (SPARK.matcher(text).matches()) {
            return Optional.of("SPARK_NOT_COVERED");
        }
        Optional<String> train = train(text);
        if (train.isEmpty() && BARE_TRAIN.matcher(text).matches()) {
            train = Optional.of(text);
        }
        return train.filter(t -> !COVERED_TRAINS.contains(t)).map(t -> "TRAIN_NOT_COVERED");
    }

    @Override
    public String unparsedReason(String storedVersion) {
        if (storedVersion != null && storedVersion.contains(UNREAD_MARK)) {
            return "VERSION_UNPARSED";
        }
        if (storedVersion != null && storedVersion.contains(MISMATCH_MARK)) {
            return "JUMBO_TRAIN_MISMATCH";
        }
        if (storedVersion != null && BARE_TRAIN.matcher(storedVersion.strip()).matches()) {
            return "JUMBO_TAKE_MISSING";
        }
        return "VERSION_UNPARSED";
    }
}
