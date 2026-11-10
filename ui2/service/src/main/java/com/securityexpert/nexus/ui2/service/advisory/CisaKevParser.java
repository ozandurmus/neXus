package com.securityexpert.nexus.ui2.service.advisory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.KnownExploited;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.ParseResult;

/**
 * The CISA known-exploited catalog (contract section 2, "the exploited flag"; section 6, "Known-exploited flag"). Yields
 * only {@link KnownExploited} entries, never advisories (rule 3: the catalog adds a flag, the vendor decides the verdict).
 * An entry whose CVE id does not match {@code CVE-\d{4}-\d{4,}} or whose date is unreadable is counted in {@code rejected}.
 */
@Component
public class CisaKevParser implements AdvisoryParser {

    @Override
    public String source() {
        return AdvisoryModel.SOURCE_CISA_KEV;
    }

    @Override
    public ParseResult parse(String url, byte[] document) {
        try {
            JsonNode root = CsafSupport.readTree(document);
            JsonNode entries = root == null ? null : root.path("vulnerabilities");
            if (entries == null || !entries.isArray()) {
                return new ParseResult(List.of(), List.of(), 1);
            }
            List<KnownExploited> out = new ArrayList<>();
            int rejected = 0;
            for (JsonNode entry : entries) {
                try {
                    String cve = CsafSupport.text(entry.path("cveID"));
                    String added = CsafSupport.text(entry.path("dateAdded"));
                    if (cve == null || added == null || !CsafSupport.isCveId(cve.trim())) {
                        rejected++;
                        continue;
                    }
                    LocalDate date = LocalDate.parse(added.trim());
                    boolean ransomware = "Known".equalsIgnoreCase(CsafSupport.text(entry.path("knownRansomwareCampaignUse")));
                    out.add(new KnownExploited(cve.trim(), date, ransomware));
                } catch (RuntimeException e) {
                    rejected++;
                }
            }
            return new ParseResult(List.of(), out, rejected);
        } catch (Exception | StackOverflowError e) {
            return new ParseResult(List.of(), List.of(), 1);
        }
    }
}
