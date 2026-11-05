import { useCallback, useEffect, useState, type ReactNode } from "react";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Collapse from "@mui/material/Collapse";
import Link from "@mui/material/Link";
import Tooltip from "@mui/material/Tooltip";
import Typography from "@mui/material/Typography";

import { ScreenHeader, ScreenRoot } from "../shell/ScreenLayout";
import { Icon, type IconName } from "../shell/Icon";
import { StatusChip } from "../shell/M3Widgets";
import { Donut, STATUS, STATUS_INK, StackedBar } from "../shell/Charts";
import { StatePanel, Ts, isRestricted } from "../shell/States";
import { useDisplayMode } from "../shell/displayMode";
import { DISPLAY_TZ_LABEL, formatStamp, formatUtc, relativeAge } from "../shell/time";
import { MONO, m3 } from "../theme/m3Theme";
import { getComplianceControls, getOverview, listCpFailoverSummary, listDevices, type ComplianceControlItem, type DeviceSummary, type CountTile, type EvidenceChip, type FailureReason, type OverviewView, type VersionSlice } from "../auth/adminApi";
import { EXEC, EstateMap, HeroFact, PopulationBar } from "./overview/EstateMap";
import { RankedBars, majorVersionVerdict, rankRows, reportedVersionVerdict } from "./overview/RankedBars";
import { vendorDisplayName } from "../shell/States";
import { jobTypeLabel } from "../shell/presenters";
import { groupClusters, haReadinessItems, readinessCounts, readinessFreshness } from "./HaReadinessList";
import { freshnessOf, FRESHNESS_COLOR, type FleetFreshness, type FreshnessLevel } from "../shell/freshness";
import { StaleEvidenceBanner } from "../shell/StaleEvidenceBanner";
import { attentionLines, evidencedShares, executiveVerdict, freshnessSentence, MAX_ATTENTION_LINES, primaryFrameworksFirst } from "./overview/executiveHome";

export { relativeAge } from "../shell/time";

/**
 * Overview -- the executive summary over stored evidence (OVERVIEW_EXCEPTION_SCREEN_CONTRACT, FROZEN 2026-09-23,
 * amendments A and B of the same day; layout from UI_VISUAL_REVIEW_2026_09_23_FABLE.md §2):
 *   A  title, "as of" with active-of-enrolled denominators, mask chip, one freshness chip (expands to six)
 *   B  the headline in three lines: Act now / Review / Evidence
 *   C  six posture tiles, each count + "of N", status word, bar, context, since-yesterday delta, one click
 *   D  compliance by framework | why jobs failed
 *   E  software and hardware (inventory facts, not exceptions -- below the fold)
 *   F  configuration changes | cluster member DIFF | inventory evidence age
 * Every figure is a stored-evidence query with its evidence time; UNKNOWN is written as UNKNOWN, never 0; zero is
 * neutral; every figure opens the list behind it. `?wall=1` shows rows B, C and the failure causes beside the queue.
 */

const CARD = { borderRadius: "10px", bgcolor: m3.scLowest, border: `1px solid ${m3.outlineVar}`, p: 2.25, boxShadow: "none" } as const;
const q = (params: Record<string, string>) => `?${new URLSearchParams(params).toString()}`;
const pct = (a: number, b: number) => (b > 0 ? Math.round((100 * a) / b) : 0);

interface HaOverview { total: number; ready: number; notReady: number; unknown: number; unsupported: number; evaluated: number; stale: number; mostlyStale: boolean;
  /** The newest readiness evaluation among the not-ready / unknown clusters (the age on the "needs attention" line). */
  notReadyAt?: string | null; unknownAt?: string | null }

/** The row text, for the page and for the link's accessible name: "20 ready of 45 clusters · 4 not ready · 16 unknown". */
export function haReadinessSentence(h: Pick<HaOverview, "total" | "ready" | "notReady" | "unknown" | "unsupported">): string {
  return `${h.ready} ready of ${h.total} clusters · ${h.notReady} not ready · ${h.unknown} unknown${h.unsupported > 0 ? ` · ${h.unsupported} not supported` : ""}`;
}

/**
 * Software versions of a vendor the overview payload carries no version breakdown for (Fortinet, Cisco ASA), read
 * from the device list exactly as the devices report them: no recommended build, no judgement of age. A device that
 * reports no version is counted as Unknown. A management server (FortiManager) runs a different product and is left out.
 */
export function reportedVersions(devices: readonly DeviceSummary[], vendor: string, includeManagers = false): Array<{ label: string | null; count: number }> {
  const counts = new Map<string | null, number>();
  for (const d of devices) {
    if (d.vendor_hint !== vendor || d.enrollment_state !== "ENROLLED" || (!includeManagers && d.role === "management_server")) continue;
    const label = d.software_version?.trim() || null;
    counts.set(label, (counts.get(label) ?? 0) + 1);
  }
  return [...counts.entries()].map(([label, count]) => ({ label, count }));
}

/** The chart tone of a read time on the product's one freshness scale (B1, `shell/freshness.ts`). */
const FRESHNESS_STATUS: Record<FreshnessLevel, keyof typeof STATUS> = { fresh: "good", ageing: "warning", stale: "critical", unknown: "neutral" };
function ageTone(iso: string | null | undefined): keyof typeof STATUS {
  return FRESHNESS_STATUS[freshnessOf(iso)];
}

function SectionTitle({ icon, title, hint, right }: { readonly icon: IconName; readonly title: string; readonly hint?: string; readonly right?: ReactNode }) {
  return (
    <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", mb: 1.5, gap: 1, flexWrap: "wrap" }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 1.25 }}>
        <Box sx={{ width: 30, height: 30, borderRadius: "8px", bgcolor: m3.primaryContainer, color: m3.primary, display: "grid", placeItems: "center" }}>
          <Icon name={icon} size={17} />
        </Box>
        <Box>
          <Typography sx={{ fontSize: 16, lineHeight: "24px", fontWeight: 600, color: m3.onSurface }}>{title}</Typography>
          {hint && <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>{hint}</Typography>}
        </Box>
      </Box>
      {right}
    </Box>
  );
}

const EVIDENCE_KEYS: Array<[string, string, string]> = [
  ["inventory", "Inventory", q({ screen: "operations", tab: "jobs", job_type: "inventory_collect" })],
  ["configuration", "Configuration", q({ screen: "operations", tab: "jobs", job_type: "configuration_collect" })],
  ["compliance", "Compliance", "?screen=compliance"],
  ["backup", "Backup", q({ screen: "operations", tab: "jobs", job_type: "backup" })],
  ["jobs", "Jobs", q({ screen: "operations", tab: "jobs" })],
  ["platform_facts", "Platform facts", "?screen=inventory"],
];

function EvidenceDot({ label, chip, href }: { readonly label: string; readonly chip: EvidenceChip | undefined; readonly href: string }) {
  const tone = chip?.state === "OK" ? ageTone(chip.at) : "neutral";
  const text = chip?.state === "OK" ? relativeAge(chip.at) : chip?.state === "UNKNOWN" ? "UNKNOWN — no stored evidence" : "READ FAILED";
  return (
    <Tooltip title={chip?.at ? `${formatUtc(chip.at)} ${DISPLAY_TZ_LABEL}` : text}>
      <Link href={href} underline="none" sx={{ color: "inherit" }}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 0.75, px: 1.25, py: 0.4, borderRadius: "999px", border: `1px solid ${m3.outlineVar}`, bgcolor: m3.scLowest }}>
          <Box sx={{ width: 8, height: 8, borderRadius: "50%", bgcolor: STATUS[tone] }} />
          <Typography sx={{ fontSize: 12, color: m3.onSurfaceVar }}>{label}</Typography>
          <Typography sx={{ fontSize: 12, fontWeight: 600, color: m3.onSurface }}>{text}</Typography>
        </Box>
      </Link>
    </Tooltip>
  );
}

/** One chip, "All evidence 2 h ago" (the oldest of the six), that expands to the six per-domain chips. */
function FreshnessChip({ evidence: raw }: { readonly evidence: OverviewView["evidence"] }) {
  const [open, setOpen] = useState(false);
  const evidence = raw as unknown as Record<string, EvidenceChip | undefined>;
  const chips = EVIDENCE_KEYS.map(([k]) => evidence[k]);
  const missing = chips.filter((c) => !c || c.state !== "OK").length;
  const oldest = chips.filter((c) => c?.state === "OK" && c.at).map((c) => c!.at as string).sort()[0] ?? null;
  const tone = missing > 0 ? "neutral" : ageTone(oldest);
  // The oldest of the six latest runs -- "latest evidence", not a claim that every device was read (review 2026-09-23).
  const label = missing === chips.length ? "Data freshness UNKNOWN" : `Latest data ${relativeAge(oldest)}${missing ? ` · ${missing} UNKNOWN` : ""}`;
  return (
    <Box sx={{ display: "flex", flexDirection: "column", alignItems: "flex-end", gap: 0.75 }}>
      <Box component="button" type="button" aria-expanded={open} onClick={() => setOpen(!open)}
        sx={{ display: "flex", alignItems: "center", gap: 0.75, px: 1.25, py: 0.5, borderRadius: "999px", cursor: "pointer",
              border: `1px solid ${m3.outlineVar}`, bgcolor: m3.scLowest, color: m3.onSurface, font: "inherit" }}>
        <Box sx={{ width: 8, height: 8, borderRadius: "50%", bgcolor: STATUS[tone] }} />
        <Typography sx={{ fontSize: 12, fontWeight: 600 }}>{label}</Typography>
        <Typography sx={{ fontSize: 11, color: m3.onSurfaceVar }}>{open ? "hide" : "details"}</Typography>
      </Box>
      <Collapse in={open}>
        <Box sx={{ display: "flex", gap: 0.75, flexWrap: "wrap", justifyContent: "flex-end", maxWidth: 640 }}>
          {EVIDENCE_KEYS.map(([k, name, href]) => <EvidenceDot key={k} label={name} chip={evidence[k]} href={href} />)}
        </Box>
      </Collapse>
    </Box>
  );
}

type Severity = "critical" | "serious" | "warning";
const WORD: Record<Severity, string> = { critical: "Act now", serious: "Review", warning: "Watch" };

/** "+3 since yesterday" / "−2" / "no change"; nothing when the product keeps no history for the figure. */
function Delta({ count, previous }: { readonly count: number; readonly previous: number | null | undefined }) {
  if (previous === null || previous === undefined) {
    return <Tooltip title="No stored history for this figure yet"><Box component="span" sx={{ color: m3.onSurfaceVar }}>no history</Box></Tooltip>;
  }
  const d = count - previous;
  return (
    <Tooltip title={`${previous} the day before`}>
      <Box component="span" sx={{ color: m3.onSurfaceVar, fontFamily: MONO }}>{d === 0 ? "no change" : `${d > 0 ? "+" : "−"}${Math.abs(d)} since yesterday`}</Box>
    </Tooltip>
  );
}

/** A posture tile (row C): eyebrow, count "of N", status word chip, thin bar, one context line, delta; one link. */
function PostureTile({ title, count, of, unknown, severity, icon, context, previous, href, big = false }: {
  readonly title: string; readonly count: number; readonly of?: number; readonly unknown: boolean; readonly severity: Severity;
  readonly icon: IconName; readonly context: string; readonly previous?: number | null; readonly href: string; readonly big?: boolean;
}) {
  const active = !unknown && count > 0;
  const fill = active ? STATUS[severity] : STATUS.neutral;
  const ink = active ? STATUS_INK[severity] : m3.onSurfaceVar;
  const word = unknown ? "UNKNOWN" : active ? WORD[severity] : "Clear";
  return (
    <Link href={href} underline="none" sx={{ color: "inherit", display: "block" }} aria-label={`${title}: ${unknown ? "UNKNOWN" : count}${of !== undefined ? ` of ${of}` : ""}, ${word}`}>
      <Card sx={{ ...CARD, p: big ? 2.5 : 2, height: "100%", display: "flex", flexDirection: "column", gap: 0.75, "&:hover": { borderColor: m3.outline } }}>
        <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", gap: 1 }}>
          <Box sx={{ display: "flex", alignItems: "flex-start", gap: 0.75, color: ink, minWidth: 0 }}>
            <Icon name={icon} size={16} />
            <Typography sx={{ fontSize: 11, lineHeight: "14px", fontWeight: 600, letterSpacing: "0.04em", textTransform: "uppercase", color: m3.onSurfaceVar, minHeight: 28 }}>{title}</Typography>
          </Box>
          <Box sx={{ px: 1, py: 0.2, borderRadius: "6px", fontSize: 11, fontWeight: 700, whiteSpace: "nowrap",
                     bgcolor: active ? `${fill}26` : m3.sc, color: ink }}>{word}</Box>
        </Box>
        <Typography sx={{ fontSize: big ? 44 : 32, lineHeight: 1.1, fontWeight: 700, color: unknown ? m3.neutralInk : m3.onSurface }}>
          {unknown ? "UNKNOWN" : count}
          {!unknown && of !== undefined && <Typography component="span" sx={{ fontSize: big ? 18 : 14, fontWeight: 500, color: m3.onSurfaceVar }}> of {of}</Typography>}
        </Typography>
        {/* A bar only where it has a denominator (review 2026-09-23: a full red bar under a bare count said nothing). */}
        {of !== undefined && of > 0 ? (
          <Box sx={{ height: 4, borderRadius: "2px", bgcolor: m3.sc, overflow: "hidden" }}>
            <Box sx={{ width: `${Math.min(100, (100 * count) / of)}%`, height: "100%", bgcolor: fill }} />
          </Box>
        ) : <Box sx={{ height: 4 }} />}
        <Typography variant="caption" sx={{ color: m3.onSurfaceVar, fontSize: big ? 13 : 11.5 }}>{context}</Typography>
        {!unknown && <Typography variant="caption" sx={{ fontSize: big ? 13 : 11.5 }}><Delta count={count} previous={previous} /></Typography>}
      </Card>
    </Link>
  );
}

/** Legend text only: the full label stays in the tooltip and the link. */
function shortLabel(vendor: "check_point" | "palo_alto", kind: "major" | "minor" | "model", label: string): string {
  if (vendor === "check_point" && kind === "minor") return label.replace(/\s*Jumbo\s+Take\s+/i, " · Take ");
  if (vendor === "check_point" && kind === "model") return label.replace(/^Check Point\s+/i, "");
  return label;
}

function VendorVersions({ vendor, name, majorTitle, minorTitle, modelTitle, versions, fallbackMinor, total }: {
  readonly vendor: "check_point" | "palo_alto"; readonly name: string; readonly majorTitle: string; readonly minorTitle: string;
  readonly modelTitle: string;
  readonly versions: { major: VersionSlice[]; minor: VersionSlice[]; model?: VersionSlice[] } | undefined;
  readonly fallbackMinor?: Array<{ level: string | null; count: number }>;
  readonly total: number;
}) {
  const accent = vendor === "check_point" ? m3.cp : m3.pan;
  const all = q({ screen: "inventory", vendor });
  const link = (kind: "major" | "minor" | "model", label: string | null) => {
    const value = label ?? "unknown";
    if (kind === "major") return q({ screen: "inventory", vendor, sw_major: value });
    if (kind === "model") return q({ screen: "inventory", vendor, hw_model: value });
    return vendor === "check_point" ? q({ screen: "inventory", vendor, hotfix_level: value }) : q({ screen: "inventory", vendor, sw_version: value });
  };
  const minorRaw = versions?.minor ?? (fallbackMinor ?? []).map((h) => ({ label: h.level, count: h.count }));
  // UI review 2026-11-05, C5: ranked bars, largest first, full labels (the donuts repeated the total and cut the names).
  const part = (kind: "major" | "minor" | "model", title: string, raw: readonly VersionSlice[]) => (
    <RankedBars key={kind} title={title} rows={rankRows(raw.map((s) => ({ ...s, href: link(kind, s.label) })), { otherHref: all })
      .map((r) => (r.kind === "named" ? { ...r, display: shortLabel(vendor, kind, r.display) } : r))} />
  );
  return (
    <Card sx={CARD}>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", mb: 1.5 }}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
          <Box sx={{ width: 9, height: 9, borderRadius: "2px", bgcolor: accent }} />
          <Typography sx={{ fontSize: 16, fontWeight: 600 }}>{name}</Typography>
        </Box>
        <Link href={all} sx={{ fontSize: 13 }}>{total} devices</Link>
      </Box>
      <Typography data-testid="version-verdict" sx={{ fontSize: 13.5, fontWeight: 600, color: m3.onSurface, mb: 1.5 }}>
        {majorVersionVerdict(vendor, versions?.major ?? [])}
      </Typography>
      <Box sx={{ display: "grid", gap: 2.5, gridTemplateColumns: "repeat(auto-fit, minmax(220px, 1fr))" }}>
        {part("major", majorTitle, versions?.major ?? [])}
        {part("minor", minorTitle, minorRaw)}
        {part("model", modelTitle, versions?.model ?? [])}
      </Box>
    </Card>
  );
}

/** Link text for the Jobs filter: the reason up to its first folded number. */
function reasonSearch(reason: string): string {
  return reason.replace(/(^|[^A-Za-z])N([^A-Za-z]|$).*$/, "$1").trim();
}

/** Failed jobs, 24 h, grouped by cause first, then the contract's list as the three latest rows. */
function FailedJobsCard({ total, reasons, rows, allHref, latest = true }: {
  readonly total: number; readonly reasons: readonly FailureReason[];
  readonly rows: OverviewView["exceptions"]["failed_jobs"]["rows"]; readonly allHref: string; readonly latest?: boolean;
}) {
  const max = Math.max(1, ...reasons.map((r) => r.count));
  return (
    <Card sx={CARD}>
      <SectionTitle icon="operations" title="Why jobs failed" hint={total > 0 ? "last 24 h, grouped by cause" : undefined}
        right={total > 0 ? <Link href={allHref} sx={{ fontSize: 13 }}>Show all · {total}</Link> : null} />
      {total === 0 ? (
        <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>No failed jobs in the last 24 h.</Typography>
      ) : (
        <>
          {reasons.map((r) => (
            <Link key={r.reason} href={q({ screen: "operations", tab: "jobs", state: "FAILED", since_hours: "24", q: reasonSearch(r.reason) })}
              underline="none" sx={{ color: "inherit", display: "block", mb: 1.25 }}>
              <Box sx={{ display: "flex", justifyContent: "space-between", gap: 1, alignItems: "baseline" }}>
                <Typography sx={{ fontSize: 12.5, color: m3.onSurface, fontFamily: MONO, overflowWrap: "anywhere" }}>{r.reason}</Typography>
                <Typography sx={{ fontSize: 15, fontWeight: 700, color: m3.onSurface }}>{r.count}</Typography>
              </Box>
              <Box sx={{ height: 5, borderRadius: "3px", bgcolor: m3.sc, my: 0.5 }}>
                <Box sx={{ width: `${(100 * r.count) / max}%`, minWidth: 4, height: "100%", borderRadius: "3px", bgcolor: STATUS.critical }} />
              </Box>
              <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
                {r.devices} device{r.devices === 1 ? "" : "s"} · {r.job_types.map((t) => jobTypeLabel(t)).join(", ")} · last {relativeAge(r.last_at)}
              </Typography>
            </Link>
          ))}
          {latest && (
            <>
              <Typography variant="caption" sx={{ color: m3.onSurfaceVar, letterSpacing: "0.06em", fontWeight: 600, display: "block", mt: 2, mb: 0.5 }}>LATEST</Typography>
              {rows.slice(0, 3).map((r) => (
                <Link key={r.job_id} href={q({ screen: "operations", tab: "jobs", q: r.job_id })} underline="none"
                  sx={{ color: "inherit", display: "block", py: 0.75, borderTop: `1px solid ${m3.outlineVar}` }}>
                  <Box sx={{ display: "flex", justifyContent: "space-between", gap: 1 }}>
                    <Typography sx={{ fontSize: 12.5, fontWeight: 600 }}>{r.label ?? r.device_id.slice(0, 8)} <Box component="span" title={r.job_type} sx={{ fontWeight: 400, color: m3.onSurfaceVar }}>· {jobTypeLabel(r.job_type)}</Box></Typography>
                    <Typography sx={{ fontSize: 12 }}><Ts at={r.finished_at} seconds={false} /></Typography>
                  </Box>
                  <Typography noWrap title={r.terminal_reason ?? ""} sx={{ fontSize: 11.5, color: m3.onSurfaceVar }}>{r.terminal_reason ?? "—"}</Typography>
                </Link>
              ))}
            </>
          )}
        </>
      )}
    </Card>
  );
}

/**
 * When each active gateway last had a policy installed, as the gateway reports it (read every evening at 23:00):
 * counts per calendar day and the oldest date. Dates only -- no threshold, no "outdated" word (PO, 2026-09-23).
 */
function PolicyInstallCard({ p, hideOpenLink = false }: { readonly p: OverviewView["policy_install"]; readonly hideOpenLink?: boolean }) {
  const buckets: Array<[string, number | undefined]> = [
    ["Today", p?.today], ["Yesterday", p?.yesterday], ["2–7 days ago", p?.days_2_7], ["Earlier", p?.older], ["Not read yet", p?.unknown],
  ];
  return (
    <Card sx={CARD}>
      <SectionTitle icon="config" title="Policy installed" hint={`as each gateway reports it · read every evening 23:00${p?.last_read_at ? ` · last read ${relativeAge(p.last_read_at)}` : ""}`}
        right={hideOpenLink ? undefined : <Link href="?screen=inventory" sx={{ fontSize: 13 }}>Open Configuration</Link>} />
      {!p || p.state !== "OK" ? (
        <StatePanel variant="not_evaluated" title="Not read yet" body="The first read runs at 23:00 with the nightly inventory collection." />
      ) : (
        <>
          <Box sx={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(120px, 1fr))", gap: 1.5 }}>
            {buckets.map(([label, n]) => (
              <Box key={label} sx={{ border: `1px solid ${m3.outlineVar}`, borderRadius: "8px", px: 1.5, py: 1 }}>
                <Typography sx={{ fontSize: 12, color: m3.onSurfaceVar }}>{label}</Typography>
                <Typography sx={{ fontSize: 20, fontWeight: 600, fontVariantNumeric: "tabular-nums" }}>{n ?? 0}</Typography>
              </Box>
            ))}
          </Box>
          <Typography variant="body2" sx={{ mt: 1.25, color: m3.onSurfaceVar }}>
            {p.of} gateways · oldest install {p.oldest_at ? <Ts at={p.oldest_at} /> : "UNKNOWN"} · newest {p.newest_at ? <Ts at={p.newest_at} /> : "UNKNOWN"}
          </Typography>
        </>
      )}
    </Card>
  );
}

function ComplianceCard({ c, evidenceAt }: { readonly c: OverviewView["compliance"]; readonly evidenceAt: string | null | undefined }) {
  return (
    <Card sx={CARD}>
      <SectionTitle icon="compliance" title="Compliance by framework" hint={`control checks (control × firewall) · evidence ${relativeAge(evidenceAt)}`}
        right={<Link href="?screen=compliance" sx={{ fontSize: 13 }}>Open Compliance</Link>} />
      {c.state !== "OK" ? (
        <StatePanel variant="not_evaluated" title="Compliance has not been evaluated yet" body={<>Open <Link href="?screen=compliance">Compliance</Link> to evaluate.</>} />
      ) : (
        <>
          <Box sx={{ display: "flex", gap: 1, flexWrap: "wrap", mb: 2 }}>
            <StatusChip tone="neutral" label={`${c.evaluated} of ${c.of_firewalls} firewalls evaluated`} />
            <Tooltip title="Assured: passing checks among all assigned checks (a check without evidence counts against). Observed: passing checks among checks that could be judged (pass + fail).">
              <Box component="span"><StatusChip tone="neutral" label={`Assured ${c.assured_pct}% · observed ${c.observed_pct}% · coverage ${c.coverage_pct}%`} /></Box>
            </Tooltip>
            <Link href={q({ screen: "compliance", severity: "critical", result: "fail" })} underline="none"><StatusChip tone={(c.critical_deficiencies ?? 0) > 0 ? "bad" : "neutral"} label={`${c.critical_deficiencies} critical failing checks`} /></Link>
            <Link href={q({ screen: "compliance", result: "unavailable" })} underline="none"><StatusChip tone={(c.data_gaps ?? 0) > 0 ? "warn" : "neutral"} label={`${c.data_gaps} data gaps`} /></Link>
            {(c.pending_reevaluation ?? 0) > 0 && (
              <Tooltip title="Their configuration or the rule set changed; they show their previous evaluation until the background re-evaluation (within a minute) completes.">
                <Box component="span"><StatusChip tone="neutral" label={`${c.pending_reevaluation} being re-evaluated`} /></Box>
              </Tooltip>
            )}
          </Box>
          {(c.frameworks ?? []).map((f) => (
            <Link key={f.name} href={q({ screen: "compliance", framework: f.name })} underline="none" sx={{ color: "inherit", display: "block", mb: 1.5 }}>
              <Box sx={{ display: "flex", justifyContent: "space-between", mb: 0.5 }}>
                <Typography sx={{ fontSize: 13, fontWeight: 600 }}>{f.name}</Typography>
                <Typography sx={{ fontSize: 12.5, color: m3.onSurfaceVar }}>{f.pass} / {f.total} checks pass · <b style={{ color: "inherit" }}>{f.score_pct}%</b></Typography>
              </Box>
              <StackedBar parts={[
                { label: "Pass", count: f.pass, color: STATUS.good },
                { label: "Fail", count: f.fail, color: STATUS.critical },
                { label: "Unavailable (no evidence)", count: f.unavailable, color: STATUS.neutral },
              ]} />
            </Link>
          ))}
          <Box sx={{ display: "flex", gap: 2, mt: 0.5 }}>
            {[["Pass", STATUS.good], ["Fail", STATUS.critical], ["Unavailable", STATUS.neutral]].map(([l, col]) => (
              <Box key={l} sx={{ display: "flex", alignItems: "center", gap: 0.5 }}><Box sx={{ width: 10, height: 10, borderRadius: "3px", bgcolor: col }} /><Typography variant="caption">{l}</Typography></Box>
            ))}
          </Box>
        </>
      )}
    </Card>
  );
}

function ListCard<T>({ icon, title, total, rows, empty, cells, rowHref, allHref }: {
  readonly icon: IconName; readonly title: string; readonly total: number; readonly rows: readonly T[]; readonly empty: string;
  readonly cells: (r: T) => ReactNode; readonly rowHref: (r: T) => string; readonly allHref: string;
}) {
  return (
    <Card sx={CARD}>
      <SectionTitle icon={icon} title={title} right={total > rows.length ? <Link href={allHref} sx={{ fontSize: 13 }}>Show all · {total}</Link> : null} />
      {rows.length === 0 ? (
        <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>{empty}</Typography>
      ) : rows.map((r, i) => (
        <Link key={i} href={rowHref(r)} underline="none" sx={{ color: "inherit", display: "block", py: 0.9, borderTop: i ? `1px solid ${m3.outlineVar}` : "none", "&:hover": { bgcolor: m3.sc } }}>
          {cells(r)}
        </Link>
      ))}
    </Card>
  );
}

/**
 * The devices in the header are the devices on the map: the enrolled ones (`active_devices`). The backend's
 * `enrolled_devices` counts every registered device, drafts included (135 against 133 tiles, UI review 2026-10-17),
 * so the difference is named instead of folded in.
 */
export function estateDevicesLabel(d: { active_devices: number; enrolled_devices?: number }): string {
  const registered = d.enrolled_devices ?? d.active_devices;
  const notEnrolled = Math.max(0, registered - d.active_devices);
  return `${d.active_devices} enrolled devices${notEnrolled > 0 ? ` (${notEnrolled} more registered, not enrolled)` : ""}`;
}

/**
 * "Estate details": policy installed, software and hardware per vendor, inventory evidence age. One component for the
 * Overview's disclosure and the Devices landing (UI review 2026-10-18, P1-1), so the two can not drift; every slice and
 * bucket keeps its link to the filtered device list. {@code onDevicesScreen} drops the one link that would lead back to
 * the screen it is on.
 */
/** The vendors whose versions come only from the device list (no platform donut): one bar each on the Fleet summary (1.5). */
export const REPORTED_VERSION_VENDORS = ["fortinet", "cisco_asa", "bluecoat", "radware", "infoblox"] as const;

/** One population bar per vendor of the versions its enrolled devices report (managers included: a FortiManager is in service too). */
function ReportedVersionBars({ fleet }: { readonly fleet: readonly DeviceSummary[] }) {
  const bars = REPORTED_VERSION_VENDORS.map((vendor) => ({ vendor, slices: reportedVersions(fleet, vendor, true) })).filter((b) => b.slices.length > 0);
  if (bars.length === 0) return null;
  return (
    <Card sx={{ ...CARD, display: "flex", flexDirection: "column", gap: 1.75 }} data-reported-versions>
      <SectionTitle icon="grid" title="Other vendors · version as reported" hint="From the device list · enrolled devices · each slice opens the device list" />
      {bars.map(({ vendor, slices }) => {
        const total = slices.reduce((n, x) => n + x.count, 0);
        return (
          <Box key={vendor}>
            <Typography sx={{ fontSize: 13, fontWeight: 600, mb: 0.25 }}>{vendorDisplayName(vendor)} · {total} {total === 1 ? "device" : "devices"}</Typography>
            <Typography data-testid="version-verdict" sx={{ fontSize: 12.5, color: m3.onSurfaceVar, mb: 0.75 }}>{reportedVersionVerdict(slices)}</Typography>
            <RankedBars title="Version as reported" rows={rankRows(
              slices.map((x) => ({ label: x.label, count: x.count, href: x.label ? q({ screen: "inventory", vendor, sw_version: x.label }) : q({ screen: "inventory", vendor }) })),
              { otherHref: q({ screen: "inventory", vendor }), unknownWord: "Unknown" })} />
          </Box>
        );
      })}
    </Card>
  );
}

export function EstateDetails({ data, onDevicesScreen = false, fleet }: { readonly data: OverviewView; readonly onDevicesScreen?: boolean;
  /** The device list the Devices screen already holds: the other vendors' version slices come from it (1.5). */
  readonly fleet?: readonly DeviceSummary[] }) {
  const age = data.inventory_age;
  const p = data.platform;
  const n = data.nexus;
  const neverRead = age?.never ?? 0;
  return (
      <Box sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
      <PolicyInstallCard p={data.policy_install} hideOpenLink={onDevicesScreen} />

      {/* Row E -- inventory facts, below the fold */}
      <Box>
        <SectionTitle icon="grid" title="Software and hardware" hint={`Per vendor, from the latest reads · facts ${relativeAge(p.evidence_at)} · each slice opens the device list`} />
        <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: { xs: "1fr", lg: "1fr 1fr" } }}>
          <VendorVersions vendor="check_point" name={vendorDisplayName("check_point")} majorTitle="Major version" minorTitle="Jumbo hotfix"
            modelTitle="Appliance" versions={p.versions?.check_point} fallbackMinor={p.hotfix_levels} total={p.check_point ?? 0} />
          <VendorVersions vendor="palo_alto" name={vendorDisplayName("palo_alto")} majorTitle="PAN-OS major" minorTitle="Exact version"
            modelTitle="Model" versions={p.versions?.palo_alto} total={p.palo_alto ?? 0} />
        </Box>
      </Box>
      {fleet && <ReportedVersionBars fleet={fleet} />}

      {/* evidence age (the change and cluster lists are the facts above) */}
      <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: { xs: "1fr", md: "1fr 1fr" } }}>
        <Card sx={CARD}>
          <SectionTitle icon="devices" title="Data freshness per device" hint={`${age?.of ?? 0} active devices · newest read per device`} />
          {/* The one freshness scale (B1): every age has the same colour on every screen. */}
          <Donut title="Last successful inventory" centerLabel="devices" slices={[
            { label: "under 24 h", display: "under 24 h", count: age?.lt24h ?? 0, color: FRESHNESS_COLOR.fresh, href: q({ screen: "inventory", inventory_age: "lt24h" }) },
            { label: "24–72 h", display: "24–72 h", count: age?.h24_72 ?? 0, color: FRESHNESS_COLOR.ageing, href: q({ screen: "inventory", inventory_age: "24h_72h" }) },
            { label: "over 72 h", display: "over 72 h", count: age?.gt72h ?? 0, color: FRESHNESS_COLOR.stale, href: q({ screen: "inventory", inventory_age: "gt72h" }) },
            { label: "never", display: "never", count: neverRead, color: FRESHNESS_COLOR.unknown, href: q({ screen: "inventory", inventory_age: "never" }) },
          ].filter((s) => s.count > 0)} />
          <Box sx={{ mt: 2, pt: 1.5, borderTop: `1px solid ${m3.outlineVar}` }}>
            <Typography sx={{ fontSize: 12.5, color: m3.onSurfaceVar }}>
              <Link href={q({ screen: "operations", tab: "jobs" })} underline="hover">neXus</Link>: completed 24 h <b>{n.completed_24h ?? "UNKNOWN"}</b> · running <b>{n.running ?? "UNKNOWN"}</b> ·
              oldest running {n.oldest_running_submitted_at ? `${relativeAge(n.oldest_running_submitted_at)} (since submitted)` : "none"} ·
              last inventory: Check Point {relativeAge(n.last_inventory?.check_point)} · Palo Alto {relativeAge(n.last_inventory?.palo_alto)}
            </Typography>
          </Box>
        </Card>
      </Box>
      </Box>
  );
}

export function OverviewScreen() {
  const [data, setData] = useState<OverviewView | null>(null);
  const [error, setError] = useState<{ message: string; restricted: boolean } | null>(null);
  const [controls, setControls] = useState<readonly ComplianceControlItem[] | null>(null);
  const [details, setDetails] = useState(false);
  const { wall } = useDisplayMode();
  // HA readiness and the Fortinet / Cisco ASA versions come from the same reads the Operations and Devices screens make.
  // A failed read says UNKNOWN; it is never drawn as zero.
  const [ha, setHa] = useState<HaOverview | "loading" | "unknown">("loading");
  const [fleet, setFleet] = useState<readonly DeviceSummary[] | null>(null);
  useEffect(() => {
    let active = true;
    Promise.all([listDevices(), listCpFailoverSummary()]).then(([{ devices }, rows]) => {
      if (!active) return;
      if (!Array.isArray(devices) || !Array.isArray(rows)) { setHa("unknown"); setFleet(Array.isArray(devices) ? devices : null); return; }
      setFleet(devices);
      const byRef: Record<string, typeof rows> = {};
      for (const row of rows) (byRef[row.cluster_member_ref] ??= []).push(row);
      const items = haReadinessItems(groupClusters(devices), byRef);
      const fresh = readinessFreshness(items, new Date());
      const newestOf = (status: string) => items.filter((i) => i.status === status).map((i) => i.base?.readiness?.observedAt)
        .filter((t): t is string => Boolean(t)).sort().at(-1) ?? null;
      setHa({ ...readinessCounts(items), evaluated: fresh.evaluatedAts.length, stale: fresh.staleAts.length, mostlyStale: fresh.mostlyStale,
        notReadyAt: newestOf("NOT_READY"), unknownAt: newestOf("UNKNOWN") });
    }).catch(() => { if (active) { setHa("unknown"); setFleet(null); } });
    return () => { active = false; };
  }, []);

  const load = useCallback(() => {
    getOverview().then((d) => {
      setData(d); setError(null);
      getComplianceControls().then((r) => setControls(Array.isArray(r?.controls) ? r.controls : null)).catch(() => setControls(null));
    })
      .catch((e: { status?: number; message?: string }) => setError({
        message: e?.message ?? `The overview could not be read (status ${e?.status ?? "?"})`, restricted: isRestricted(e) }));
  }, []);

  useEffect(() => {
    load();
    const t = setInterval(load, 60_000);
    const onFocus = () => load();
    window.addEventListener("focus", onFocus);
    return () => { clearInterval(t); window.removeEventListener("focus", onFocus); };
  }, [load]);

  if (error) {
    return (
      <ScreenRoot>
        <ScreenHeader title="Overview" subtitle="The overview could not be read" />
        {error.restricted
          ? <StatePanel variant="restricted" title="Overview" body="This account can not view the overview." />
          : <StatePanel variant="error" title="Overview unavailable" body="The overview could not be read. It retries every 60 s." code={error.message}
              action={<Link component="button" onClick={load}>Retry</Link>} />}
      </ScreenRoot>
    );
  }
  if (!data) {
    return <ScreenRoot><ScreenHeader title="Overview" subtitle="Reading the fleet…" /></ScreenRoot>;
  }

  const a = data.attention;
  const ex = data.exceptions;
  const age = data.inventory_age;
  const c = data.compliance;
  const p = data.platform;
  const n = data.nexus;
  const d = data.denominators;
  const failed = a.failed_jobs_24h?.count ?? 0;
  const tileUnknown = (t: CountTile | undefined) => !t || t.state !== "OK";
  const neverRead = age?.never ?? 0;

  // Row B -- three lines, Act now / Review / Evidence (review §2 wording: "n of N", no adjectives).
  const actNow: string[] = [];
  if (!tileUnknown(a.failed_jobs_24h)) actNow.push(failed > 0
    ? `${failed} of ${a.failed_jobs_24h.terminal_24h} jobs failed in 24 h${a.failed_jobs_24h.last_at ? `, latest ${relativeAge(a.failed_jobs_24h.last_at)}` : ""}.`
    : "No job failed in 24 h.");
  if (!tileUnknown(a.backup_missing)) actNow.push(a.backup_missing.count > 0
    ? `${a.backup_missing.count} of ${a.backup_missing.of} backup targets have no archive.`
    : `Every one of ${a.backup_missing.of} backup targets holds an archive.`);
  const review: string[] = [];
  if (!tileUnknown(a.cluster_diff)) review.push(`${a.cluster_diff.count} of ${a.cluster_diff.of} active clusters show member differences${a.cluster_diff.unknown ? ` (${a.cluster_diff.unknown} not comparable)` : ""}.`);
  if (a.managed_not_enrolled && !tileUnknown(a.managed_not_enrolled) && a.managed_not_enrolled.count > 0) {
    review.push(`${a.managed_not_enrolled.count} gateway${a.managed_not_enrolled.count === 1 ? "" : "s"} managed by ${a.managed_not_enrolled.of === 1 ? "the management server" : `${a.managed_not_enrolled.of} management servers`} ${a.managed_not_enrolled.count === 1 ? "is" : "are"} not in neXus.`);
  }
  if (!tileUnknown(a.config_changed)) review.push(`${a.config_changed.count} device${a.config_changed.count === 1 ? "" : "s"} changed configuration since the previous collection.`);
  const evidence: string[] = [];
  evidence.push(`Evidence read in 24 h for ${age?.lt24h ?? 0} of ${age?.of ?? d.active_devices} active devices${neverRead ? `; ${neverRead} never read` : ""}.`);
  evidence.push(c.state === "OK"
    ? `Compliance evidence covers ${c.coverage_pct}% of control checks: ${c.critical_deficiencies} critical failing checks, ${c.data_gaps} data gaps.`
    : "Compliance not evaluated.");

  const headline = (
    <Card sx={{ ...CARD, p: { xs: 2, md: 2.5 }, bgcolor: m3.scLowest }}>
      {([["Act now", actNow, STATUS_INK.critical], ["Review", review, STATUS_INK.serious], ["Evidence", evidence, m3.onSurfaceVar]] as Array<[string, string[], string]>).map(([word, lines, ink]) => (
        <Box key={word} sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", sm: "96px 1fr" }, gap: { xs: 0, sm: 2 }, py: 0.6 }}>
          <Typography sx={{ fontSize: wall ? 22 : 15, fontWeight: 700, color: ink }}>{word}</Typography>
          <Typography sx={{ fontSize: wall ? 22 : 15, lineHeight: 1.45, color: m3.onSurface }}>{lines.length ? lines.join(" ") : "Nothing to report."}</Typography>
        </Box>
      ))}
    </Card>
  );

  const tiles = (
    <Box sx={{ display: "grid", gap: 1.5, gridTemplateColumns: "repeat(auto-fit, minmax(min(100%, 200px), 1fr))" }}>
      <PostureTile big={wall} icon="operations" severity="critical" title="Failed jobs, 24 h" count={failed} of={a.failed_jobs_24h?.terminal_24h}
        unknown={tileUnknown(a.failed_jobs_24h)} previous={a.failed_jobs_24h?.previous}
        context={`${a.failed_jobs_24h?.last_at ? `latest ${relativeAge(a.failed_jobs_24h.last_at)}` : "no failure in 24 h"}${a.failed_jobs_24h?.other_terminal_24h ? ` · +${a.failed_jobs_24h.other_terminal_24h} outcome unknown / rejected` : ""}`}
        href={q({ screen: "operations", tab: "jobs", state: "FAILED", since_hours: "24" })} />
      <PostureTile big={wall} icon="backup" severity="critical" title="Backup targets without archive" count={a.backup_missing?.count ?? 0} of={a.backup_missing?.of}
        unknown={tileUnknown(a.backup_missing)} previous={a.backup_missing?.previous}
        context={`latest backup ${relativeAge(data.evidence.backup?.at)}`} href={q({ screen: "backups", artefact: "none" })} />
      <PostureTile big={wall} icon="config" severity="serious" title="Clusters with member differences" count={a.cluster_diff?.count ?? 0} of={a.cluster_diff?.of}
        unknown={tileUnknown(a.cluster_diff)} previous={a.cluster_diff?.previous}
        context={`${(a.cluster_diff?.of ?? 0) - (a.cluster_diff?.count ?? 0)} in agreement${a.cluster_diff?.unknown ? ` · ${a.cluster_diff.unknown} not comparable` : ""} · interface physical settings and member addresses excluded`}
        href={q({ screen: "inventory", cluster_diff: "present" })} />
      <PostureTile big={wall} icon="config" severity="serious" title="Configuration changed" count={a.config_changed?.count ?? 0} of={a.config_changed?.of}
        unknown={tileUnknown(a.config_changed)} previous={a.config_changed?.previous}
        context={`since the previous collection · latest ${relativeAge(data.evidence.configuration?.at)}`} href={q({ screen: "inventory", change_state: "changed" })} />
      <PostureTile big={wall} icon="devices" severity="warning" title="Devices without evidence, 24 h" count={a.stale_inventory?.count ?? 0} of={a.stale_inventory?.of}
        unknown={tileUnknown(a.stale_inventory)} previous={a.stale_inventory?.previous}
        context={`${age?.h24_72 ?? 0} aging · ${age?.gt72h ?? 0} stale · ${neverRead} never read`} href={q({ screen: "inventory", inventory_age: "stale" })} />
      <PostureTile big={wall} icon="compliance" severity="critical" title="Compliance deficiencies" count={c.state === "OK" ? (c.critical_deficiencies ?? 0) : 0}
        unknown={c.state !== "OK"} previous={null}
        context={c.state === "OK" ? `critical failing checks (control × firewall) · ${c.coverage_pct}% coverage · ${c.data_gaps} data gaps` : "not evaluated"}
        href={q({ screen: "compliance", severity: "critical", result: "fail" })} />
    </Box>
  );

  const failedCard = (
    <FailedJobsCard total={ex.failed_jobs?.total ?? 0} reasons={ex.failed_jobs?.reasons ?? []} rows={ex.failed_jobs?.rows ?? []} latest={!wall}
      allHref={q({ screen: "operations", tab: "jobs", state: "FAILED", since_hours: "24" })} />
  );

  if (wall) {
    return (
      <ScreenRoot>
        {headline}
        {tiles}
        <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: { xs: "1fr", lg: (n.running ?? 0) > 0 ? "2fr 1fr" : "3fr 1fr" }, alignItems: "start" }}>
          {failedCard}
          <Card sx={CARD}>
            <SectionTitle icon="operations" title="Queue" hint="neXus jobs" />
            <Typography sx={{ fontSize: 40, fontWeight: 700 }}>{n.running ?? "UNKNOWN"}<Typography component="span" sx={{ fontSize: 16, color: m3.onSurfaceVar }}> running</Typography></Typography>
            <Typography sx={{ color: m3.onSurfaceVar }}>completed in 24 h: <b>{n.completed_24h ?? "UNKNOWN"}</b></Typography>
            <Typography sx={{ color: m3.onSurfaceVar }}>oldest running: {n.oldest_running_submitted_at ? relativeAge(n.oldest_running_submitted_at) : "none"}</Typography>
            <Typography sx={{ color: m3.onSurfaceVar }}>last inventory: Check Point {relativeAge(n.last_inventory?.check_point)} · Palo Alto {relativeAge(n.last_inventory?.palo_alto)}</Typography>
          </Card>
        </Box>
      </ScreenRoot>
    );
  }

  const clustersEnrolled = d.clusters_enrolled ?? d.clusters;

  // Executive home (UI review 2026-11-05, C1; Argon §3 amended by Fable): verdict word and freshness sentence, four
  // pillars, at most five "needs attention" lines with their age, then -- below the fold -- the framework bars, the
  // 24 h change pulse and the estate map. Plain words for an executive reader (C7): data freshness, backup coverage,
  // policies. Every figure comes from the reads this screen already made; UNKNOWN is never drawn as zero.
  const est = data.estate;
  const crit = est?.facts.critical;
  const bk = est?.facts.backups;
  const ev = est?.facts.evidence;
  const widespread = (controls ?? []).filter((x) => x.severity === "CRITICAL" && x.fail_count > 0)
    .sort((x, y) => y.fail_count - x.fail_count || (x.vendors?.[0] ?? "").localeCompare(y.vendors?.[0] ?? "") || x.control_id.localeCompare(y.control_id));
  const top = widespread[0];
  const changed = tileUnknown(a.config_changed) ? null : { count: a.config_changed.count, of: a.config_changed.of ?? 0 };
  const cd = tileUnknown(a.cluster_diff) ? null : a.cluster_diff;

  const security = crit && typeof crit.k === "number" && typeof crit.n === "number" ? { failing: crit.k, of: crit.n } : null;
  const backups = bk ? { archived: bk.a, of: bk.b }
    : !tileUnknown(a.backup_missing) && a.backup_missing.of !== undefined ? { archived: a.backup_missing.of - a.backup_missing.count, of: a.backup_missing.of } : null;
  const freshness: FleetFreshness | null = ev ? { fresh: ev.r, of: ev.d, never: ev.never }
    : age && (age.state ?? "OK") === "OK" ? { fresh: age.lt24h, of: age.of, never: age.never } : null;
  const haFig = typeof ha === "object" ? ha : null;
  const reads = (est?.devices ?? []).map((x) => x.last_read_at).filter((t): t is string => Boolean(t)).sort();
  const newestRead = data.evidence.inventory?.at ?? reads.at(-1) ?? null;
  const oldestRead = reads[0] ?? null;
  const figures = { security, backups, ha: haFig, freshness };
  const verdict = ha === "loading" ? null : executiveVerdict(figures);
  const attention = attentionLines({
    ...figures,
    ages: { haNotReadyAt: haFig?.notReadyAt, haUnknownAt: haFig?.unknownAt, backupAt: data.evidence.backup?.at,
      complianceAt: data.evidence.compliance?.at, oldestReadAt: oldestRead },
    topControl: top ? { id: top.control_id, title: top.title, failing: top.fail_count, of: top.target_device_count } : null,
  });
  const block = (titleText: string, hint: string | null, link: { href: string; label: string } | null, children: ReactNode, testId?: string) => (
    <Card sx={{ ...CARD, display: "flex", flexDirection: "column", gap: 1.5 }} data-testid={testId}>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", gap: 1 }}>
        <Box>
          <Typography sx={{ fontSize: 16, fontWeight: 700 }}>{titleText}</Typography>
          {hint && <Typography sx={{ fontSize: 12.5, color: m3.onSurfaceVar }}>{hint}</Typography>}
        </Box>
        {link && <Link href={link.href} underline="hover" sx={{ fontSize: 13, whiteSpace: "nowrap" }}>{link.label} →</Link>}
      </Box>
      {children}
    </Card>
  );
  const verdictInk = verdict?.word === "HEALTHY" ? STATUS_INK.good : verdict?.word === "AT RISK" ? STATUS_INK.critical : m3.neutralInk;
  const verdictBg = verdict?.word === "HEALTHY" ? `${STATUS.good}1f` : verdict?.word === "AT RISK" ? `${STATUS.critical}1f` : m3.sc;
  const frameworks = primaryFrameworksFirst(c.frameworks ?? [], (f) => f.name);
  const pi = data.policy_install;
  const pulse: Array<{ key: string; label: string; value: string; hint: string; href: string }> = [
    { key: "config", label: "Configuration changed", hint: "since each device's previous read",
      value: changed && changed.of > 0 ? `${changed.count} of ${changed.of} devices` : "UNKNOWN", href: q({ screen: "inventory", change_state: "changed" }) },
    { key: "cluster", label: "Cluster member differences", hint: "a review signal, not a fault",
      value: cd && (cd.of ?? 0) > 0 ? `${cd.count} of ${cd.of} clusters${cd.unknown ? ` · ${cd.unknown} not comparable` : ""}` : "UNKNOWN",
      href: q({ screen: "inventory", cluster_diff: "present" }) },
    { key: "policy", label: "Policies installed today", hint: `as each gateway reports it${pi?.last_read_at ? ` · read ${relativeAge(pi.last_read_at)}` : ""}`,
      value: pi && pi.state === "OK" ? `${pi.today ?? 0} of ${pi.of ?? 0} gateways` : "UNKNOWN", href: q({ screen: "policy" }) },
  ];

  return (
    <ScreenRoot>
      <ScreenHeader
        title="Overview"
        subtitle={`Estate assurance · ${estateDevicesLabel(d)} · ${clustersEnrolled} clusters · as of ${formatUtc(data.generated_at, false)} ${DISPLAY_TZ_LABEL}`}
        actions={<FreshnessChip evidence={data.evidence} />}
      />

      <StaleEvidenceBanner freshness={freshness} newestAt={newestRead} />

      {/* 1. The verdict word and the freshness sentence */}
      <Card sx={{ ...CARD, p: { xs: 2, md: 2.5 } }} data-testid="executive-verdict">
        <Box sx={{ display: "flex", gap: 2, alignItems: "center", flexWrap: "wrap" }}>
          <Box aria-label={`Estate verdict: ${verdict?.word ?? "reading"}`} data-verdict={verdict?.word ?? "LOADING"}
            sx={{ px: 2, py: 0.75, borderRadius: "10px", bgcolor: verdictBg, color: verdictInk, fontSize: 26, fontWeight: 800, letterSpacing: "0.04em", whiteSpace: "nowrap" }}>
            {verdict?.word ?? "…"}
          </Box>
          <Box sx={{ minWidth: 0, flex: "1 1 260px" }}>
            <Typography sx={{ fontSize: 15, fontWeight: 600, color: m3.onSurface }}>{verdict?.reason ?? "Reading the clusters and their readiness…"}</Typography>
            <Typography data-testid="freshness-sentence" sx={{ fontSize: 13.5, color: m3.onSurfaceVar }}>{freshnessSentence(freshness, newestRead, oldestRead)}</Typography>
          </Box>
        </Box>
      </Card>

      {/* 2. Four pillars */}
      <Box sx={{ display: "grid", gap: 1.5, gridTemplateColumns: { xs: "1fr", sm: "1fr 1fr", lg: "repeat(4, 1fr)" } }}>
        <Card sx={{ ...CARD, p: 0.5 }}>
          <HeroFact label="Security posture" value={security?.failing ?? null} of={security?.of ?? null} unit="firewalls" bad={security?.failing ?? 0} color={EXEC.red}
            sliverLabel="with a critical failure" unknownWord="UNKNOWN"
            sentence={security?.of ? "firewalls with at least one critical check failing" : "No firewall is assessed yet"}
            href={q({ screen: "compliance", severity: "critical", result: "fail" })} />
        </Card>
        <Card sx={{ ...CARD, p: 0.5 }}>
          <HeroFact label="Backup coverage" value={backups ? backups.archived : null} of={backups ? backups.of : null} unit="targets backed up"
            bad={backups ? backups.of - backups.archived : 0} color={EXEC.red} sliverLabel="without a backup" unknownWord="UNKNOWN"
            sentence={backups && backups.of - backups.archived > 0 ? `${backups.of - backups.archived} backup targets have no stored backup` : "Every backup target holds a stored backup"}
            href={q({ screen: "backups", artefact: "none" })} />
        </Card>
        <Card sx={{ ...CARD, p: 0.5 }}>
          {/* HA readiness (UI review 2026-10-18, F-13): the same counts as Operations, with the age of the evaluations. */}
          <HeroFact label="HA readiness" unit="clusters ready" unknownWord={ha === "loading" ? "Loading…" : ha === "unknown" ? "UNKNOWN" : "No clusters"}
            value={haFig && haFig.total > 0 ? haFig.ready : null} of={haFig ? haFig.total : null}
            bad={0} color={EXEC.red} href={q({ screen: "operations" })}
            ariaLabel={haFig ? `HA readiness: ${haReadinessSentence(haFig)}` : `HA readiness: ${ha === "loading" ? "Loading" : "UNKNOWN"}`}
            segments={haFig ? [
              { count: haFig.notReady, color: EXEC.red, label: "not ready" },
              { count: haFig.unknown, color: EXEC.hatch, label: "unknown" },
            ] : undefined}
            aside={haFig && haFig.mostlyStale ? (
              <Tooltip title={`${haFig.stale} of ${haFig.evaluated} readiness evaluations are older than 24 h; the counts may no longer hold`}>
                <Box component="span" data-stale-evaluation sx={{ px: 0.9, py: 0.1, borderRadius: "6px", fontSize: 11, fontWeight: 700,
                  bgcolor: m3.warningContainer, color: m3.onWarningContainer }}>Stale</Box>
              </Tooltip>) : undefined}
            sentence={haFig ? haReadinessSentence(haFig) : ha === "loading" ? "Reading the clusters and their readiness…" : "UNKNOWN: the clusters or their readiness were not available."} />
        </Card>
        <Card sx={{ ...CARD, p: 0.5 }}>
          <HeroFact label="Data freshness" value={freshness ? freshness.fresh : null} of={freshness ? freshness.of : null} unit="devices" bad={freshness ? freshness.of - freshness.fresh : 0}
            color={FRESHNESS_COLOR.ageing} sliverLabel="not read in 24 h" unknownWord="UNKNOWN"
            sentence={`read in the last 24 h${freshness?.never ? ` · ${freshness.never} never read` : ""}`} href={q({ screen: "inventory", inventory_age: "stale" })} />
        </Card>
      </Box>

      {/* 3. Needs attention: at most five lines, most severe first, each with its age and one link to the filtered problem */}
      {block("Needs attention", attention.length ? `${attention.length === MAX_ATTENTION_LINES ? "the five most severe" : "most severe first"} · each line opens the filtered list` : null, null,
        attention.length === 0 ? (
          <Typography sx={{ color: m3.onSurfaceVar }}>
            {verdict?.word === "HEALTHY" ? "Nothing needs attention." : "No known problem in the data that could be read."}
          </Typography>
        ) : (
          <Box component="ol" sx={{ listStyle: "none", m: 0, p: 0 }}>
            {attention.map((l, i) => (
              <Box component="li" key={l.key} data-attention={l.key} sx={{ borderTop: i ? `1px solid ${m3.outlineVar}` : "none" }}>
                <Link href={l.href} underline="none" sx={{ color: "inherit", display: "flex", flexWrap: "wrap", alignItems: "baseline", columnGap: 1.25, rowGap: 0.25, py: 1, "&:hover": { bgcolor: m3.scLow } }}>
                  <Box component="span" aria-hidden sx={{ width: 10, height: 10, borderRadius: "50%", flex: "none", bgcolor: STATUS[l.severity], alignSelf: "center" }} />
                  <Typography component="span" sx={{ fontSize: 14.5, fontWeight: 600, color: m3.onSurface, flex: "1 1 220px", minWidth: 0 }}>{l.text}</Typography>
                  <Typography component="span" sx={{ fontSize: 12.5, color: m3.onSurfaceVar, whiteSpace: "nowrap", ml: "auto" }}>
                    {l.ageOf} {l.at ? relativeAge(l.at) : "UNKNOWN"} →
                  </Typography>
                </Link>
              </Box>
            ))}
          </Box>
        ), "needs-attention")}

      {/* Below the fold: framework bars and the 24 h change pulse */}
      <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: { xs: "1fr", lg: "1fr 1fr" } }}>
        {block("Compliance by framework", "Technical checks. Not an audit certification.", { href: q({ screen: "compliance" }), label: "Open" },
          c.state === "OK" && frameworks.length > 0 ? (
            <Box sx={{ display: "flex", flexDirection: "column", gap: 1.75 }}>
              {frameworks.map((f) => {
                const t = f.pass + f.fail + f.unavailable;
                const name = f.name === "Financial Baseline" ? "Financial baseline" : f.name;
                const s = evidencedShares(f.pass, f.fail, f.unavailable);
                return (
                  <Box key={f.name} data-framework={f.name}>
                    <Box sx={{ display: "flex", justifyContent: "space-between", gap: 1, mb: 0.5, flexWrap: "wrap" }}>
                      <Link href={q({ screen: "compliance", framework: f.name })} underline="hover" sx={{ fontSize: 13.5, fontWeight: 600, color: m3.onSurface }}>{name}</Link>
                      <Tooltip title="A check is one control on one firewall. A check without evidence is not counted as failed.">
                        <Typography sx={{ fontSize: 12.5, color: m3.onSurfaceVar }}>
                          {t ? <><b style={{ color: m3.onSurface }}>{s.passPct === null ? "UNKNOWN" : `${s.passPct}%`}</b> of evidenced controls pass · {s.noEvidencePct}% have no evidence</> : "Not assessed"}
                        </Typography>
                      </Tooltip>
                    </Box>
                    <Box sx={{ display: "flex", height: 14, borderRadius: 7, overflow: "hidden", gap: "2px" }}>
                      {[{ n: "Pass", c: f.pass, bg: EXEC.green }, { n: "Fail", c: f.fail, bg: EXEC.red }, { n: "No evidence", c: f.unavailable, bg: EXEC.hatch }]
                        .filter((x) => x.c > 0).map((x) => (
                          <Tooltip key={x.n} title={`${x.n}: ${x.c}`}><Box sx={{ width: `${(100 * x.c) / Math.max(1, t)}%`, background: x.bg }} /></Tooltip>
                        ))}
                    </Box>
                  </Box>
                );
              })}
              <Box sx={{ display: "flex", gap: 2 }}>
                {[{ n: "Pass", bg: EXEC.green }, { n: "Fail", bg: EXEC.red }, { n: "No evidence", bg: EXEC.hatch }].map((x) => (
                  <Box key={x.n} sx={{ display: "flex", alignItems: "center", gap: 0.6 }}>
                    <Box sx={{ width: 10, height: 10, borderRadius: "2px", background: x.bg }} />
                    <Typography sx={{ fontSize: 12, color: m3.onSurfaceVar }}>{x.n}</Typography>
                  </Box>
                ))}
              </Box>
            </Box>
          ) : <Typography sx={{ color: m3.onSurfaceVar }}>Compliance has not been evaluated yet.</Typography>)}
        {block("Change pulse", "what moved in the estate", null,
          <Box sx={{ display: "flex", flexDirection: "column" }}>
            {pulse.map((p, i) => (
              <Link key={p.key} href={p.href} underline="none" data-pulse={p.key}
                sx={{ color: "inherit", display: "flex", justifyContent: "space-between", gap: 1.5, py: 1, borderTop: i ? `1px solid ${m3.outlineVar}` : "none", "&:hover": { bgcolor: m3.scLow } }}>
                <Box sx={{ minWidth: 0 }}>
                  <Typography sx={{ fontSize: 13.5, fontWeight: 600, color: m3.onSurface }}>{p.label}</Typography>
                  <Typography sx={{ fontSize: 12, color: m3.onSurfaceVar }}>{p.hint}</Typography>
                </Box>
                <Typography sx={{ fontSize: 14, fontWeight: 700, color: p.value === "UNKNOWN" ? m3.neutralInk : m3.onSurface, whiteSpace: "nowrap" }}>{p.value} →</Typography>
              </Link>
            ))}
          </Box>, "change-pulse")}
      </Box>

      {est && (
        <Card sx={{ ...CARD, p: { xs: 2, md: 3 } }}>
          <Typography sx={{ fontSize: 18, fontWeight: 700 }}>Estate map</Typography>
          <Typography sx={{ fontSize: 13, color: m3.onSurfaceVar, mb: 2 }}>
            Every device, coloured by the worst condition neXus can evidence · {freshness ? `${freshness.fresh} of ${freshness.of} read in the last 24 h` : "data freshness UNKNOWN"}
          </Typography>
          <EstateMap estate={est} />
        </Card>
      )}

      <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: { xs: "1fr", md: "1fr 1fr" } }}>
        {block("Most widespread critical failures", "firewalls failing, on one scale", { href: q({ screen: "compliance", severity: "critical", result: "fail" }), label: "All" },
          widespread.length > 0 ? (
            <Box sx={{ display: "flex", flexDirection: "column", gap: 1.5 }}>
              {widespread.slice(0, 5).map((x) => (
                <Box key={x.control_id} component="a" href={q({ screen: "compliance", q: x.control_id })} sx={{ textDecoration: "none", color: "inherit" }}>
                  <Box sx={{ display: "flex", justifyContent: "space-between", gap: 1.5, alignItems: "baseline", mb: 0.5 }}>
                    <Box sx={{ display: "flex", alignItems: "center", gap: 1, minWidth: 0 }}>
                      {(x.vendors ?? []).map((v) => (
                        <Box key={v} component="span" sx={{ fontSize: 10.5, fontWeight: 700, px: 0.75, py: 0.1, borderRadius: "4px",
                          bgcolor: v === "check_point" ? m3.cp : v === "palo_alto" ? m3.pan : m3.outline, color: "#fff", whiteSpace: "nowrap" }}>
                          {v === "check_point" ? "CP" : v === "palo_alto" ? "PAN" : vendorDisplayName(v)}</Box>
                      ))}
                      <Typography sx={{ fontSize: 13.5, fontWeight: 600, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }} title={x.title}>{x.title}</Typography>
                    </Box>
                    <Typography sx={{ fontSize: 12.5, color: m3.onSurfaceVar, whiteSpace: "nowrap" }}>
                      <b style={{ color: m3.onSurface }}>{x.fail_count}</b> of {x.target_device_count} checked · {x.data_unavailable_count} no evidence</Typography>
                  </Box>
                  <Box sx={{ height: 10, borderRadius: 5, bgcolor: m3.sc, overflow: "hidden" }}>
                    <Box sx={{ width: `${(100 * x.fail_count) / Math.max(1, ...widespread.slice(0, 5).map((w) => w.target_device_count))}%`, height: "100%", bgcolor: EXEC.red, borderRadius: 5 }} />
                  </Box>
                </Box>
              ))}
            </Box>
          ) : <Typography sx={{ color: controls === null ? m3.onSurfaceVar : EXEC.green }}>{controls === null ? "Checks not read." : "No critical check is failing"}</Typography>)}
        {/* One home for the version charts (post-release review 2026-10-18, 3.5): the Devices Fleet summary. Here one line. */}
        {block("Software in service", null, null,
          <Typography data-software-link sx={{ fontSize: 13.5, color: m3.onSurfaceVar }}>
            Versions per vendor, as the devices report them{p.evidence_at ? ` · facts ${relativeAge(p.evidence_at)}` : ""}:{" "}
            <Link href={q({ screen: "inventory" })} underline="hover">Devices › Fleet summary →</Link>
          </Typography>)}
      </Box>

      <Box>
        <Link component="button" underline="hover" onClick={() => setDetails((v) => !v)} sx={{ fontSize: 13 }}>
          {details ? "Hide estate details" : "Estate details: policies installed · hardware models · data freshness per device"}
        </Link>
      </Box>
      <Collapse in={details} unmountOnExit>
        <EstateDetails data={data} />
      </Collapse>
    </ScreenRoot>
  );
}
