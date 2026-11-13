import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Typography from "@mui/material/Typography";

import type { ChangeFeedPage, CpFailoverSummary, DeviceSummary, LifecycleDevice, OverviewView } from "../auth/adminApi";
import { m3 } from "../theme/m3Theme";
import { AttentionList } from "../screens/overview/AttentionList";
import { buildAttention } from "../screens/overview/attentionRules";
import { EXEC } from "../screens/overview/EstateMap";
import { evidencedShares, primaryFrameworksFirst } from "../screens/overview/executiveHome";
import { EXEC_HOME } from "./previewData";

const CARD = { borderRadius: "10px", bgcolor: m3.scLowest, border: `1px solid ${m3.outlineVar}`, p: 2.25, boxShadow: "none" } as const;

/**
 * The Overview (docs/design/OVERVIEW_ATTENTION_LIST_DESIGN_2026_11_13.md, DRAFT) on synthetic, masked figures: the
 * attention list built by the live screen's own rules (`overview/attentionRules.ts`), the fleet in four facts and the
 * framework bars. Only the inputs are synthetic.
 */
export function OverviewPreview() {
  const now = new Date();
  const ago = (h: number) => new Date(now.getTime() - h * 3_600_000).toISOString();
  const x = EXEC_HOME;
  const fw = (n: number) => `FW-${["ALFA", "BRAVO", "CHARLIE", "DELTA", "ECHO", "FOXTROT", "GOLF", "HOTEL"][n % 8]}-${String(n).padStart(2, "0")}`;
  const devices = Array.from({ length: 12 }, (_, i) => ({
    device_id: `p${i}`, hostname: fw(i), vendor: i % 3 ? "check_point" : "palo_alto", model: null, condition: "clear" as const,
    no_archive: i === 3 || i === 7, critical_fail: i < 5 ? 2 : 0, last_read_at: i === 9 ? ago(80) : i === 10 ? ago(30) : ago(0.4),
    backup_target: true, last_backup_at: i === 3 || i === 7 ? null : i === 5 ? ago(24 * 9) : ago(20),
  }));
  const overview = {
    generated_at: now.toISOString(), masked: true,
    evidence: { compliance: { at: ago(x.complianceHoursAgo), state: "OK" } },
    attention: { failed_jobs_24h: { count: 4, state: "OK", last_at: ago(0.7) } },
    exceptions: { failed_jobs: { total: 4, rows: [
      { job_id: "j1", job_type: "backup", device_id: "p1", label: fw(1), terminal_reason: "connect_failed", finished_at: ago(0.7) },
      { job_id: "j2", job_type: "inventory_collect", device_id: "p8", label: fw(8), terminal_reason: "timeout", finished_at: ago(3) },
    ], reasons: [{ reason: "connect_failed: SSH session closed", count: 3, devices: 1, job_types: ["backup"], last_at: ago(0.7) }] } },
    estate: { devices, compliance_state: "OK" },
  } as unknown as OverviewView;
  const member = (ref: string, n: number): DeviceSummary => ({ device_id: `${ref}-${n}`, vendor_hint: "check_point", enrollment_state: "ENROLLED",
    hostname: `${ref}-M${n}`, model: null, software_version: null, ha_role: null, cluster_member_ref: ref } as DeviceSummary);
  const unit = (ref: string, status: "READY" | "NOT_READY" | "UNKNOWN", failedCheck = "") =>
    ({ clusterId: ref, unitId: ref, cluster_member_ref: ref, readiness: { status, observedAt: ago(x.readinessHoursAgo), failedCheck, checks: [] } }) as unknown as CpFailoverSummary;
  const life = (i: number, extra: Partial<LifecycleDevice>) => ({ device_id: `p${i}`, hostname: fw(i), vendor: "check_point", licenses: [], ...extra }) as LifecycleDevice;
  const attention = buildAttention({
    now, overview,
    failover: { state: "ok", value: { devices: ["CLS-ROMEO-01", "CLS-TANGO-04", "CLS-SIERRA-02"].flatMap((r) => [member(r, 1), member(r, 2)]),
      rows: [unit("CLS-ROMEO-01", "NOT_READY", "sync interface state"), unit("CLS-TANGO-04", "READY"), unit("CLS-SIERRA-02", "UNKNOWN")] } },
    lifecycle: { state: "ok", value: { as_of: now.toISOString(), summary: {}, devices: [
      life(4, { vulnerabilities: { state: "EVALUATED", affected_exploited: 1, affected_critical_high: 1, compared: { source: "preview", advisories: 40, oldest: null, newest: null, loaded_at: ago(5), age_days: 0 } } }),
      life(6, { licenses: [{ label: "Threat Prevention", status: "DATED", days_remaining: 9, expiry: ago(-24 * 9) }] }),
      life(2, { live_patch: { package_take: null, jumbo: null, read_outcome: "OK", observed_at: ago(6), armed: 0, ready: 0, jumbofix: 0, other: 0, items: [], missing: true } }),
    ] } },
    changes: { state: "ok", value: { events: [{ key: "c1", kind: "USER", actor: null, device_name: fw(11), device_id: "p11", time: ago(14) }] } as unknown as ChangeFeedPage },
    controls: [{ control_id: x.topControl.id, title: x.topControl.title, severity: "CRITICAL", fail_count: 5, target_device_count: 12 }] as never,
  }, undefined);
  const facts = [
    { value: String(x.devices), label: "enrolled devices" },
    { value: `${x.ha.ready} of ${x.ha.total}`, label: "clusters ready for failover" },
    { value: `${x.backups.archived} of ${x.backups.of}`, label: "backup targets backed up" },
    { value: "129 of 132", label: "devices read in 24 h" },
  ];
  return (
    <Box sx={{ p: { xs: 2, md: 4 }, flex: 1, minWidth: 0, display: "flex", flexDirection: "column", gap: 2 }}>
      <Box>
        <Typography variant="h2">Overview</Typography>
        <Typography variant="body2">{x.devices} enrolled devices · {x.clusters} clusters · synthetic preview figures</Typography>
      </Box>
      <AttentionList result={attention} />
      <Card sx={{ ...CARD, display: "flex", flexDirection: "column", gap: 1.5 }} data-testid="fleet-summary">
        <Typography sx={{ fontSize: 16, fontWeight: 700 }}>Fleet</Typography>
        <Box sx={{ display: "grid", gap: 1.5, gridTemplateColumns: { xs: "1fr 1fr", md: "repeat(4, 1fr)" } }}>
          {facts.map((f) => (
            <Box key={f.label} sx={{ border: `1px solid ${m3.outlineVar}`, borderRadius: "8px", px: 1.5, py: 1 }}>
              <Typography sx={{ fontSize: 20, fontWeight: 700 }}>{f.value}</Typography>
              <Typography sx={{ fontSize: 12.5, color: m3.onSurfaceVar }}>{f.label}</Typography>
            </Box>
          ))}
        </Box>
      </Card>
      <Card sx={CARD}>
        <Typography sx={{ fontSize: 16, fontWeight: 700 }}>Compliance by framework</Typography>
        <Typography sx={{ fontSize: 12.5, color: m3.onSurfaceVar, mb: 1.5 }}>Technical checks. Not an audit certification.</Typography>
        {primaryFrameworksFirst(x.frameworks, (f) => f.name).map((f) => {
          const s = evidencedShares(f.pass, f.fail, f.unavailable);
          const t = f.pass + f.fail + f.unavailable;
          return (
            <Box key={f.name} sx={{ mb: 1.5 }}>
              <Box sx={{ display: "flex", justifyContent: "space-between", gap: 1, mb: 0.5, flexWrap: "wrap" }}>
                <Typography sx={{ fontSize: 13.5, fontWeight: 600 }}>{f.name}</Typography>
                <Typography sx={{ fontSize: 12.5, color: m3.onSurfaceVar }}><b>{s.passPct}%</b> of evidenced controls pass · {s.noEvidencePct}% have no evidence</Typography>
              </Box>
              <Box sx={{ display: "flex", height: 14, borderRadius: 7, overflow: "hidden", gap: "2px" }}>
                {[[f.pass, EXEC.green], [f.fail, EXEC.red], [f.unavailable, EXEC.hatch]].map(([c, bg], i) => (
                  <Box key={i} sx={{ width: `${(100 * Number(c)) / t}%`, background: String(bg) }} />
                ))}
              </Box>
            </Box>
          );
        })}
      </Card>
    </Box>
  );
}
