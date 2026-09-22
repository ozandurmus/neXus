import { useEffect, useState } from "react";
import Box from "@mui/material/Box";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

import { ScreenHeader, MetricGrid, MetricCard, EmptyPanel, ScreenRoot } from "../shell/ScreenLayout";
import { M3Button, StatusChip } from "../shell/M3Widgets";
import {
  getComplianceOverview,
  listConfigurations,
  listDevices,
  listFleetBackups,
  listJobs,
  type BackupArtefact,
  type ComplianceOverview,
  type DeviceSummary,
} from "../auth/adminApi";

/**
 * What the Overview says, computed from the same reads the other screens make.
 * Every figure is counted from a list the store returned; when a read failed
 * the figure is null and the card says the read failed rather than showing 0
 * (Product Owner, 2026-09-22: the screen read "0 devices enrolled" over a
 * fleet of 103 -- it was a constant).
 */
export interface OverviewFigures {
  readonly devicesTotal: number;
  readonly devicesEnrolled: number;
  readonly devicesDraft: number;
  readonly devicesDisabled: number;
  readonly byVendor: Record<string, number>;
  readonly clusters: number;
  readonly withInventory: number;
  readonly withConfiguration: number | null;
  readonly withBackup: number | null;
  readonly backupTargets: number;
  readonly newestBackupAt: string | null;
  readonly oldestBackupAgeDays: number | null;
  readonly jobsTotal: number | null;
  readonly jobsRunning: number | null;
  readonly jobsFailed24h: number | null;
  readonly compliance: ComplianceOverview | null;
}

export function computeFigures(input: {
  devices: readonly DeviceSummary[];
  configurationDeviceIds: readonly string[] | null;
  backups: readonly BackupArtefact[] | null;
  jobsTotal: number | null;
  jobsRunning: number | null;
  jobsFailed24h: number | null;
  compliance: ComplianceOverview | null;
  now?: Date;
}): OverviewFigures {
  const now = input.now ?? new Date();
  const devices = input.devices;
  const byVendor: Record<string, number> = {};
  const clusterRefs = new Set<string>();
  for (const d of devices) {
    byVendor[d.vendor_hint] = (byVendor[d.vendor_hint] ?? 0) + 1;
    if (d.cluster_member_ref) clusterRefs.add(d.cluster_member_ref);
  }
  let newestBackupAt: string | null = null;
  let oldestNewestPerDevice: string | null = null;
  let withBackup: number | null = null;
  if (input.backups) {
    const newestByDevice = new Map<string, string>();
    for (const b of input.backups) {
      const seen = newestByDevice.get(b.device_id);
      if (!seen || b.collected_at > seen) newestByDevice.set(b.device_id, b.collected_at);
      if (!newestBackupAt || b.collected_at > newestBackupAt) newestBackupAt = b.collected_at;
    }
    withBackup = newestByDevice.size;
    for (const t of newestByDevice.values()) {
      if (!oldestNewestPerDevice || t < oldestNewestPerDevice) oldestNewestPerDevice = t;
    }
  }
  const deviceIds = new Set(devices.map((d) => d.device_id));
  return {
    devicesTotal: devices.length,
    devicesEnrolled: devices.filter((d) => d.enrollment_state === "ENROLLED").length,
    devicesDraft: devices.filter((d) => d.enrollment_state === "DRAFT").length,
    devicesDisabled: devices.filter((d) => d.enrollment_state === "DISABLED").length,
    byVendor,
    clusters: clusterRefs.size,
    withInventory: devices.filter((d) => Boolean(d.ip_addresses)).length,
    withConfiguration: input.configurationDeviceIds ? input.configurationDeviceIds.filter((id) => deviceIds.has(id)).length : null,
    withBackup,
    backupTargets: devices.filter((d) => d.backup_target).length,
    newestBackupAt,
    oldestBackupAgeDays: oldestNewestPerDevice ? Math.floor((now.getTime() - new Date(oldestNewestPerDevice).getTime()) / 86_400_000) : null,
    jobsTotal: input.jobsTotal,
    jobsRunning: input.jobsRunning,
    jobsFailed24h: input.jobsFailed24h,
    compliance: input.compliance,
  };
}

const VENDOR_LABEL: Record<string, string> = { check_point: "Check Point", palo_alto: "Palo Alto" };

function readFailed(value: number | null): string {
  return value === null ? "read failed" : String(value);
}

export function OverviewScreen() {
  const [figures, setFigures] = useState<OverviewFigures | null>(null);
  const [devicesError, setDevicesError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      let devices: DeviceSummary[] = [];
      try {
        devices = (await listDevices()).devices ?? [];
      } catch (error) {
        if (!cancelled) setDevicesError(error instanceof Error ? error.message : "The device list could not be read.");
        return;
      }
      const since = new Date(Date.now() - 24 * 3600 * 1000).toISOString();
      const [configuration, backups, jobsTotal, jobsRunning, jobsFailed24h, compliance] = await Promise.all([
        listConfigurations().then((r) => r.devices.map((d) => d.device_id)).catch(() => null),
        listFleetBackups().then((r) => r.backups ?? []).catch(() => null),
        listJobs({ page_size: 1 }).then((r) => r.total).catch(() => null),
        listJobs({ state: "EXECUTING,CLAIMED,REQUESTED", page_size: 1 }).then((r) => r.total).catch(() => null),
        listJobs({ state: "FAILED", since, page_size: 1 }).then((r) => r.total).catch(() => null),
        getComplianceOverview().catch(() => null),
      ]);
      if (!cancelled) {
        setFigures(computeFigures({ devices, configurationDeviceIds: configuration, backups, jobsTotal, jobsRunning, jobsFailed24h, compliance }));
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  if (devicesError) {
    return (
      <ScreenRoot>
        <ScreenHeader title="Operational posture" subtitle="The device list could not be read" />
        <EmptyPanel title="Overview unavailable" body={devicesError} />
      </ScreenRoot>
    );
  }
  if (!figures) {
    return (
      <ScreenRoot>
        <ScreenHeader title="Operational posture" subtitle="Reading the fleet…" />
      </ScreenRoot>
    );
  }

  const vendors = Object.entries(figures.byVendor)
    .sort((a, b) => b[1] - a[1])
    .map(([v, n]) => `${n} ${VENDOR_LABEL[v] ?? v}`)
    .join(" · ");
  const subtitle = figures.devicesTotal === 0
    ? "0 devices enrolled · nothing collected yet"
    : `${figures.devicesEnrolled} of ${figures.devicesTotal} devices enrolled` + (vendors ? ` · ${vendors}` : "")
      + (figures.clusters ? ` · ${figures.clusters} clusters` : "");

  const c = figures.compliance;
  return (
    <ScreenRoot>
      <ScreenHeader
        title="Operational posture"
        subtitle={subtitle}
        actions={
          <>
            <M3Button emphasis="outlined" href="?screen=inventory">Open Inventory</M3Button>
            <M3Button emphasis="filled" href="?screen=configuration">Open Configuration</M3Button>
          </>
        }
      />
      <MetricGrid>
        <MetricCard
          title="Network inventory"
          note={figures.devicesTotal === 0
            ? "none enrolled"
            : `${figures.withInventory} of ${figures.devicesTotal} with collected interfaces` + (figures.devicesDraft ? ` · ${figures.devicesDraft} unconfirmed` : "") + (figures.devicesDisabled ? ` · ${figures.devicesDisabled} disabled` : "")}
        />
        <MetricCard
          title="Configuration"
          note={figures.withConfiguration === null ? "read failed" : figures.withConfiguration === 0 ? "no evidence yet" : `${figures.withConfiguration} devices with a configuration read`}
        />
        <MetricCard
          title="Backups"
          note={figures.withBackup === null
            ? "read failed"
            : figures.withBackup === 0
              ? `none taken · ${figures.backupTargets} targets`
              : `${figures.withBackup} devices backed up · ${figures.backupTargets} targets` + (figures.oldestBackupAgeDays !== null ? ` · oldest latest ${figures.oldestBackupAgeDays} d` : "")}
        />
        <MetricCard
          title="Jobs"
          note={figures.jobsTotal === null
            ? "read failed"
            : `${figures.jobsTotal} on record · ${readFailed(figures.jobsRunning)} in flight · ${readFailed(figures.jobsFailed24h)} failed in 24 h`}
        />
      </MetricGrid>
      <Box sx={{ display: "grid", gridTemplateColumns: "minmax(0, 2fr) minmax(0, 1fr)", gap: 2, flex: 1, minHeight: 0 }}>
        {c ? (
          <EmptyPanel
            title="Compliance"
            body={`${c.evaluated_firewalls} of ${c.total_firewalls} firewalls evaluated · observed ${c.observed_compliance_pct}% · evidence coverage ${c.evidence_coverage_pct}% · ${c.critical_deficiencies} critical deficiencies · ${c.data_gaps} data gaps`}
          >
            <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", gap: 1 }}>
              {c.frameworks.map((f) => (
                <StatusChip
                  key={f.framework}
                  tone={f.score_pct >= 90 ? "ok" : f.score_pct >= 60 ? "warn" : "bad"}
                  label={`${f.framework} ${f.score_pct}% (${f.pass_count}/${f.total_controls})`}
                />
              ))}
            </Stack>
          </EmptyPanel>
        ) : (
          <EmptyPanel title="Compliance" body="The compliance overview could not be read, or no evaluation has run yet." />
        )}
        <Stack spacing={2}>
          <EmptyPanel
            title="Configuration alignment"
            body="Alignment (expected intent vs effective state) is not collected in this build; Configuration shows what each device reports about itself."
          />
          <EmptyPanel
            title="HA / clusters"
            body={figures.clusters === 0
              ? "No cluster is enrolled. Readiness is observed only; no class 2 action exists in this build."
              : `${figures.clusters} clusters enrolled. Readiness is observed only; no class 2 action exists in this build.`}
          />
          <Typography variant="caption" color="text.secondary">
            Every figure is counted from a list the store returned when this screen opened; nothing here is inferred.
          </Typography>
        </Stack>
      </Box>
    </ScreenRoot>
  );
}
