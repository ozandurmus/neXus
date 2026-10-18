package com.securityexpert.nexus.ui2.packetanalysis.export;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * The hop marker rule approved by the Product Owner on 2026-10-18 ({@code docs/design/PACKET_ANALYSIS_HOP_VERDICT_PROPOSAL.md},
 * family C "breadth", recommended values; {@code PO_DECISION_RECORD_2026_10_18_PACKET_ANALYSIS_HOP_VERDICT.md}).
 *
 * <p>A hop is a pair of neighbouring capture points. Each of its signals (forward and reverse transit of the same packet,
 * the request RX and response TX components of matched cycles) is judged on its mean before and after the compared
 * window, and the signals are combined: one delayed signal marks the hop {@code gecikme burada}; {@code temiz} needs every
 * judged signal clean; anything else is {@code belirsiz}. A signal is judged only when it has at least
 * {@link #MIN_SAMPLES} samples in both windows.
 *
 * <p>Pure and deterministic: every threshold comparison on the means and shares is exact integer arithmetic on the
 * engine's integer sums (nanoseconds or microseconds), so no floating-point rounding decides a boundary; only the clock
 * comparison uses the engine's uncertainty, which is itself a floating-point value.
 */
final class HopVerdict {
    /** X: the mean must grow by at least this much (ms); also the "late" distance above the earlier median. */
    static final double DELAY_MS = 5.0;
    /** Y: the later mean must be at least this many times the earlier mean. */
    static final long RATIO = 2;
    /** N: samples needed in each window, and late samples needed in the later window. */
    static final long MIN_SAMPLES = 10;
    /** Bf: growth of the late share needed for a delay, and the growth a clean hop must stay below (1 point = 1/100). */
    static final long SHARE_GROWTH_PER_HUNDRED = 1;
    /** K: the increase must exceed this multiple of the hop's clock uncertainty, and "temiz" needs K·u below X. */
    static final double CLOCK_FACTOR = 2.0;
    /** Z: "temiz" needs the mean to move by less than this (ms); also the late distance for the clean share test. */
    static final double CLEAN_MS = 1.0;
    /**
     * The earlier mean is taken as at least this (ms) when the ratio is formed, so a near-zero or negative earlier mean
     * (clock offset residue) does not make the ratio undefined. The value of the proposal's evaluation tooling
     * ({@code R0 = 0.1}); with a required increase of 5 ms it only ever makes the ratio condition easier to meet.
     */
    static final double RATIO_FLOOR_MS = 0.1;

    enum Mark {
        DELAY("gecikme burada"), CLEAN("temiz"), UNDECIDED("belirsiz"), NO_DATA("ölçüm yok");

        final String word;

        Mark(String word) {
            this.word = word;
        }

        /** The word printed on the chain strip; none for belirsiz and ölçüm yok. */
        String stripWord() {
            return this == DELAY || this == CLEAN ? word : null;
        }
    }

    enum Kind {
        FORWARD_TRANSIT("ileri yön geçiş süresi"), REVERSE_TRANSIT("geri yön geçiş süresi"), RX("istek RX bileşeni"),
        TX("cevap TX bileşeni");

        final String text;

        Kind(String text) {
            this.text = text;
        }
    }

    /**
     * Why a hop is belirsiz, in the order of the proposal's section 8 lines; the hop shows the first that applies. The
     * entries marked "added" are situations the proposal's seven lines do not name; each sits next to the line it
     * refines.
     */
    enum Reason {
        NO_WINDOW,       // line 1
        FEW_SAMPLES,     // line 2
        CLOCK_FLOOR,     // line 3: K·u >= X, a change of X cannot be seen
        CLOCK_UNKNOWN,   // added (line 3): the engine has no uncertainty for the hop
        BETWEEN,         // line 4: Z <= |Δ| < X (or a decrease smaller than X)
        LATE_SHARE,      // added (line 4): |Δ| < Z but the share above median + Z grew by Bf or more
        BREADTH,         // line 5: too few late packets, or their share grew too little
        RATIO,           // added (line 5): Δ >= X but the later mean is below Y times the earlier
        DECREASE,        // line 6: Δ <= -X
        CLOCK_INCREASE   // line 7: Δ >= X but not more than K·u
    }

    /**
     * One signal of a hop: per window (0 = before, 1 = after) the sample count and exact integer sum in {@code unit}
     * units per ms, and the counts of samples at least X ({@code lateDelay}) and Z ({@code lateClean}) above the before
     * window's median.
     */
    record Signal(Kind kind, double unit, long[] n, long[] sum, long[] lateDelay, long[] lateClean, Double uncertaintyMs) {
        boolean usable() {
            return n[0] >= MIN_SAMPLES && n[1] >= MIN_SAMPLES;
        }

        boolean any() {
            return n[0] > 0 || n[1] > 0;
        }

        double mean(int i) {
            return sum[i] / (double) n[i] / unit;
        }

        double delta() {
            return mean(1) - mean(0);
        }

        /** The ratio the rule tests: after / max(before, {@link #RATIO_FLOOR_MS}). */
        double ratio() {
            return mean(1) / Math.max(mean(0), RATIO_FLOOR_MS);
        }

        double share(long[] late, int i) {
            return n[i] == 0 ? 0 : late[i] / (double) n[i];
        }
    }

    record Judged(Signal signal, Mark mark, Reason reason) {
    }

    /** One hop's marker and the reason line printed with it. */
    record Result(Mark mark, Reason reason, String text) {
    }

    private HopVerdict() {
    }

    // ---------------------------------------------------------------------------------------------- one signal

    /** Judges a usable signal; the caller drops signals that are not {@link Signal#usable()}. */
    static Judged judge(Signal s) {
        long scale = units(s.unit());
        BigInteger n0 = BigInteger.valueOf(s.n()[0]);
        BigInteger n1 = BigInteger.valueOf(s.n()[1]);
        // Δ · n0 · n1 · unit = sum1·n0 − sum0·n1, exactly
        BigInteger deltaScaled = BigInteger.valueOf(s.sum()[1]).multiply(n0).subtract(BigInteger.valueOf(s.sum()[0]).multiply(n1));
        BigInteger both = n0.multiply(n1);
        BigInteger x = both.multiply(BigInteger.valueOf(Math.round(DELAY_MS * scale)));
        BigInteger z = both.multiply(BigInteger.valueOf(Math.round(CLEAN_MS * scale)));
        double delta = s.delta();
        Double u = s.uncertaintyMs();
        boolean increase = deltaScaled.compareTo(x) >= 0;
        boolean ratio = ratioMet(s, scale);
        boolean clockSeesIncrease = u != null && delta > CLOCK_FACTOR * u;
        boolean breadth = s.lateDelay()[1] >= MIN_SAMPLES && shareGrowthAtLeastOnePoint(s, s.lateDelay());
        if (increase && ratio && clockSeesIncrease && breadth) {
            return new Judged(s, Mark.DELAY, null);
        }
        boolean small = deltaScaled.abs().compareTo(z) < 0;
        boolean clockCanSeeX = u != null && CLOCK_FACTOR * u < DELAY_MS;
        boolean cleanShare = !shareGrowthAtLeastOnePoint(s, s.lateClean());
        if (small && clockCanSeeX && cleanShare) {
            return new Judged(s, Mark.CLEAN, null);
        }
        Reason reason;
        if (increase) {
            if (u == null) {
                reason = Reason.CLOCK_UNKNOWN;
            } else if (!clockSeesIncrease) {
                reason = Reason.CLOCK_INCREASE;
            } else if (!ratio) {
                reason = Reason.RATIO;
            } else {
                reason = Reason.BREADTH;
            }
        } else if (deltaScaled.negate().compareTo(x) >= 0) {
            reason = Reason.DECREASE;
        } else if (!small) {
            reason = Reason.BETWEEN;
        } else if (u == null) {
            reason = Reason.CLOCK_UNKNOWN;
        } else if (!clockCanSeeX) {
            reason = Reason.CLOCK_FLOOR;
        } else {
            reason = Reason.LATE_SHARE;
        }
        return new Judged(s, Mark.UNDECIDED, reason);
    }

    /** after ≥ Y · max(before, floor), exactly: sum1·n0 ≥ Y·sum0·n1, or after ≥ Y·floor when before is below the floor. */
    private static boolean ratioMet(Signal s, long scale) {
        BigInteger n0 = BigInteger.valueOf(s.n()[0]);
        BigInteger n1 = BigInteger.valueOf(s.n()[1]);
        BigInteger sum0 = BigInteger.valueOf(s.sum()[0]);
        BigInteger sum1 = BigInteger.valueOf(s.sum()[1]);
        BigInteger floorUnits = BigInteger.valueOf(Math.round(RATIO_FLOOR_MS * scale));
        boolean belowFloor = sum0.compareTo(floorUnits.multiply(n0)) < 0;
        BigInteger y = BigInteger.valueOf(RATIO);
        if (belowFloor) {
            return sum1.compareTo(y.multiply(floorUnits).multiply(n1)) >= 0;
        }
        return sum1.multiply(n0).compareTo(y.multiply(sum0).multiply(n1)) >= 0;
    }

    /** late1/n1 − late0/n0 ≥ 1/100, exactly: 100·(late1·n0 − late0·n1) ≥ n0·n1. */
    private static boolean shareGrowthAtLeastOnePoint(Signal s, long[] late) {
        BigInteger n0 = BigInteger.valueOf(s.n()[0]);
        BigInteger n1 = BigInteger.valueOf(s.n()[1]);
        BigInteger growth = BigInteger.valueOf(late[1]).multiply(n0).subtract(BigInteger.valueOf(late[0]).multiply(n1))
                .multiply(BigInteger.valueOf(100 / SHARE_GROWTH_PER_HUNDRED));
        return growth.compareTo(n0.multiply(n1)) >= 0;
    }

    private static long units(double unit) {
        long scale = Math.round(unit);
        if (scale <= 0 || scale != unit || scale % 10 != 0) {
            throw new IllegalArgumentException("unit must be a whole number of units per ms divisible by 10: " + unit);
        }
        return scale;
    }

    // ---------------------------------------------------------------------------------------------- one hop

    /**
     * The hop's marker from its signals (in display order), whether the report has a before/after split, and the
     * unmatched-observation share per window (null when the hop has no match-coverage rows).
     */
    static Result hop(List<Signal> signals, boolean split, double[] unmatchedShare) {
        boolean anySample = signals.stream().anyMatch(Signal::any);
        if (!anySample) {
            return new Result(Mark.NO_DATA, null, "ölçüm yok: bu hop'ta karşılaştırılan aralıkta eşleşmiş paket yok.");
        }
        if (!split) {
            return undecided(Reason.NO_WINDOW, null);
        }
        List<Judged> judged = new ArrayList<>();
        for (Signal s : signals) {
            if (s.usable()) {
                judged.add(judge(s));
            }
        }
        if (judged.isEmpty()) {
            Signal most = null;
            for (Signal s : signals) {
                if (most == null || Math.min(s.n()[0], s.n()[1]) > Math.min(most.n()[0], most.n()[1])) {
                    most = s;
                }
            }
            return undecided(Reason.FEW_SAMPLES, most);
        }
        Judged delayed = null;
        for (Judged j : judged) {
            if (j.mark() == Mark.DELAY && (delayed == null || j.signal().delta() > delayed.signal().delta())) {
                delayed = j;
            }
        }
        if (delayed != null) {
            return new Result(Mark.DELAY, null, delayText(delayed.signal()));
        }
        if (judged.stream().allMatch(j -> j.mark() == Mark.CLEAN)) {
            return new Result(Mark.CLEAN, null, cleanText(judged, unmatchedShare));
        }
        Judged first = null;
        for (Judged j : judged) {
            if (j.mark() != Mark.CLEAN && (first == null || j.reason().ordinal() < first.reason().ordinal())) {
                first = j;
            }
        }
        return undecided(first.reason(), first.signal());
    }

    // ---------------------------------------------------------------------------------------------- wording

    private static String ms(double v) {
        return TrFormat.number(v, 2);
    }

    private static String signedMs(double v) {
        String text = TrFormat.number(Math.abs(v), 2);
        if (text.matches("0(,0+)?")) {
            return text;
        }
        return (v < 0 ? "-" : "+") + text;
    }

    private static String pct(double share) {
        return "%" + TrFormat.number(share * 100.0, 1);
    }

    private static String times(double ratio) {
        return TrFormat.number(ratio, Math.abs(ratio) >= 10 ? 0 : 1) + "×";
    }

    private static String uncertainty(Double u) {
        return u == null ? "bilinmiyor" : "±" + TrFormat.number(u, 2) + " ms";
    }

    private static String number(double v) {
        return TrFormat.number(v, v == Math.rint(v) ? 0 : 1);
    }

    static String delayText(Signal s) {
        return "gecikme burada (" + s.kind().text + "): ortalama " + ms(s.mean(0)) + " → " + ms(s.mean(1)) + " ms ("
                + signedMs(s.delta()) + " ms, " + times(s.ratio()) + "); önceki ortancadan " + number(DELAY_MS)
                + " ms fazla geç kalan paketler önce " + pct(s.share(s.lateDelay(), 0)) + ", sonra " + pct(s.share(s.lateDelay(), 1))
                + " (" + TrFormat.integer(s.lateDelay()[1]) + " paket); örnek sayısı " + TrFormat.integer(s.n()[0]) + " / "
                + TrFormat.integer(s.n()[1]) + "; hop saat belirsizliği " + uncertainty(s.uncertaintyMs()) + ".";
    }

    private static String kinds(List<Judged> judged) {
        List<Kind> kinds = judged.stream().map(j -> j.signal().kind()).toList();
        List<String> parts = new ArrayList<>();
        if (kinds.contains(Kind.FORWARD_TRANSIT) && kinds.contains(Kind.REVERSE_TRANSIT)) {
            parts.add("ileri ve geri yön geçiş süresi");
        } else if (kinds.contains(Kind.FORWARD_TRANSIT)) {
            parts.add(Kind.FORWARD_TRANSIT.text);
        } else if (kinds.contains(Kind.REVERSE_TRANSIT)) {
            parts.add(Kind.REVERSE_TRANSIT.text);
        }
        if (kinds.contains(Kind.RX) && kinds.contains(Kind.TX)) {
            parts.add("istek RX ve cevap TX bileşeni");
        } else if (kinds.contains(Kind.RX)) {
            parts.add(Kind.RX.text);
        } else if (kinds.contains(Kind.TX)) {
            parts.add(Kind.TX.text);
        }
        return String.join(", ", parts);
    }

    static String cleanText(List<Judged> judged, double[] unmatchedShare) {
        // the signal whose mean moved most (in either direction) is the one the margin was closest to binding on
        Signal widest = judged.get(0).signal();
        for (Judged j : judged) {
            if (Math.abs(j.signal().delta()) > Math.abs(widest.delta())) {
                widest = j.signal();
            }
        }
        double growth = 0;
        double before = 0;
        double after = 0;
        for (Judged j : judged) {
            Signal s = j.signal();
            double g = s.share(s.lateClean(), 1) - s.share(s.lateClean(), 0);
            if (g > growth) {
                growth = g;
                before = s.share(s.lateClean(), 0);
                after = s.share(s.lateClean(), 1);
            }
        }
        String share = growth <= 0 ? "geç kalan paket payı artmadı"
                : "önceki ortancadan " + number(CLEAN_MS) + " ms fazla geç kalan paket payı " + pct(before) + " → " + pct(after)
                        + " (1 puandan az)";
        String unmatched = unmatchedShare == null ? ""
                : "; eşleşmeyen gözlem önce " + pct(unmatchedShare[0]) + ", sonra " + pct(unmatchedShare[1])
                        + " (bu oran tek başına kayıp anlamına gelmez)";
        return "temiz (" + kinds(judged) + "): ortalama fark " + signedMs(widest.delta()) + " ms (sınır ±" + number(CLEAN_MS)
                + " ms); " + share + "; örnek sayısı " + TrFormat.integer(widest.n()[0]) + " / " + TrFormat.integer(widest.n()[1])
                + unmatched + ".";
    }

    static Result undecided(Reason reason, Signal s) {
        String x = number(DELAY_MS);
        String z = number(CLEAN_MS);
        String k = number(CLOCK_FACTOR);
        String text = switch (reason) {
            case NO_WINDOW -> "belirsiz: önce/sonra karşılaştırması yok (pencere verilmedi, motor olay başlangıcı bulamadı).";
            case FEW_SAMPLES -> "belirsiz: örnek yetersiz; önce " + TrFormat.integer(s.n()[0]) + ", sonra "
                    + TrFormat.integer(s.n()[1]) + " (en az " + MIN_SAMPLES + " gerekir).";
            case CLOCK_FLOOR -> "belirsiz: saat belirsizliği ±" + TrFormat.number(s.uncertaintyMs(), 1) + " ms; " + k + "× = "
                    + TrFormat.number(CLOCK_FACTOR * s.uncertaintyMs(), 1) + " ms, " + x + " ms'lik bir değişim bu hop'ta ölçülemez.";
            case CLOCK_UNKNOWN -> "belirsiz: hop saat belirsizliği bilinmiyor; " + x + " ms'lik bir değişimin bu hop'ta "
                    + "ölçülebildiği gösterilemez (" + s.kind().text + ", ortalama " + signedMs(s.delta()) + " ms).";
            case BETWEEN -> "belirsiz: ortalama " + signedMs(s.delta()) + " ms değişti; temiz sınırının (" + z
                    + " ms) üstünde, gecikme eşiğinin (" + x + " ms) altında.";
            case LATE_SHARE -> "belirsiz: ortalama fark " + signedMs(s.delta()) + " ms (sınır ±" + z + " ms) ama önceki "
                    + "ortancadan " + z + " ms fazla geç kalan paket payı " + pct(s.share(s.lateClean(), 0)) + " → "
                    + pct(s.share(s.lateClean(), 1)) + " arttı; temiz için artış 1 puandan az olmalı.";
            case BREADTH -> "belirsiz: ortalama " + signedMs(s.delta()) + " ms arttı ama yalnız "
                    + TrFormat.integer(s.lateDelay()[1]) + " paket (" + pct(s.share(s.lateDelay(), 1)) + "; önce "
                    + pct(s.share(s.lateDelay(), 0)) + ") önceki ortancadan " + x + " ms fazla geç kaldı; en az " + MIN_SAMPLES
                    + " paket ve payın 1 puan artması gerekir.";
            case RATIO -> "belirsiz: ortalama " + signedMs(s.delta()) + " ms arttı ama önceki ortalamanın " + RATIO
                    + " katına ulaşmadı (" + ms(s.mean(0)) + " → " + ms(s.mean(1)) + " ms, " + times(s.ratio()) + ").";
            case DECREASE -> "belirsiz: ortalama " + signedMs(s.delta()) + " ms azaldı; önceki pencerede gecikme vardı.";
            case CLOCK_INCREASE -> "belirsiz: artış " + signedMs(s.delta()) + " ms, hop saat belirsizliğinin " + k
                    + " katından (" + TrFormat.number(CLOCK_FACTOR * s.uncertaintyMs(), 1) + " ms) büyük değil.";
        };
        return new Result(Mark.UNDECIDED, reason, text);
    }
}
