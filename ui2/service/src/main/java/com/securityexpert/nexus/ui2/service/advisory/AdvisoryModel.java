package com.securityexpert.nexus.ui2.service.advisory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * The normalised advisory model (contract sections 5 to 7). Versions inside rules are strings in the product's own
 * canonical form (the form the product's {@link ProductComparator} parses); a rule never holds a guessed version.
 */
public final class AdvisoryModel {

    private AdvisoryModel() {
    }

    /** Source keys (contract section 2). */
    public static final String SOURCE_PALOALTO = "paloalto";
    public static final String SOURCE_FORTINET = "fortinet";
    public static final String SOURCE_CISCO = "cisco";
    public static final String SOURCE_CISA_KEV = "cisa_kev";
    /** Amendment A-2026-11-13. */
    public static final String SOURCE_CHECKPOINT = "checkpoint";

    /** Product keys (contract section 6). */
    public static final String PRODUCT_PAN_OS = "paloalto:pan-os";
    public static final String PRODUCT_FORTIOS = "fortinet:fortios";
    public static final String PRODUCT_FORTIMANAGER = "fortinet:fortimanager";
    public static final String PRODUCT_CISCO_ASA = "cisco:asa";
    /** Check Point Gaia gateways (appliances, open servers, ClusterXL and VSX members), amendment A-2026-11-13. */
    public static final String PRODUCT_CP_GATEWAY = "checkpoint:gaia-gateway";
    /** Check Point Gaia management: Security Management Server and Multi-Domain Server, amendment A-2026-11-13. */
    public static final String PRODUCT_CP_MANAGEMENT = "checkpoint:gaia-management";

    /**
     * The bases of a verdict that waits for a fix rule a {@code security_admin} has not confirmed yet (PO decision
     * 2026-11-10, question 4): shown as pending, kept out of every summary count until the rule is confirmed.
     */
    public static final Set<String> PENDING_BASES = Set.of("FIX_RULE_UNCONFIRMED", "MULTIPLE_FIX_TAKES", "FIX_STATEMENT_UNCLEAR");

    /** The train value of a rule that covers the whole product. */
    public static final String ALL_TRAINS = "*";

    public enum Severity { CRITICAL, HIGH, MEDIUM, LOW, NONE, UNKNOWN }

    public enum ParseState { PARSED, NEEDS_REVIEW }

    /** What a rule says about the versions it covers. */
    public enum Status { AFFECTED, UNAFFECTED }

    /** One normalised statement about versions of one train. A train's rules are evaluated together (section 6). */
    public sealed interface VersionRule permits WholeTrain, Range, ExactVersions, JumboFix {
    }

    /** Every version of the train (or of the product, for the train {@link #ALL_TRAINS}) has this status. */
    public record WholeTrain(Status status) implements VersionRule {
    }

    /**
     * Versions from {@code from} (inclusive; null = start of the train) to {@code to} (null = end of the train) have
     * {@code status}; a change point overrides the range for its own maintenance release (Palo Alto: below the point's
     * hotfix the range status, at or above it the point's status).
     */
    public record Range(Status status, String from, String to, boolean toInclusive, List<ChangePoint> changes) implements VersionRule {
        public Range {
            changes = changes == null ? List.of() : List.copyOf(changes);
        }
    }

    /** From version {@code at} on, within the same maintenance release, the status is {@code status}. */
    public record ChangePoint(String at, Status status) {
    }

    /** Exactly these versions have this status (Cisco lists every affected release). */
    public record ExactVersions(Status status, Set<String> versions) implements VersionRule {
        public ExactVersions {
            versions = Set.copyOf(versions);
        }
    }

    /**
     * Check Point (amendment A-2026-11-13): a version above {@code clearFrom} (or at it, when {@code clearExclusive} is
     * false) is {@code FIXED}, because the vendor names that Jumbo take as carrying the fix or names every take above it as
     * unaffected; any other version is {@code NOT_EVALUABLE} with {@code pendingBasis}, because what the vendor published
     * does not say it is affected. {@code clearFrom} null: no take clears it. Never yields {@code AFFECTED}.
     */
    public record JumboFix(String clearFrom, boolean clearExclusive, String pendingBasis) implements VersionRule {
    }

    /**
     * One statement of a Check Point Jumbo "All Resolved Issues" page: the take of a train that names a CVE. {@code kind}
     * {@code FIX} when the line says the CVE was resolved or fixed, {@code MENTION} when it names the CVE in another way
     * ("Check Point response to ..."). {@code evidence} is the table line it was read from (public text, at most 300
     * characters).
     */
    public record JumboStatement(String train, int take, String cveId, String kind, String productText, String skRef,
            String evidence) {
    }

    /** The rules of one advisory for one product and train, with the vendor's fix statement for that train (or null). */
    public record ProductRule(String productKey, String train, List<VersionRule> rules, String fixedText) {
        public ProductRule {
            rules = List.copyOf(rules);
        }
    }

    /** One advisory as parsed from a source document. {@code parseNote} is a fixed code word, never document text. */
    public record ParsedAdvisory(String advisoryId, String source, String title, Severity severity, BigDecimal cvssScore,
            List<String> cveIds, LocalDate publishedOn, LocalDate updatedOn, String sourceUrl, ParseState parseState,
            String parseNote, List<ProductRule> rules) {
        public ParsedAdvisory {
            cveIds = cveIds == null ? List.of() : List.copyOf(cveIds);
            rules = rules == null ? List.of() : List.copyOf(rules);
        }
    }

    /** One entry of the known-exploited catalog. */
    public record KnownExploited(String cveId, LocalDate dateAdded, boolean ransomwareUse) {
    }

    /**
     * What one source document yielded. {@code followUps} are further document addresses named by this document (the
     * Fortinet feed names its CSAF files; the Cisco change list names files): data for the fetcher's own logic only, never
     * fetched by the service. {@code rejected} counts entries that could not be read at all.
     */
    public record ParseResult(List<ParsedAdvisory> advisories, List<KnownExploited> knownExploited, int rejected,
            String jumboTrain, List<JumboStatement> jumboStatements) {
        public ParseResult {
            advisories = List.copyOf(advisories);
            knownExploited = List.copyOf(knownExploited);
            jumboStatements = jumboStatements == null ? List.of() : List.copyOf(jumboStatements);
        }

        public ParseResult(List<ParsedAdvisory> advisories, List<KnownExploited> knownExploited, int rejected) {
            this(advisories, knownExploited, rejected, null, List.of());
        }

        public static ParseResult empty() {
            return new ParseResult(List.of(), List.of(), 0);
        }
    }

    public enum VerdictKind { AFFECTED, FIXED, NOT_AFFECTED, NOT_EVALUABLE, NOT_LISTED }

    /**
     * The verdict for one device and one advisory. {@code basis} is a fixed code word (for example {@code IN_RANGE},
     * {@code BELOW_HOTFIX}, {@code EXACT_VERSION_LISTED}, {@code AT_OR_ABOVE_FIX}, {@code TRAIN_UNAFFECTED},
     * {@code VERSION_UNPARSED}, {@code ADVISORY_NEEDS_REVIEW}, {@code TRAIN_NOT_NAMED}); {@code fixedText} is the vendor's
     * fix statement for the device's train, or null. A {@code NOT_EVALUABLE} verdict whose basis is in
     * {@link #PENDING_BASES} is pending a fix rule confirmation.
     */
    public record Verdict(VerdictKind kind, String basis, String fixedText) {
    }
}
