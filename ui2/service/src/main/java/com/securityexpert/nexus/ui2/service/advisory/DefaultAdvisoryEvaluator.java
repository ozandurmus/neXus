package com.securityexpert.nexus.ui2.service.advisory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Contract section 7. Order: {@code NOT_EVALUABLE} (version missing or unparsed, or the advisory needs review and names the
 * device's product), then the rules of the device's train, and when those say nothing the rules with train {@code *}.
 *
 * <p>Within a {@link AdvisoryModel.Range} of PAN-OS, a change point whose maintenance release equals the device's decides by
 * hotfix whatever the range bounds say (below the first point the range's status, at or above a point that point's status);
 * otherwise the linear range decides, whose upper bound of an affected range is lifted to the highest unaffected change point
 * (the vendor names the first fix as the bound and the backported fixes of later releases as points). Strength of what a rule can say, highest first: AFFECTED, a vendor-named unaffected
 * statement (whole train, unaffected range), FIXED (outside an affected range at or above a bound or point the vendor names),
 * and a version below the affected range or outside an exact list (a weak NOT_AFFECTED). When the train has rules but none
 * speaks about the version, the answer is {@code NOT_EVALUABLE} with {@code NO_RULE_COVERS_VERSION}: never green by default.
 * An advisory that has no rule for the product is {@code NOT_LISTED} whatever the device's version is: the contract's "no
 * verdict" row (the product is not named, so the version cannot matter).
 */
public final class DefaultAdvisoryEvaluator implements AdvisoryEvaluator {

    private static final int RANK_AFFECTED = 5;
    private static final int RANK_NAMED_UNAFFECTED = 4;
    private static final int RANK_FIXED = 3;
    private static final int RANK_WEAK = 2;

    private record Hit(int rank, AdvisoryModel.VerdictKind kind, String basis) {
    }

    private final Map<String, ProductComparator> comparators = new HashMap<>();

    public DefaultAdvisoryEvaluator(Collection<? extends ProductComparator> comparators) {
        for (ProductComparator c : comparators) {
            this.comparators.put(c.productKey(), c);
        }
    }

    @Override
    public AdvisoryModel.Verdict evaluate(String productKey, String storedVersion, AdvisoryModel.ParseState advisoryState,
            List<AdvisoryModel.ProductRule> rulesForProduct) {
        try {
            return doEvaluate(productKey, storedVersion, advisoryState, rulesForProduct);
        } catch (RuntimeException e) {
            return new AdvisoryModel.Verdict(AdvisoryModel.VerdictKind.NOT_EVALUABLE, "EVALUATION_ERROR", null);
        }
    }

    private AdvisoryModel.Verdict doEvaluate(String productKey, String storedVersion, AdvisoryModel.ParseState state,
            List<AdvisoryModel.ProductRule> productRules) {
        List<AdvisoryModel.ProductRule> rules = productRules == null ? List.of() : productRules;
        if (rules.isEmpty()) {
            return verdict(AdvisoryModel.VerdictKind.NOT_LISTED, "PRODUCT_NOT_NAMED", null);
        }
        ProductComparator cmp = productKey == null ? null : comparators.get(productKey);
        if (cmp == null) {
            return verdict(AdvisoryModel.VerdictKind.NOT_EVALUABLE, "NO_COMPARATOR", null);
        }
        Optional<String> canonical = storedVersion == null ? Optional.empty() : cmp.canonical(storedVersion);
        Optional<String> train = canonical.flatMap(cmp::train);
        if (canonical.isEmpty() || train.isEmpty()) {
            return verdict(AdvisoryModel.VerdictKind.NOT_EVALUABLE, "VERSION_UNPARSED", null);
        }
        if (state == AdvisoryModel.ParseState.NEEDS_REVIEW) {
            return verdict(AdvisoryModel.VerdictKind.NOT_EVALUABLE, "ADVISORY_NEEDS_REVIEW", null);
        }
        List<AdvisoryModel.ProductRule> own = new ArrayList<>();
        List<AdvisoryModel.ProductRule> star = new ArrayList<>();
        for (AdvisoryModel.ProductRule r : rules) {
            if (train.get().equals(r.train())) {
                own.add(r);
            } else if (AdvisoryModel.ALL_TRAINS.equals(r.train())) {
                star.add(r);
            }
        }
        if (own.isEmpty() && star.isEmpty()) {
            return verdict(AdvisoryModel.VerdictKind.NOT_LISTED, "TRAIN_NOT_NAMED", null);
        }
        boolean panOs = AdvisoryModel.PRODUCT_PAN_OS.equals(productKey);
        Hit best = best(own, canonical.get(), cmp, panOs);
        List<AdvisoryModel.ProductRule> deciding = own;
        if (best == null) {
            best = best(star, canonical.get(), cmp, panOs);
            deciding = star;
        }
        if (best == null) {
            return verdict(AdvisoryModel.VerdictKind.NOT_EVALUABLE, "NO_RULE_COVERS_VERSION", fixedText(own, star));
        }
        return verdict(best.kind(), best.basis(), fixedText(deciding, own.isEmpty() ? star : own));
    }

    private static String fixedText(List<AdvisoryModel.ProductRule> first, List<AdvisoryModel.ProductRule> second) {
        for (AdvisoryModel.ProductRule r : first) {
            if (r.fixedText() != null) {
                return r.fixedText();
            }
        }
        for (AdvisoryModel.ProductRule r : second) {
            if (r.fixedText() != null) {
                return r.fixedText();
            }
        }
        return null;
    }

    private static AdvisoryModel.Verdict verdict(AdvisoryModel.VerdictKind kind, String basis, String fixedText) {
        return new AdvisoryModel.Verdict(kind, basis, fixedText);
    }

    private static Hit best(List<AdvisoryModel.ProductRule> productRules, String version, ProductComparator cmp, boolean panOs) {
        Hit best = null;
        for (AdvisoryModel.ProductRule pr : productRules) {
            for (AdvisoryModel.VersionRule rule : pr.rules()) {
                Hit h = switch (rule) {
                    case AdvisoryModel.WholeTrain w -> w.status() == AdvisoryModel.Status.AFFECTED
                            ? new Hit(RANK_AFFECTED, AdvisoryModel.VerdictKind.AFFECTED, "TRAIN_AFFECTED")
                            : new Hit(RANK_NAMED_UNAFFECTED, AdvisoryModel.VerdictKind.NOT_AFFECTED, "TRAIN_UNAFFECTED");
                    case AdvisoryModel.ExactVersions e -> exact(e, version);
                    case AdvisoryModel.Range r -> range(r, version, cmp, panOs);
                };
                if (h != null && (best == null || h.rank() > best.rank())) {
                    best = h;
                }
            }
        }
        return best;
    }

    private static Hit exact(AdvisoryModel.ExactVersions e, String version) {
        boolean listed = e.versions().contains(version);
        if (e.status() == AdvisoryModel.Status.AFFECTED) {
            return listed ? new Hit(RANK_AFFECTED, AdvisoryModel.VerdictKind.AFFECTED, "EXACT_VERSION_LISTED")
                    : new Hit(RANK_WEAK, AdvisoryModel.VerdictKind.NOT_AFFECTED, "VERSION_NOT_LISTED_IN_TRAIN");
        }
        return listed ? new Hit(RANK_NAMED_UNAFFECTED, AdvisoryModel.VerdictKind.NOT_AFFECTED, "EXACT_VERSION_UNAFFECTED") : null;
    }

    private static Hit range(AdvisoryModel.Range r, String version, ProductComparator cmp, boolean panOs) {
        if (panOs && !r.changes().isEmpty()) {
            PanOsVersion dv = PanOsVersion.parse(version).orElseThrow();
            boolean any = false;
            int bestHotfix = -1;
            AdvisoryModel.Status viaPoint = null;
            for (AdvisoryModel.ChangePoint cp : r.changes()) {
                PanOsVersion p = PanOsVersion.parse(cp.at()).orElse(null);
                if (p == null || !p.sameMaintenanceRelease(dv)) {
                    continue;
                }
                any = true;
                if (p.hotfix() <= dv.hotfix() && p.hotfix() >= bestHotfix) {
                    bestHotfix = p.hotfix();
                    viaPoint = cp.status();
                }
            }
            if (any) {
                if (viaPoint == null) {
                    return r.status() == AdvisoryModel.Status.AFFECTED
                            ? new Hit(RANK_AFFECTED, AdvisoryModel.VerdictKind.AFFECTED, "BELOW_HOTFIX")
                            : new Hit(RANK_NAMED_UNAFFECTED, AdvisoryModel.VerdictKind.NOT_AFFECTED, "BELOW_HOTFIX_UNAFFECTED");
                }
                return viaPoint == AdvisoryModel.Status.AFFECTED
                        ? new Hit(RANK_AFFECTED, AdvisoryModel.VerdictKind.AFFECTED, "CHANGE_POINT_AFFECTED")
                        : new Hit(RANK_FIXED, AdvisoryModel.VerdictKind.FIXED, "AT_OR_ABOVE_FIX");
            }
        }
        String to = r.to();
        boolean toInclusive = r.toInclusive();
        if (panOs && r.status() == AdvisoryModel.Status.AFFECTED) {
            // The vendor names the first fix as the bound and the later per-release fixes as change points; releases
            // in between that have no point of their own stay affected until the highest fix (checked against the vendor's own
            // lists of affected versions, contract section 6 self-check). A range without a bound ends at its highest fix
            // point for the same reason: the vendor's lists never name a release above it as affected.
            for (AdvisoryModel.ChangePoint cp : r.changes()) {
                if (cp.status() == AdvisoryModel.Status.UNAFFECTED && PanOsVersion.parse(cp.at()).isPresent()
                        && (to == null || cmp.compare(cp.at(), to) >= 0)) {
                    to = cp.at();
                    toInclusive = false;
                }
            }
        }
        boolean aboveFrom = r.from() == null || cmp.compare(version, r.from()) >= 0;
        boolean belowTo = true;
        if (to != null) {
            int c = cmp.compare(version, to);
            belowTo = toInclusive ? c <= 0 : c < 0;
        }
        if (aboveFrom && belowTo) {
            return r.status() == AdvisoryModel.Status.AFFECTED
                    ? new Hit(RANK_AFFECTED, AdvisoryModel.VerdictKind.AFFECTED, "IN_RANGE")
                    : new Hit(RANK_NAMED_UNAFFECTED, AdvisoryModel.VerdictKind.NOT_AFFECTED, "IN_UNAFFECTED_RANGE");
        }
        if (r.status() == AdvisoryModel.Status.AFFECTED) {
            return aboveFrom
                    ? new Hit(RANK_FIXED, AdvisoryModel.VerdictKind.FIXED, "AT_OR_ABOVE_FIX")
                    : new Hit(RANK_WEAK, AdvisoryModel.VerdictKind.NOT_AFFECTED, "BELOW_AFFECTED_RANGE");
        }
        return null;
    }
}
