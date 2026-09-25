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
import { Donut, STATUS, STATUS_INK, StackedBar, foldSlices } from "../shell/Charts";
import { StatePanel, Ts, isRestricted } from "../shell/States";
import { useDisplayMode } from "../shell/displayMode";
import { DISPLAY_TZ_LABEL, formatUtc, relativeAge } from "../shell/time";
import { MONO, m3 } from "../theme/m3Theme";
import { getComplianceControls, getOverview, type ComplianceControlItem, type CountTile, type EvidenceChip, type FailureReason, type OverviewView, type VersionSlice } from "../auth/adminApi";
import { checkPointLag, paloAltoLag, type Lag } from "./overview/patchLag";

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

function ageTone(iso: string | null | undefined): keyof typeof STATUS {
  if (!iso) return "neutral";
  const h = (Date.now() - new Date(iso).getTime()) / 3_600_000;
  return h < 24 ? "good" : h < 72 ? "warning" : "critical";
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
  const label = missing === chips.length ? "Evidence UNKNOWN" : `Latest evidence ${relativeAge(oldest)}${missing ? ` · ${missing} UNKNOWN` : ""}`;
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
  const donut = (kind: "major" | "minor" | "model", raw: readonly VersionSlice[]) =>
    foldSlices(raw.map((s) => ({ ...s, href: link(kind, s.label) })), 5, all)
      .map((s) => (s.label !== null && s.display === s.label ? { ...s, display: shortLabel(vendor, kind, s.label) } : s));
  const minorRaw = versions?.minor ?? (fallbackMinor ?? []).map((h) => ({ label: h.level, count: h.count }));
  /** A donut with one slice carries no information (review §2): a stat line that keeps the click-through. */
  const part = (kind: "major" | "minor" | "model", title: string, raw: readonly VersionSlice[]) => {
    const slices = donut(kind, raw);
    if (slices.length === 1) {
      const s = slices[0];
      return (
        <Box key={kind} sx={{ display: "flex", flexDirection: "column", gap: 1 }}>
          <Typography variant="caption" sx={{ color: m3.onSurfaceVar, letterSpacing: "0.06em", fontWeight: 600 }}>{title.toUpperCase()}</Typography>
          <Link href={s.href} underline="hover" sx={{ color: m3.onSurface }}>
            <Typography sx={{ fontFamily: MONO, fontSize: 22, fontWeight: 600 }}>{s.display}</Typography>
            <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>{s.count} of {s.count} devices</Typography>
          </Link>
        </Box>
      );
    }
    return <Donut key={kind} stacked size={120} title={title} slices={slices} />;
  };
  return (
    <Card sx={CARD}>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", mb: 1.5 }}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
          <Box sx={{ width: 9, height: 9, borderRadius: "2px", bgcolor: accent }} />
          <Typography sx={{ fontSize: 16, fontWeight: 600 }}>{name}</Typography>
        </Box>
        <Link href={all} sx={{ fontSize: 13 }}>{total} devices</Link>
      </Box>
      <Box sx={{ display: "grid", gap: 2.5, gridTemplateColumns: "repeat(auto-fit, minmax(170px, 1fr))" }}>
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
                {r.devices} device{r.devices === 1 ? "" : "s"} · {r.job_types.join(", ")} · last {relativeAge(r.last_at)}
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
                    <Typography sx={{ fontSize: 12.5, fontWeight: 600 }}>{r.label ?? r.device_id.slice(0, 8)} <Box component="span" sx={{ fontWeight: 400, color: m3.onSurfaceVar }}>· {r.job_type}</Box></Typography>
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
function PolicyInstallCard({ p }: { readonly p: OverviewView["policy_install"] }) {
  const buckets: Array<[string, number | undefined]> = [
    ["Today", p?.today], ["Yesterday", p?.yesterday], ["2–7 days ago", p?.days_2_7], ["Earlier", p?.older], ["Not read yet", p?.unknown],
  ];
  return (
    <Card sx={CARD}>
      <SectionTitle icon="config" title="Policy installed" hint={`as each gateway reports it · read every evening 23:00${p?.last_read_at ? ` · last read ${relativeAge(p.last_read_at)}` : ""}`}
        right={<Link href="?screen=configuration" sx={{ fontSize: 13 }}>Open Configuration</Link>} />
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
 * One executive fact (PO 2026-09-25): a big number, one plain sentence, at most three named items, one link. No job
 * or connection detail -- that belongs to Operations.
 */
function FactCard({ title, icon, value, of, tone, sentence, items, href, linkLabel }: {
  readonly title: string; readonly icon: IconName; readonly value: string; readonly of?: string;
  readonly tone: "good" | "warning" | "critical" | "neutral"; readonly sentence: string;
  readonly items?: ReadonlyArray<{ label: string; detail?: string; href?: string }>; readonly href: string; readonly linkLabel: string;
}) {
  const ink = tone === "critical" ? STATUS_INK.critical : tone === "warning" ? STATUS_INK.serious : tone === "good" ? STATUS.good : m3.onSurface;
  return (
    <Card sx={{ ...CARD, display: "flex", flexDirection: "column", gap: 1.25 }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
        <Icon name={icon} size={18} />
        <Typography sx={{ fontSize: 13, fontWeight: 700, letterSpacing: 0.3, textTransform: "uppercase", color: m3.onSurfaceVar }}>{title}</Typography>
      </Box>
      <Typography sx={{ fontSize: 40, fontWeight: 700, lineHeight: 1, color: ink }}>
        {value}{of && <Typography component="span" sx={{ fontSize: 16, fontWeight: 500, color: m3.onSurfaceVar }}> of {of}</Typography>}
      </Typography>
      <Typography sx={{ fontSize: 15, lineHeight: 1.45, color: m3.onSurface }}>{sentence}</Typography>
      {items && items.length > 0 && (
        <Box sx={{ display: "flex", flexDirection: "column", gap: 0.5, pt: 0.5, borderTop: `1px solid ${m3.outlineVar}` }}>
          {items.slice(0, 3).map((it) => (
            <Box key={it.label} sx={{ display: "flex", justifyContent: "space-between", gap: 1.5, alignItems: "baseline" }}>
              {it.href ? <Link href={it.href} underline="hover" sx={{ fontSize: 13.5, fontWeight: 600, minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{it.label}</Link>
                : <Typography sx={{ fontSize: 13.5, fontWeight: 600, minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{it.label}</Typography>}
              {it.detail && <Typography sx={{ fontSize: 12.5, color: m3.onSurfaceVar, whiteSpace: "nowrap" }}>{it.detail}</Typography>}
            </Box>
          ))}
        </Box>
      )}
      <Box sx={{ mt: "auto" }}><Link href={href} underline="hover" sx={{ fontSize: 13 }}>{linkLabel} →</Link></Box>
    </Card>
  );
}

function lagSentence(lag: Lag, device: string, unit: string): string {
  return `${lag.behind} of ${lag.of} ${device} run an older ${unit} than the newest in use on the same version.`;
}

export function OverviewScreen() {
  const [data, setData] = useState<OverviewView | null>(null);
  const [error, setError] = useState<{ message: string; restricted: boolean } | null>(null);
  const [controls, setControls] = useState<readonly ComplianceControlItem[] | null>(null);
  const [details, setDetails] = useState(false);
  const { wall } = useDisplayMode();

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
    : "Compliance NOT EVALUATED.");

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
        href={q({ screen: "configuration", cluster_diff: "present" })} />
      <PostureTile big={wall} icon="config" severity="serious" title="Configuration changed" count={a.config_changed?.count ?? 0} of={a.config_changed?.of}
        unknown={tileUnknown(a.config_changed)} previous={a.config_changed?.previous}
        context={`since the previous collection · latest ${relativeAge(data.evidence.configuration?.at)}`} href={q({ screen: "configuration", change_state: "changed" })} />
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

  const enrolled = d.enrolled_devices ?? d.active_devices;
  const clustersEnrolled = d.clusters_enrolled ?? d.clusters;

  // Executive summary (PO 2026-09-25): five facts a manager decides on; job and connection detail lives in Operations.
  const backupOf = a.backup_missing?.of ?? d.backup_targets;
  const backupHave = backupOf - (a.backup_missing?.count ?? 0);
  const topControls = (controls ?? []).filter((x) => x.fail_count > 0 && (x.severity === "CRITICAL" || x.severity === "HIGH"))
    .sort((x, y) => (x.severity === y.severity ? 0 : x.severity === "CRITICAL" ? -1 : 1) || y.fail_count - x.fail_count)
    .slice(0, 3).map((x) => ({ label: x.title, detail: `${x.fail_count} device${x.fail_count === 1 ? "" : "s"}`,
      href: q({ screen: "compliance", q: x.control_id }) }));
  const cpLag = checkPointLag(p.versions?.check_point?.minor);
  const panLag = paloAltoLag(p.versions?.palo_alto?.minor);
  const lagBehind = (cpLag?.behind ?? 0) + (panLag?.behind ?? 0);
  const lagOf = (cpLag?.of ?? 0) + (panLag?.of ?? 0);
  const readAt = data.evidence.inventory?.at ?? p.evidence_at;

  return (
    <ScreenRoot>
      <ScreenHeader
        title="Overview"
        subtitle={`Executive summary · ${enrolled} devices, ${clustersEnrolled} clusters under neXus · data read ${relativeAge(readAt)}`}
        actions={<Box sx={{ display: "flex", gap: 1, alignItems: "flex-start" }}><FreshnessChip evidence={data.evidence} /></Box>}
      />

      <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: { xs: "1fr", md: "1fr 1fr", xl: "repeat(3, 1fr)" } }}>
        <FactCard title="Recoverability" icon="backup"
          value={tileUnknown(a.backup_missing) ? "UNKNOWN" : String(backupHave)} of={tileUnknown(a.backup_missing) ? undefined : String(backupOf)}
          tone={(a.backup_missing?.count ?? 0) > 0 ? "critical" : "good"}
          sentence={tileUnknown(a.backup_missing) ? "Backup coverage has not been read."
            : (a.backup_missing?.count ?? 0) > 0 ? `devices chosen for backup hold a stored backup. ${a.backup_missing.count} have none yet.`
              : "devices chosen for backup all hold a stored backup."}
          href={q({ screen: "backups", artefact: "none" })} linkLabel="Devices without a backup" />
        <FactCard title="Compliance" icon="compliance"
          value={c.state === "OK" ? `${c.assured_pct ?? 0}%` : "UNKNOWN"}
          tone={c.state !== "OK" ? "neutral" : (c.critical_deficiencies ?? 0) > 0 ? "critical" : "good"}
          sentence={c.state === "OK" ? `of security checks pass across ${c.evaluated ?? 0} firewalls. ${c.critical_deficiencies ?? 0} critical findings are open.`
            : "Compliance has not been evaluated yet."}
          items={topControls} href={q({ screen: "compliance" })} linkLabel="All findings" />
        <FactCard title="Configuration changes" icon="config"
          value={tileUnknown(a.config_changed) ? "UNKNOWN" : String(a.config_changed.count)} of={tileUnknown(a.config_changed) ? undefined : String(a.config_changed.of)}
          tone={(a.config_changed?.count ?? 0) > 0 ? "warning" : "good"}
          sentence={`devices changed configuration since their previous read (latest read ${relativeAge(data.evidence.configuration?.at)}).`}
          items={(ex.config_changes?.rows ?? []).map((r) => ({ label: r.label ?? r.device_id.slice(0, 8), detail: relativeAge(r.collected_at),
            href: q({ screen: "configuration", device_id: r.device_id }) }))}
          href={q({ screen: "configuration", change_state: "changed" })} linkLabel="All changes" />
        <FactCard title="Cluster consistency" icon="devices"
          value={tileUnknown(a.cluster_diff) ? "UNKNOWN" : String(a.cluster_diff.count)} of={tileUnknown(a.cluster_diff) ? undefined : String(a.cluster_diff.of)}
          tone={(a.cluster_diff?.count ?? 0) > 0 ? "warning" : "good"}
          sentence="clusters whose two members carry different settings -- after a failover the other member's settings apply."
          items={(ex.cluster_diff?.rows ?? []).map((r) => ({ label: r.cluster_ref, detail: `${r.diff_setting_count} settings`,
            href: q({ screen: "configuration", cluster_ref: r.cluster_ref }) }))}
          href={q({ screen: "configuration", cluster_diff: "present" })} linkLabel="All clusters with differences" />
        {(cpLag || panLag) && (
          <FactCard title="Versions and patches" icon="grid"
            value={String(lagBehind)} of={String(lagOf)} tone={lagBehind > 0 ? "warning" : "good"}
            sentence={[cpLag && lagSentence(cpLag, "Check Point devices", "hotfix take"), panLag && lagSentence(panLag, "Palo Alto firewalls", "maintenance build")]
              .filter(Boolean).join(" ")}
            items={[...(cpLag?.lines ?? []).map((l) => ({ label: `Check Point ${l}` })), ...(panLag?.lines ?? []).map((l) => ({ label: `Palo Alto ${l}` }))]}
            href={q({ screen: "inventory" })} linkLabel="Devices" />
        )}
      </Box>

      <Box>
        <Link component="button" underline="hover" onClick={() => setDetails((v) => !v)} sx={{ fontSize: 13 }}>
          {details ? "Hide fleet details" : "Fleet details: software, hardware, policy install, evidence age"}
        </Link>
      </Box>
      <Collapse in={details} unmountOnExit>
      <Box sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
      <PolicyInstallCard p={data.policy_install} />

      {/* Row E -- inventory facts, below the fold */}
      <Box>
        <SectionTitle icon="grid" title="Software and hardware" hint={`Per vendor, from the latest reads · facts ${relativeAge(p.evidence_at)} · each slice opens the device list`} />
        <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: { xs: "1fr", lg: "1fr 1fr" } }}>
          <VendorVersions vendor="check_point" name="Check Point" majorTitle="Major version" minorTitle="Jumbo hotfix"
            modelTitle="Appliance" versions={p.versions?.check_point} fallbackMinor={p.hotfix_levels} total={p.check_point ?? 0} />
          <VendorVersions vendor="palo_alto" name="Palo Alto Networks" majorTitle="PAN-OS major" minorTitle="Exact version"
            modelTitle="Model" versions={p.versions?.palo_alto} total={p.palo_alto ?? 0} />
        </Box>
      </Box>

      {/* evidence age (the change and cluster lists are the facts above) */}
      <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: { xs: "1fr", md: "1fr 1fr" } }}>
        <Card sx={CARD}>
          <SectionTitle icon="devices" title="Inventory evidence age" hint={`${age?.of ?? 0} active devices · newest read per device`} />
          <Donut title="Last successful inventory" centerLabel="devices" slices={[
            { label: "under 24 h", display: "under 24 h", count: age?.lt24h ?? 0, color: STATUS.good, href: q({ screen: "inventory", inventory_age: "lt24h" }) },
            { label: "24–72 h", display: "24–72 h", count: age?.h24_72 ?? 0, color: STATUS.warning, href: q({ screen: "inventory", inventory_age: "24h_72h" }) },
            { label: "over 72 h", display: "over 72 h", count: age?.gt72h ?? 0, color: STATUS.critical, href: q({ screen: "inventory", inventory_age: "gt72h" }) },
            { label: "never", display: "never", count: neverRead, color: STATUS.neutral, href: q({ screen: "inventory", inventory_age: "never" }) },
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
      </Collapse>
    </ScreenRoot>
  );
}
