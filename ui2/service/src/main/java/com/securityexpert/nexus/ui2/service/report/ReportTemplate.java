package com.securityexpert.nexus.ui2.service.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.securityexpert.nexus.ui2.service.security.ActionRegistry;

/**
 * The closed catalogue (Reports R1 contract "Scope"): RPT-01, RPT-02, RPT-03 and RPT-05, RPT-17 (V270) and the zone
 * matrix evidence report ZM-01 (V277; ZONE_MATRIX_CONTRACT.md 7.3). Nothing else is generatable
 * ({@code report_run.template_id} check). Each template names the read action of every underlying domain it uses
 * (contract "Masking and RBAC": generating a report also requires those reads).
 */
public enum ReportTemplate {
    RPT_01("RPT-01", "Executive weekly summary", "Executive",
            "How is the estate doing this week, how much of it is backed by fresh evidence, and what needs attention?",
            List.of(ActionRegistry.DEVICE_READ, ActionRegistry.DEVICE_BACKUP_READ, ActionRegistry.CP_FAILOVER_READ),
            false),
    RPT_02("RPT-02", "Framework compliance and evidence coverage", "Auditor",
            "How do the firewalls comply with CIS and NIST (then PCI-DSS and Financial Baseline), which controls fail where, "
                    + "and how much has no evidence?",
            List.of(ActionRegistry.DEVICE_READ), true),
    RPT_03("RPT-03", "Backup coverage and anomalies", "Operations",
            "Which backup targets have an archive and how old is it, and which backups grew, are fleet-large or carry "
                    + "firewall logs?",
            List.of(ActionRegistry.DEVICE_BACKUP_READ), false),
    RPT_05("RPT-05", "HA and failover readiness", "Operations",
            "Which HA clusters are ready for failover, which are not and why, and which readiness results are too old to "
                    + "rely on?",
            List.of(ActionRegistry.CP_FAILOVER_READ), false),
    RPT_17("RPT-17", "Lifecycle and licensing", "Auditor",
            "Which licences, subscriptions and support contracts are expired or expiring within 30 or 90 days, which "
                    + "devices match exploited or critical/high advisories, what Live Patch and hotfix levels are stored, "
                    + "and which devices have no lifecycle read?",
            List.of(ActionRegistry.DEVICE_READ), false),
    ZM_01("ZM-01", "Zone matrix segmentation evidence", "Compliance / Auditor",
            "Which firewall rules conflict with the published zone matrix in the period, which policies were evaluated and "
                    + "how current they are, and how many referenced matrix cells compliance has confirmed?",
            List.of(ActionRegistry.ZONE_MATRIX_READ), false);

    private final String id;
    private final String title;
    private final String audience;
    private final String question;
    private final List<String> domainReadActions;
    private final boolean frameworkParameter;

    ReportTemplate(String id, String title, String audience, String question, List<String> domainReadActions,
            boolean frameworkParameter) {
        this.id = id;
        this.title = title;
        this.audience = audience;
        this.question = question;
        this.domainReadActions = List.copyOf(domainReadActions);
        this.frameworkParameter = frameworkParameter;
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public String audience() {
        return audience;
    }

    public String question() {
        return question;
    }

    public List<String> domainReadActions() {
        return domainReadActions;
    }

    public boolean frameworkParameter() {
        return frameworkParameter;
    }

    /**
     * The file formats this template generates; the on-screen JSON preview is not a file. ZM-01 is CSV and PDF only
     * (ZONE_MATRIX_CONTRACT.md 7.3); an XLSX request for it is refused with {@code INVALID_FORMAT}.
     */
    public Set<ReportFormat> fileFormats() {
        if (this == ZM_01) return Set.of(ReportFormat.PDF, ReportFormat.CSV);
        return Set.of(ReportFormat.PDF, ReportFormat.XLSX, ReportFormat.CSV);
    }

    /** {@link #fileFormats()} in the stable {@link ReportFormat} order, for the catalogue and refusal texts. */
    public List<ReportFormat> fileFormatsOrdered() {
        List<ReportFormat> out = new ArrayList<>();
        for (ReportFormat f : ReportFormat.values()) {
            if (fileFormats().contains(f)) out.add(f);
        }
        return List.copyOf(out);
    }

    /** True for the template that takes the zone matrix parameters (policy, outcome, blocks appendix opt-in). */
    public boolean zoneMatrixParameters() {
        return this == ZM_01;
    }

    /** Fail-closed lookup by the persisted id; an unknown id is empty, never a default template. */
    public static Optional<ReportTemplate> fromId(String id) {
        for (ReportTemplate t : values()) {
            if (t.id.equals(id)) return Optional.of(t);
        }
        return Optional.empty();
    }
}
