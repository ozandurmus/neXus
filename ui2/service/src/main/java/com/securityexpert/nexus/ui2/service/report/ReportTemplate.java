package com.securityexpert.nexus.ui2.service.report;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.securityexpert.nexus.ui2.service.security.ActionRegistry;

/**
 * The closed R1 catalogue (Reports R1 contract "Scope"): RPT-01, RPT-02, RPT-03 and RPT-05. Nothing else is
 * generatable in R1 (V190 {@code report_run.template_id} check). Each template names the read action of every
 * underlying domain it uses (contract "Masking and RBAC": generating a report also requires those reads).
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
            List.of(ActionRegistry.CP_FAILOVER_READ), false);

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

    /** The file formats R1 generates; the on-screen JSON preview is not a file. */
    public Set<ReportFormat> fileFormats() {
        return Set.of(ReportFormat.PDF, ReportFormat.XLSX, ReportFormat.CSV);
    }

    /** Fail-closed lookup by the persisted id; an unknown id is empty, never a default template. */
    public static Optional<ReportTemplate> fromId(String id) {
        for (ReportTemplate t : values()) {
            if (t.id.equals(id)) return Optional.of(t);
        }
        return Optional.empty();
    }
}
