package com.securityexpert.nexus.ui2.worker.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository;
import java.util.ArrayList;
import java.util.List;

/**
 * Which Multi-Domain Server hosts the Active and which the Standby Domain Management Server of each domain, read from the
 * {@code servers[]} list of the already-issued, gated {@code show-domains} reply (no new device command, no gate change).
 * <p>Only {@code management server} entries are kept (log servers and SmartConsole servers are not the domain's management
 * placement). An entry missing its domain uid or name, server name, multi-domain-server or a boolean {@code active} is
 * skipped and counted, never guessed. Parsing never throws on odd shapes.
 */
final class CpDomainServerPlacement {
    private CpDomainServerPlacement() {}

    record Row(String domainUid, String domainName, String serverName, String multiDomainServer, boolean active) {}
    record Result(List<Row> rows, int domains, int servers, int active, int skipped) {}

    static Result parse(JsonNode listing) {
        List<Row> rows = new ArrayList<>();
        java.util.Set<String> keys = new java.util.HashSet<>();
        int domains = 0, skipped = 0, active = 0;
        JsonNode objects = listing == null ? null : listing.path("objects");
        if (objects == null || !objects.isArray()) return new Result(rows, 0, 0, 0, 0);
        for (JsonNode domain : objects) {
            if (!domain.isObject()) { skipped++; continue; }
            JsonNode servers = domain.path("servers");
            if (!servers.isArray()) continue;
            domains++;
            String uid = text(domain, "uid"), name = text(domain, "name");
            for (JsonNode server : servers) {
                if (!server.isObject() || !"management server".equals(text(server, "type"))) continue;
                String serverName = text(server, "name"), mds = text(server, "multi-domain-server");
                JsonNode flag = server.path("active");
                if (uid == null || name == null || serverName == null || mds == null || !flag.isBoolean()
                        || !keys.add(uid + '\u0000' + serverName)) { skipped++; continue; }
                rows.add(new Row(uid, name, serverName, mds, flag.booleanValue()));
                if (flag.booleanValue()) active++;
            }
        }
        return new Result(List.copyOf(rows), domains, rows.size(), active, skipped);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    /**
     * Parse and replace the stored placement of one source. Only lease/ownership loss propagates; any other failure is a
     * warning (counts only, never names or addresses) and never fails the collection.
     */
    static void record(PolicyCollectionRepository repository, String sourceId, JsonNode listing) {
        try {
            Result result = parse(listing);
            if (result.rows().isEmpty()) {
                // Every Check Point domain has an Active Domain Management Server, so an empty set is an unexpected reply
                // shape, not a fact: keep the previous placement (and its older observed_at) instead of erasing it.
                System.getLogger(CpDomainServerPlacement.class.getName()).log(System.Logger.Level.WARNING,
                    java.text.MessageFormat.format("[CP_DOMAIN_HA] no servers parsed (domains={0} skipped={1}); previous placement kept",
                        result.domains(), result.skipped()));
                return;
            }
            repository.replaceDomainServerPlacement(sourceId, result.rows().stream()
                .map(r -> new PolicyCollectionRepository.DomainServerPlacement(r.domainUid(), r.domainName(), r.serverName(),
                    r.multiDomainServer(), r.active())).toList());
            System.getLogger(CpDomainServerPlacement.class.getName()).log(System.Logger.Level.INFO,
                java.text.MessageFormat.format("[CP_DOMAIN_HA] domains={0} servers={1} active={2} skipped={3}",
                    result.domains(), result.servers(), result.active(), result.skipped()));
        } catch (RuntimeException failed) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(failed);
            System.getLogger(CpDomainServerPlacement.class.getName()).log(System.Logger.Level.WARNING, "[CP_DOMAIN_HA] unavailable");
        }
    }
}
