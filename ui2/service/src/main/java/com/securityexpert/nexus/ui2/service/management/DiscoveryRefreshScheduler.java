package com.securityexpert.nexus.ui2.service.management;

import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import java.util.List;
import java.util.logging.Logger;

import org.jooq.Record;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.service.discovery.DiscoveryRunService;

/**
 * Re-runs discovery against every enrolled management server (Check Point MDS, Panorama) at the shared six-hour Europe/Istanbul slots
 * (PO, 2026-09-23), so the managed-estate tree stays current and a device added on the manager shows up as "not in
 * neXus". It repeats the last finished run's own address, vendor and credential reference -- the same gated
 * management-plane reads, no new command. A manager with no earlier finished run is left alone: the first discovery
 * is an operator's decision (Import from manager).
 */
@Component
public class DiscoveryRefreshScheduler {

    private static final Logger LOG = Logger.getLogger(DiscoveryRefreshScheduler.class.getName());
    public static final String ACTOR = "system:discovery-refresh";

    private final TransactionBoundary tx;
    private final DiscoveryRunService discovery;

    public DiscoveryRefreshScheduler(TransactionBoundary tx, DiscoveryRunService discovery) {
        this.tx = tx;
        this.discovery = discovery;
    }

    @Scheduled(cron = JobWindowPolicy.CRON, zone = JobWindowPolicy.ZONE)
    public void nightly() {
        try {
            int started = refreshAll();
            LOG.info("[DISCOVERY_REFRESH] started " + started + " run(s)");
        } catch (RuntimeException e) {
            LOG.warning("[DISCOVERY_REFRESH] failed: " + e.getMessage());
        }
    }

    /** @return the number of runs admitted */
    public int refreshAll() {
        List<Record> targets = tx.inTransaction(dsl -> dsl.fetch(
                "select distinct on (r.vendor, r.management_address) r.vendor, r.management_address, r.credential_reference_id, "
                        + "(latest.state = 'FAILED' and latest.outcome_summary @> "
                        + "'{\"failure_reason_class:manager command timed out; stopped\":1}'::jsonb "
                        + "and (latest.finished_at at time zone 'Europe/Istanbul')::date >= "
                        + "((now() at time zone 'Europe/Istanbul')::date - 1)) as skip_timeout "
                        + "from discovery_run r join endpoints e on e.address_ref = r.management_address "
                        + "join devices d on d.device_id = e.device_id "
                        + "join lateral (select x.state, x.outcome_summary, x.finished_at from discovery_run x "
                        + "where x.vendor = r.vendor and x.management_address = r.management_address "
                        + "and x.finished_at is not null order by x.finished_at desc limit 1) latest on true "
                        + "where d.role = 'management_server' and d.enrollment_state = 'ENROLLED' and not d.disabled "
                        + "and r.state = 'FINISHED' "
                        + "and not exists (select 1 from discovery_run x where x.management_address = r.management_address "
                        + "and x.vendor = r.vendor and x.finished_at is null) "
                        + "order by r.vendor, r.management_address, r.finished_at desc"));
        int started = 0;
        for (Record t : targets) {
            if (!JobWindowPolicy.SYSTEM.isOpen()) break;
            if (Boolean.TRUE.equals(t.get("skip_timeout", Boolean.class))) {
                LOG.info("[DISCOVERY_REFRESH] skipped: previous run timed out");
                continue;
            }
            DiscoveryRunService.StartOutcome outcome = discovery.start(ACTOR, t.get("management_address", String.class),
                    t.get("vendor", String.class), t.get("credential_reference_id", String.class));
            if (outcome instanceof DiscoveryRunService.StartOutcome.Admitted) {
                started++;
            } else {
                LOG.warning("[DISCOVERY_REFRESH] " + t.get("vendor", String.class) + " run not admitted: " + outcome);
            }
        }
        return started;
    }
}
