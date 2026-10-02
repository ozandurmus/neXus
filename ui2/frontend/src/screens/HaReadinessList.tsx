import { useEffect, useState } from "react";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import CardActionArea from "@mui/material/CardActionArea";
import CircularProgress from "@mui/material/CircularProgress";
import IconButton from "@mui/material/IconButton";
import TextField from "@mui/material/TextField";
import Tooltip from "@mui/material/Tooltip";
import Typography from "@mui/material/Typography";
import type { CpFailoverSummary, DeviceSummary } from "../auth/adminApi";
import { Icon, type IconName } from "../shell/Icon";
import { StatePanel, VendorBadge, vendorDisplayName } from "../shell/States";
import { formatTime, relativeAge, DISPLAY_TZ_LABEL } from "../shell/time";
import { m3 } from "../theme/m3Theme";

type Cluster = { ref: string; title: string; members: DeviceSummary[] };
type Status = "READY" | "NOT_READY" | "UNKNOWN" | "UNSUPPORTED";
const statuses: Record<Status, { label: string; icon: IconName; bg: string; ink: string; order: number }> = {
  READY: { label: "Ready", icon: "check-circle", bg: m3.successContainer, ink: m3.onSuccessContainer, order: 2 },
  NOT_READY: { label: "Not ready", icon: "x-circle", bg: m3.errorContainer, ink: m3.onErrorContainer, order: 0 },
  UNKNOWN: { label: "Unknown", icon: "info", bg: m3.scHigh, ink: m3.onSurfaceVar, order: 1 },
  UNSUPPORTED: { label: "Not supported", icon: "lock", bg: m3.scLow, ink: m3.onSurfaceVar, order: 3 },
};

function Badge({ status }: { status: Status }) {
  const s = statuses[status];
  return <Box component="span" sx={{ display: "inline-flex", alignItems: "center", gap: 0.75, px: 1.25, py: 0.75, borderRadius: 2, bgcolor: s.bg, color: s.ink, fontSize: 12, fontWeight: 600, whiteSpace: "nowrap" }}>
    <Icon name={s.icon} size={18} />{s.label}
  </Box>;
}

function Observation({ row, now }: { row?: CpFailoverSummary; now: Date }) {
  const at = row?.readiness?.observedAt;
  const reason = row?.readiness?.status === "NOT_READY" ? row.readiness.failedCheck || "Blocking conditions observed"
    : row?.readiness?.status === "READY" ? "No blocking conditions observed" : "Readiness has not been established";
  return <Typography variant="body2" sx={{ color: m3.onSurfaceVar, mt: 0.75 }}>
    {reason}{at && <> · <Box component="time" dateTime={at} title={`${formatTime(at)} ${DISPLAY_TZ_LABEL} · ${at}`}>{relativeAge(at, now)}</Box></>}
  </Typography>;
}

/** Presentation only: status, permissions and masked names remain server-owned. */
export function HaReadinessList({ clusters, rows, running, error, onOpen, onRun }: {
  clusters: readonly Cluster[]; rows: Record<string, CpFailoverSummary[]>; running: string | null; error: string | null;
  onOpen: (ref: string, unitId: string | null) => void; onRun: (row: CpFailoverSummary) => void;
}) {
  const [filter, setFilter] = useState<Status | null>(null);
  const [search, setSearch] = useState("");
  const [showUnsupported, setShowUnsupported] = useState(false);
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 60_000);
    return () => window.clearInterval(timer);
  }, []);
  const items = clusters.map(cluster => {
    const units = rows[cluster.ref] ?? [];
    const base = units.find(row => row.unitId === row.clusterId);
    const vendor = cluster.members[0]?.vendor_hint;
    const unsupported = Boolean(vendor && !["check_point", "palo_alto"].includes(vendor));
    const status: Status = unsupported ? "UNSUPPORTED" : base?.readiness?.status ?? "UNKNOWN";
    return { cluster, units, base, vendor, status };
  });
  const query = search.trim().toLowerCase();
  const visible = items.filter(({ cluster, units, status }) => (!filter || status === filter)
    && [cluster.title, ...cluster.members.map(m => m.hostname ?? ""), ...units.map(row => row.virtual_system ?? "")]
      .some(name => name.toLowerCase().includes(query)))
    .sort((a, b) => statuses[a.status].order - statuses[b.status].order || a.cluster.title.localeCompare(b.cluster.title));
  const supported = visible.filter(item => item.status !== "UNSUPPORTED");
  const unsupported = visible.filter(item => item.status === "UNSUPPORTED");
  const runButton = (row?: CpFailoverSummary) => row?.canRunReadiness && <Tooltip title={running === row.unitId ? "Running pre-checks…" : "Run pre-checks"}>
    <span><IconButton aria-label="Run pre-checks" aria-busy={running === row.unitId} disabled={running !== null}
      onClick={() => onRun(row)} sx={{ m: 1.5, color: m3.primary, bgcolor: m3.primaryContainer, '&:focus-visible': { outline: `2px solid ${m3.primary}`, outlineOffset: 2 } }}>
      {running === row.unitId ? <CircularProgress size={22} aria-label="Running pre-checks" /> : <Icon name="operations" size={22} />}
    </IconButton></span>
  </Tooltip>;
  return <Box sx={{ maxWidth: 1200, mx: "auto", width: "100%" }}>
    <Typography variant="h4" sx={{ mb: 0.5 }}>{clusters.length} clusters enrolled</Typography>
    <Typography variant="body2" sx={{ color: m3.onSurfaceVar, mb: 2.5 }}>Readiness is collected directly from each enrolled unit. Failover runs always perform fresh pre-checks.</Typography>
    <Box aria-label="Readiness summary" sx={{ display: "grid", gridTemplateColumns: { xs: "repeat(2, minmax(0, 1fr))", md: "repeat(4, minmax(0, 1fr))" }, gap: 1.5, mb: 2.5 }}>
      {(Object.keys(statuses) as Status[]).map(status => {
        const s = statuses[status];
        const matches = items.filter(item => item.status === status);
        const vendors = new Map<string, number>();
        matches.forEach(item => { const name = vendorDisplayName(item.vendor); vendors.set(name, (vendors.get(name) ?? 0) + 1); });
        return <Card key={status} sx={{ bgcolor: s.bg, color: s.ink, border: `1px solid ${filter === status ? s.ink : 'transparent'}`, borderRadius: 3, boxShadow: "none" }}>
          <CardActionArea aria-label={`${s.label}: ${matches.length} clusters`} aria-pressed={filter === status}
            onClick={() => setFilter(filter === status ? null : status)} sx={{ p: 2, height: "100%" }}>
            <Box sx={{ display: "flex", gap: 1, alignItems: "center" }}><Icon name={s.icon} /><Typography variant="subtitle2">{s.label}</Typography></Box>
            <Typography sx={{ fontSize: 34, fontWeight: 600, lineHeight: 1.4 }}>{matches.length}</Typography>
            <Typography variant="caption">{[...vendors].map(([name, count]) => `${name} ${count}`).join(" · ") || "No clusters"}</Typography>
          </CardActionArea>
        </Card>;
      })}
    </Box>
    <TextField label="Search cluster or member" size="small" fullWidth value={search} onChange={e => setSearch(e.target.value)} sx={{ mb: 2.5 }} />
    {error && <Box sx={{ mb: 2 }}><StatePanel variant="error" title={error} /></Box>}
    <Box component="ul" aria-label="HA clusters" sx={{ listStyle: "none", p: 0, m: 0, display: "grid", gap: 1.5 }}>
      {supported.map(({ cluster, units, base, vendor, status }) => {
        const rolesAt = cluster.members.map(m => m.inventory_collected_at ?? "").sort().at(-1);
        const roleNames = (roles: string[]) => cluster.members.filter(m => roles.includes((m.ha_role ?? "").toLowerCase())).map(m => m.hostname ?? "Unknown member").join(", ") || "Not observed";
        const context = vendor === "palo_alto" ? [...new Set(cluster.members.flatMap(m => (m.virtual_systems ?? "").split(",").map(v => v.trim()).filter(Boolean)))] : [];
        return <Card component="li" key={cluster.ref} aria-label={cluster.title} sx={{ border: `1px solid ${m3.outlineVar}`, borderRadius: 3, bgcolor: m3.scLowest, boxShadow: "none", overflow: "hidden" }}>
          <Box sx={{ display: "flex", alignItems: "center" }}>
            <CardActionArea onClick={() => onOpen(cluster.ref, base?.unitId ?? null)} aria-label={`Open ${cluster.title}`} sx={{ flex: 1, minWidth: 0, p: { xs: 1.5, sm: 2.5 } }}>
              <Box sx={{ display: "flex", alignItems: { xs: "flex-start", sm: "center" }, gap: 2, flexDirection: { xs: "column", sm: "row" } }}>
                <Badge status={status} />
                <Box sx={{ minWidth: 0, overflowWrap: "anywhere" }}>
                  <Box sx={{ display: "flex", gap: 1, alignItems: "center" }}><Typography variant="subtitle1" sx={{ fontWeight: 600 }}>{cluster.title}</Typography><VendorBadge vendor={vendor} /></Box>
                  <Box title={`Roles observed: ${rolesAt ? `${formatTime(rolesAt)} ${DISPLAY_TZ_LABEL}` : "Not observed"}`} sx={{ display: "flex", gap: 1, flexWrap: "wrap", alignItems: "center", color: m3.onSurfaceVar, fontSize: 12, mt: 0.75 }}>
                    <Box component="span" sx={{ display: "inline-flex", alignItems: "center", gap: 0.75 }}><Box component="span" aria-hidden sx={{ width: 6, height: 6, borderRadius: "50%", bgcolor: m3.onSurface }} />Active: <Box component="span">{roleNames(["active"])}</Box></Box>
                    <span aria-hidden>↔</span>
                    <Box component="span" sx={{ display: "inline-flex", alignItems: "center", gap: 0.75 }}><Box component="span" aria-hidden sx={{ width: 6, height: 6, borderRadius: "50%", border: `1px solid ${m3.onSurfaceVar}` }} />Standby: <Box component="span">{roleNames(["standby", "passive"])}</Box></Box>
                  </Box>
                  {context.length > 0 && <Typography variant="caption">VSYS: {context.join(", ")}</Typography>}
                  <Observation row={base} now={now} />
                </Box>
              </Box>
            </CardActionArea>
            {runButton(base)}
          </Box>
          {units.some(row => row.unitId !== row.clusterId) && <Box component="ul" aria-label={`Virtual systems in ${cluster.title}`} sx={{ listStyle: "none", m: 0, py: 1, px: { xs: 1, sm: 2.5 }, bgcolor: m3.scLow, borderTop: `1px solid ${m3.outlineVar}` }}>
            {units.filter(row => row.unitId !== row.clusterId).map(row => <Box component="li" key={row.unitId} sx={{ display: "flex", alignItems: "center" }}>
              <CardActionArea aria-label={`Open Virtual System ${row.virtual_system ?? "Unknown"}`} onClick={() => onOpen(cluster.ref, row.unitId)} sx={{ p: 1, borderRadius: 2, minWidth: 0 }}>
                <Box sx={{ display: "flex", gap: 1.5, flexWrap: "wrap", alignItems: "center" }}><Badge status={row.readiness?.status ?? "UNKNOWN"} /><Typography variant="body2">Virtual System · <Box component="span">{row.virtual_system ?? "Unknown"}</Box></Typography></Box>
                <Observation row={row} now={now} />
              </CardActionArea>
              {runButton(row)}
            </Box>)}
          </Box>}
        </Card>;
      })}
    </Box>
    {unsupported.length > 0 && <Box component="details" open={showUnsupported || filter === "UNSUPPORTED"} onToggle={e => setShowUnsupported(e.currentTarget.open)} sx={{ mt: 2.5, border: `1px solid ${m3.outlineVar}`, borderRadius: 3, p: 2, bgcolor: m3.scLow }}>
      <Box component="summary" sx={{ cursor: "pointer", color: m3.onSurfaceVar, fontWeight: 600 }}>Not supported · {unsupported.length} clusters</Box>
      <Typography variant="body2" sx={{ color: m3.onSurfaceVar, my: 1.5 }}>Readiness pre-checks are not supported for these vendors; enrolled clusters are shown for inventory context.</Typography>
      <Box component="ul" sx={{ listStyle: "none", m: 0, p: 0 }}>{unsupported.map(({ cluster, vendor }) => <Box component="li" key={cluster.ref} sx={{ display: "flex", gap: 1, alignItems: "center", py: 0.75 }}><Typography variant="body2">{cluster.title}</Typography><VendorBadge vendor={vendor} /></Box>)}</Box>
    </Box>}
    {visible.length === 0 && <StatePanel variant="empty" title="No clusters match these filters" />}
  </Box>;
}
