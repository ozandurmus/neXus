package com.securityexpert.nexus.ui2.service.logdetect;

/**
 * The layer-B score (LOG_DETECTION_ENGINE_DESIGN_2026_11_24.md section 4.4, PO decision 2026-11-24 question 3):
 * {@code score = 100 x S x R x C}, used to order findings only, never to set severity or to decide that a finding exists.
 *
 * <ul>
 *   <li>R, fleet rarity: {@code ln((N + 1) / (n + 1)) / ln(N + 1)}, an inverse-document-frequency weight over devices;
 *       a template on no other device has R = 1. With no cohort ({@code N = 0}) there is no fleet evidence and R = 1.</li>
 *   <li>C, change on this device: {@code max(C_new, C_rate, C_boot, C_silence, 0.1)} with
 *       {@code C_rate = clip((z - 3) / 7, 0, 1)}, {@code z = (x - m) / sigma},
 *       {@code sigma = max(1.4826 x MAD, sqrt(m), 1)} (the scale floor, Argon finding 7).</li>
 * </ul>
 */
public final class LayerBScore {
    /** 1.4826 x MAD is the consistent estimator of the standard deviation under normality (Rousseeuw and Croux 1993). */
    public static final double MAD_SCALE = 1.4826;
    public static final double C_FLOOR = 0.1;

    private LayerBScore() {
    }

    public static double rarity(int cohort, int prevalence) {
        if (cohort <= 0) return 1.0;
        int n = Math.max(0, Math.min(prevalence, cohort));
        double r = Math.log((cohort + 1.0) / (n + 1.0)) / Math.log(cohort + 1.0);
        return Math.max(0.0, Math.min(1.0, r));
    }

    public static double sigma(double median, double mad) {
        return Math.max(Math.max(MAD_SCALE * mad, Math.sqrt(Math.max(0, median))), 1.0);
    }

    public static double z(double x, double median, double mad) {
        return (x - median) / sigma(median, mad);
    }

    public static double rateChange(double z) {
        return Math.max(0.0, Math.min(1.0, (z - 3.0) / 7.0));
    }

    public static double change(boolean newOnDevice, double cRate, boolean bootBurst, boolean silence) {
        double c = C_FLOOR;
        if (newOnDevice) c = 1.0;
        if (bootBurst) c = 1.0;
        if (silence) c = 1.0;
        return Math.max(c, Math.max(0.0, Math.min(1.0, cRate)));
    }

    public static double score(double s, double r, double c) {
        double v = 100.0 * s * r * c;
        return Math.max(0.0, Math.min(100.0, v));
    }
}
