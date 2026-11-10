package com.securityexpert.nexus.ui2.service.advisory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.ChangePoint;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.ParseState;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.ProductRule;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.Range;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.Status;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.VerdictKind;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.WholeTrain;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultAdvisoryEvaluatorTest {

    private final AdvisoryEvaluator ev = new DefaultAdvisoryCore(List.of()).evaluator();
    private static final String PAN = AdvisoryModel.PRODUCT_PAN_OS;

    private static ChangePoint fix(String at) {
        return new ChangePoint(at, Status.UNAFFECTED);
    }

    private AdvisoryModel.Verdict pan(String version, ProductRule... rules) {
        return ev.evaluate(PAN, version, ParseState.PARSED, List.of(rules));
    }

    /** Affected up to 11.1.7, with backported fixes 11.1.4-h13 and 11.1.6-h1 and the plain release 11.1.7. */
    private static ProductRule train111() {
        return new ProductRule(PAN, "11.1", List.of(new Range(Status.AFFECTED, null, "11.1.7", false,
                List.of(fix("11.1.7"), fix("11.1.6-h1"), fix("11.1.4-h13")))), "11.1.4-h13, 11.1.6-h1, 11.1.7");
    }

    @Test
    void changePointDecidesByHotfixInsideItsMaintenanceRelease() {
        assertEquals(new AdvisoryModel.Verdict(VerdictKind.AFFECTED, "BELOW_HOTFIX", "11.1.4-h13, 11.1.6-h1, 11.1.7"), pan("11.1.4-h12", train111()));
        assertEquals(VerdictKind.AFFECTED, pan("11.1.4", train111()).kind());
        assertEquals(VerdictKind.FIXED, pan("11.1.4-h13", train111()).kind());
        assertEquals(VerdictKind.FIXED, pan("11.1.4-h20", train111()).kind());
        assertEquals(VerdictKind.AFFECTED, pan("11.1.5", train111()).kind(), "no point in this release: the range decides");
        assertEquals(VerdictKind.AFFECTED, pan("11.1.6", train111()).kind());
        assertEquals(VerdictKind.FIXED, pan("11.1.6-h1", train111()).kind());
        assertEquals(VerdictKind.FIXED, pan("11.1.9", train111()).kind());
    }

    @Test
    void hotfixPointDecidesRegardlessOfRangeBound() {
        ProductRule r = new ProductRule(PAN, "10.2", List.of(new Range(Status.AFFECTED, "10.2.0", "10.2.3", false,
                List.of(fix("10.2.8-h2")))), null);
        assertEquals(VerdictKind.AFFECTED, pan("10.2.8", r).kind());
        assertEquals(VerdictKind.FIXED, pan("10.2.8-h2", r).kind());
        assertEquals(VerdictKind.AFFECTED, pan("10.2.3", r).kind(), "bound lifted to the highest change point");
        assertEquals(VerdictKind.FIXED, pan("10.2.9", r).kind());
    }

    @Test
    void rangeInclusiveAndBelowFrom() {
        ProductRule r = new ProductRule(PAN, "7.1", List.of(new Range(Status.AFFECTED, "7.1.2", "7.1.24", true, List.of())), null);
        assertEquals(VerdictKind.NOT_AFFECTED, pan("7.1.1", r).kind());
        assertEquals("BELOW_AFFECTED_RANGE", pan("7.1.1", r).basis());
        assertEquals(VerdictKind.AFFECTED, pan("7.1.2", r).kind());
        assertEquals(VerdictKind.AFFECTED, pan("7.1.24", r).kind());
        assertEquals(VerdictKind.FIXED, pan("7.1.24-h1", r).kind());
    }

    @Test
    void wholeTrainAndStarRules() {
        ProductRule unaffected = new ProductRule(PAN, "11.0", List.of(new WholeTrain(Status.UNAFFECTED)), null);
        ProductRule allAffected = new ProductRule(PAN, "*", List.of(new WholeTrain(Status.AFFECTED)), null);
        assertEquals(new AdvisoryModel.Verdict(VerdictKind.NOT_AFFECTED, "TRAIN_UNAFFECTED", null), pan("11.0.3", unaffected));
        assertEquals(VerdictKind.AFFECTED, pan("11.0.3", allAffected).kind());
        assertEquals(VerdictKind.NOT_AFFECTED, pan("11.0.3", unaffected, allAffected).kind(), "the device's train wins over *");
        assertEquals(VerdictKind.AFFECTED, pan("9.1.1", unaffected, allAffected).kind());
        assertEquals(new AdvisoryModel.Verdict(VerdictKind.NOT_LISTED, "TRAIN_NOT_NAMED", null), pan("9.1.1", unaffected));
    }

    @Test
    void notEvaluableCases() {
        assertEquals("VERSION_UNPARSED", pan(null, train111()).basis());
        assertEquals(VerdictKind.NOT_EVALUABLE, pan("11.1.10-c12", train111()).kind());
        assertEquals(VerdictKind.NOT_EVALUABLE, pan("", train111()).kind());
        AdvisoryModel.Verdict review = ev.evaluate(PAN, "11.1.4", ParseState.NEEDS_REVIEW, List.of(train111()));
        assertEquals(new AdvisoryModel.Verdict(VerdictKind.NOT_EVALUABLE, "ADVISORY_NEEDS_REVIEW", null), review);
        ProductRule placeholder = new ProductRule(PAN, "*", List.of(), null);
        assertEquals(VerdictKind.NOT_EVALUABLE, ev.evaluate(PAN, "11.1.4", ParseState.NEEDS_REVIEW, List.of(placeholder)).kind());
        assertEquals(VerdictKind.NOT_EVALUABLE, ev.evaluate("unknown:product", "1.2.3", ParseState.PARSED, List.of(placeholder)).kind());
        ProductRule onlyUnaffectedRange = new ProductRule(PAN, "7.0", List.of(new Range(Status.UNAFFECTED, "7.0.18", null, false, List.of())), null);
        assertEquals("NO_RULE_COVERS_VERSION", pan("7.0.17", onlyUnaffectedRange).basis(), "never green by default");
        assertEquals(VerdictKind.NOT_AFFECTED, pan("7.0.18", onlyUnaffectedRange).kind());
    }

    @Test
    void productNotNamedIsNoVerdict() {
        assertEquals(VerdictKind.NOT_LISTED, ev.evaluate(PAN, "11.1.4", ParseState.PARSED, List.of()).kind());
        assertEquals(VerdictKind.NOT_LISTED, ev.evaluate(PAN, "garbage", ParseState.NEEDS_REVIEW, List.of()).kind());
    }

    @Test
    void exactVersionsForCisco() {
        ProductRule r = new ProductRule(AdvisoryModel.PRODUCT_CISCO_ASA, "9.18",
                List.of(new AdvisoryModel.ExactVersions(Status.AFFECTED, Set.of("9.18.4", "9.18.4.50"))), null);
        String asa = AdvisoryModel.PRODUCT_CISCO_ASA;
        assertEquals(new AdvisoryModel.Verdict(VerdictKind.AFFECTED, "EXACT_VERSION_LISTED", null), ev.evaluate(asa, "9.18(4)50", ParseState.PARSED, List.of(r)));
        assertEquals(VerdictKind.AFFECTED, ev.evaluate(asa, "9.18(4)", ParseState.PARSED, List.of(r)).kind());
        assertEquals(new AdvisoryModel.Verdict(VerdictKind.NOT_AFFECTED, "VERSION_NOT_LISTED_IN_TRAIN", null), ev.evaluate(asa, "9.18(4)51", ParseState.PARSED, List.of(r)));
        assertEquals(VerdictKind.NOT_LISTED, ev.evaluate(asa, "9.16(4)", ParseState.PARSED, List.of(r)).kind());
    }

    @Test
    void fortinetRangeUsesCanonicalVersion() {
        ProductRule r = new ProductRule(AdvisoryModel.PRODUCT_FORTIOS, "7.4", List.of(new Range(Status.AFFECTED, "7.4.0", "7.4.7", false, List.of())), "7.4.7");
        assertEquals(VerdictKind.AFFECTED, ev.evaluate(AdvisoryModel.PRODUCT_FORTIOS, "v7.4.6 build2500", ParseState.PARSED, List.of(r)).kind());
        assertEquals(new AdvisoryModel.Verdict(VerdictKind.FIXED, "AT_OR_ABOVE_FIX", "7.4.7"), ev.evaluate(AdvisoryModel.PRODUCT_FORTIOS, "v7.4.12 build2902", ParseState.PARSED, List.of(r)));
    }
}
