import { useEffect, useMemo, useState } from "react";
import Box from "@mui/material/Box";
import Tooltip from "@mui/material/Tooltip";
import Typography from "@mui/material/Typography";
import {
  getDeviceCompliance,
  getDeviceConfiguration,
  getDeviceInventory,
  getDeviceLifecycle,
  getDeviceSoftwareHistory,
  getDeviceWorkspace,
  getManagementTree,
  listDeviceBackups,
  listFmgDiagnosticTargets,
  listStaticRoutePreviews,
  type BackupArtefact,
  type ManagementTree,
  type CpInstallEvent,
  type DeviceComplianceResult,
  type DeviceConfiguration,
  type DeviceInventory,
  type DeviceWorkspaceView,
  type LifecycleDevice,
  type SoftwareStretch,
} from "../auth/adminApi";
import { formatBytes } from "../shell/bytes";
import { enrollmentStateLabel } from "../shell/deviceCopy";
import { statusWord, vendorLabel } from "../shell/presenters";
import { isRestricted } from "../shell/States";
import { DISPLAY_TZ, formatStamp, formatUtc } from "../shell/time";
import { backupDisplayWord } from "../screens/backupWords";
import { changedSections, complianceCounts } from "../screens/HeaderStatePills";
import { licenseState, type SourceRead } from "../screens/overview/attentionRules";
import {
  unitEstateActions,
  type EstateActionReads,
  type EstateActionSpec,
} from "./estateActionModel";
import {
  parseTimeMs,
  readEstateWatchlist,
  toggleEstateWatchlist,
  type BadgeState,
} from "./estateModel";
import {
  EstateCard,
  EvidenceStamp,
  HaPairTiles,
  HonestBar,
  MoreFooter,
  StateBadge,
} from "./EstatePrimitives";
import {
  backupsHref,
  changesHref,
  complianceHref,
  dossierHref,
  standardBackupsHref,
  standardComplianceHref,
  wallHref,
  leavesEstateView,
  rewindHref,
  standardPolicyHref,
  type RewindOrigin,
} from "./estateRouting";
import { ESTATE_MONO, ESTATE_SANS, ev } from "./estateTokens";
import { managedThroughOf } from "./estateManagedThrough";
import { useEstateData } from "./useEstateData";
import { EstateDossierLogsBlock, loadDeviceLogsReads } from "./EstateDossierLogsBlock";
import { EstateDossierMemberDiffBlock } from "./EstateDossierMemberDiffBlock";
import { EstateDossierCertificateBlock } from "./EstateDossierCertificateBlock";
import { EstateDossierAsaSavedBlock } from "./EstateDossierAsaSavedBlock";
import { EstateDossierSshTrustBlock } from "./EstateDossierSshTrustBlock";
import { EstateDossierGridBlock } from "./EstateDossierGridBlock";
import { EstateDossierJobsBlock } from "./EstateDossierJobsBlock";
import type { DeviceLogsReads } from "./estateDeviceLogsSummary";

type DossierTab = "interfaces" | "routes" | "backups" | "controls" | "prechecks";

const DAY_KEY_FMT = new Intl.DateTimeFormat("sv-SE", {
  timeZone: DISPLAY_TZ,
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
});

function settle<T>(p: Promise<T>): Promise<SourceRead<T>> {
  return p.then(
    (value) => ({ state: "ok", value }) as const,
    (err: unknown) => ({ state: isRestricted(err) ? "restricted" : "failed" }) as const,
  );
}

interface AnswerCardSpec {
  readonly key: string;
  readonly question: string;
  readonly state: BadgeState;
  readonly badgeWord: string;
  readonly headline: string;
  readonly detail: string;
  readonly evidenceAt: string | null;
  readonly evidenceSource: string;
  readonly href: string | null;
}

/**
 * A lens row may only show its own evidence time. The model's lens answers can fall back to the
 * inventory read (unit.lastReadAt); that timestamp is not evidence for a non-inventory lens, so it
 * is dropped here and the stamp renders "evidence time unknown" instead of the inventory age.
 */
function ownLensEvidenceAt(
  at: string | null | undefined,
  source: string,
  inventoryReadAt: string | null,
): string | null {
  if (!at) return null;
  if (source !== "inventory" && inventoryReadAt !== null && at === inventoryReadAt) return null;
  return at;
}

export function EstateDossierScreen({ deviceId, fromRewind = null }: { readonly deviceId: string; readonly fromRewind?: RewindOrigin | null }) {
  const { loading: estateLoading, overview, model } = useEstateData();
  const [watchlist, setWatchlist] = useState<string[]>(() => readEstateWatchlist());
  const [tab, setTab] = useState<DossierTab>("interfaces");

  const [inventory, setInventory] = useState<SourceRead<DeviceInventory> | "loading">("loading");
  const [backups, setBackups] = useState<SourceRead<readonly BackupArtefact[]> | "loading">("loading");
  const [compliance, setCompliance] = useState<SourceRead<DeviceComplianceResult> | "loading">("loading");
  const [configuration, setConfiguration] = useState<SourceRead<DeviceConfiguration> | "loading">("loading");
  const [lifecycleDetail, setLifecycleDetail] = useState<SourceRead<LifecycleDevice> | "loading">("loading");
  const [swHistory, setSwHistory] = useState<
    SourceRead<{ stretches: readonly SoftwareStretch[]; installEvents: readonly CpInstallEvent[] }> | "loading"
  >("loading");
  const [logs, setLogs] = useState<SourceRead<DeviceLogsReads> | "loading">("loading");
  const [workspaceFallback, setWorkspaceFallback] = useState<SourceRead<DeviceWorkspaceView> | null>(null);
  const [actionReads, setActionReads] = useState<EstateActionReads>({ staticRouteTargets: null, diagnosticTargets: null });

  useEffect(() => {
    let active = true;
    setActionReads({ staticRouteTargets: null, diagnosticTargets: null });
    settle(listStaticRoutePreviews().then((r) => r.targets ?? [])).then((staticRouteTargets) => {
      if (active) setActionReads((prev) => ({ ...prev, staticRouteTargets }));
    });
    settle(listFmgDiagnosticTargets().then((r) => ({ targets: r.targets ?? [], canExecute: r.canExecute === true }))).then(
      (diagnosticTargets) => {
        if (active) setActionReads((prev) => ({ ...prev, diagnosticTargets }));
      },
    );
    return () => {
      active = false;
    };
  }, [deviceId]);
  const [managementTree, setManagementTree] = useState<SourceRead<ManagementTree> | "loading">("loading");

  useEffect(() => {
    let active = true;
    setInventory("loading");
    setBackups("loading");
    setCompliance("loading");
    setConfiguration("loading");
    setLifecycleDetail("loading");
    setSwHistory("loading");
    setLogs("loading");
    setWorkspaceFallback(null);
    setManagementTree("loading");
    settle(getManagementTree(deviceId)).then((mt) => {
      if (active) setManagementTree(mt);
    });

    Promise.all([
      settle(getDeviceInventory(deviceId)),
      settle(listDeviceBackups(deviceId).then((r) => (Array.isArray(r?.backups) ? r.backups : []))),
      settle(getDeviceCompliance(deviceId)),
      settle(getDeviceConfiguration(deviceId)),
      settle(getDeviceLifecycle(deviceId)),
      settle(
        getDeviceSoftwareHistory(deviceId).then((r) => ({
          stretches: Array.isArray(r?.stretches) ? r.stretches : [],
          installEvents: Array.isArray(r?.install_events) ? r.install_events : [],
        })),
      ),
      settle(loadDeviceLogsReads(deviceId)),
    ]).then(([inv, bk, cmp, cfg, lc, sw, lg]) => {
      if (!active) return;
      setInventory(inv);
      setBackups(bk);
      setCompliance(cmp);
      setConfiguration(cfg);
      setLifecycleDetail(lc);
      setSwHistory(sw);
      setLogs(lg);
    });

    return () => {
      active = false;
    };
  }, [deviceId]);

  const unit = model?.byId.get(deviceId) ?? null;

  useEffect(() => {
    if (estateLoading || unit) return;
    let active = true;
    settle(getDeviceWorkspace(deviceId)).then((ws) => {
      if (active) setWorkspaceFallback(ws);
    });
    return () => {
      active = false;
    };
  }, [deviceId, estateLoading, unit]);

  const now = model?.now ?? new Date();
  const cluster = unit?.clusterRef && model ? model.clustersByRef.get(unit.clusterRef) ?? null : null;
  const peers = useMemo(
    () => (cluster ? cluster.members.filter((m) => m.deviceId !== deviceId) : []),
    [cluster, deviceId],
  );
  const invData = inventory !== "loading" && inventory.state === "ok" ? inventory.value : null;
  const bkList = backups !== "loading" && backups.state === "ok" ? backups.value : null;

  const allInterfaces = useMemo(() => {
    if (!invData?.contexts) return [];
    return invData.contexts.flatMap((ctx) =>
      ctx.interfaces.map((iface) => ({
        context: ctx.vs_name ?? ctx.context,
        iface,
      })),
    );
  }, [invData]);

  const allRoutes = useMemo(() => {
    if (!invData?.contexts) return [];
    return invData.contexts.flatMap((ctx) =>
      ctx.routes.map((rt) => ({
        context: ctx.vs_name ?? ctx.context,
        rt,
      })),
    );
  }, [invData]);

  const nightStrip = useMemo(() => {
    const byDay = new Map<string, BackupArtefact>();
    for (const art of bkList ?? []) {
      const d = new Date(art.collected_at);
      if (Number.isNaN(d.getTime())) continue;
      const key = DAY_KEY_FMT.format(d);
      const prev = byDay.get(key);
      if (!prev || new Date(art.collected_at).getTime() > new Date(prev.collected_at).getTime()) {
        byDay.set(key, art);
      }
    }
    const cells: Array<{ day: string; kind: "stored" | "changed" | "none"; sizeBytes: number | null }> = [];
    for (let i = 13; i >= 0; i--) {
      const dayDate = new Date(now.getTime() - i * 86_400_000);
      const key = DAY_KEY_FMT.format(dayDate);
      const found = byDay.get(key);
      cells.push({
        day: key,
        kind: !found ? "none" : found.deviation_state === "changed" ? "changed" : "stored",
        sizeBytes: found?.size_bytes ?? null,
      });
    }
    return cells;
  }, [bkList, now]);

  if (estateLoading && !unit) {
    return (
      <EstateCard sx={{ p: 3 }}>
        <Typography component="h1" sx={{ fontSize: "24px", fontWeight: 650, mb: 0.5 }}>
          Unit dossier
        </Typography>
        <Typography sx={{ color: ev.ink2 }}>Reading unit dossier…</Typography>
      </EstateCard>
    );
  }

  // Fallback for a DRAFT or unknown device id
  if (!unit) {
    const ws = workspaceFallback?.state === "ok" ? workspaceFallback.value : null;
    return (
      <Box data-testid="estate-dossier-screen">
        <Box sx={{ fontSize: "12.5px", mb: "10px" }}>
          <Box component="a" href={wallHref()} sx={{ color: ev.accent, textDecoration: "none" }}>
            Estate
          </Box>{" "}
          <span style={{ color: ev.ink3 }}>/</span>{" "}
          <span style={{ fontFamily: ESTATE_MONO }}>{deviceId}</span>
        </Box>

      {fromRewind ? (
        <Box
          data-testid="dossier-rewind-banner"
          role="note"
          sx={{ mb: "12px", p: "10px 14px", borderRadius: "8px", border: `1px solid ${ev.line}`, bgcolor: ev.surface2, fontSize: "13px", color: ev.ink2 }}
        >
          Live view; you came from Rewind at {formatUtc(fromRewind.at, false)}
          {fromRewind.compare ? ` (compared with ${formatUtc(fromRewind.compare, false)})` : ""}. Nothing on this page is historical.{" "}
          <Box component="a" href={rewindHref(fromRewind.at, fromRewind.compare)} sx={{ color: ev.accent }}>
            Back to Rewind →
          </Box>
        </Box>
      ) : null}
        <EstateCard sx={{ p: 3 }}>
          <Typography sx={{ fontSize: "11px", letterSpacing: "0.09em", textTransform: "uppercase", color: ev.ink3, fontWeight: 650 }}>
            Dossier
          </Typography>
          <Typography component="h1" sx={{ fontFamily: ESTATE_MONO, fontSize: "26px", fontWeight: 700, mt: "2px", mb: 1 }}>
            {ws ? ws.device_id : deviceId}
          </Typography>
          <Box sx={{ display: "flex", gap: "10px", alignItems: "center", mb: 1.5 }}>
            <StateBadge state="unk" word={ws ? statusWord(enrollmentStateLabel(ws.enrollment_state)) : "UNKNOWN"} />
            <EvidenceStamp at={ws?.created_at ?? null} source="registered" now={now} />
          </Box>
          <Typography sx={{ color: ev.ink2 }}>
            {ws
              ? `${vendorLabel(ws.vendor_hint)} · ${enrollmentStateLabel(ws.enrollment_state)}. No active enrolled unit evidence is available on the Estate wall yet.`
              : "This unit could not be found in the active enrolled estate."}
          </Typography>
        </EstateCard>
      </Box>
    );
  }

  const ld: LifecycleDevice | null =
    lifecycleDetail !== "loading" && lifecycleDetail.state === "ok" ? lifecycleDetail.value : unit.lifecycle;
  const cmpData = compliance !== "loading" && compliance.state === "ok" ? compliance.value : null;
  const cfgData = configuration !== "loading" && configuration.state === "ok" ? configuration.value : null;
  const swData = swHistory !== "loading" && swHistory.state === "ok" ? swHistory.value : null;

  const isWatched = watchlist.includes(unit.deviceId);

  // Newest stored event time the events timeline actually plots (backups, software, install events,
  // readiness, and the config/policy/platform reads). The inventory read (unit.lastReadAt) is not an
  // event in this section and is deliberately excluded.
  const eventsNewestAt = (() => {
    const candidates: Array<string | null | undefined> = [
      ...(bkList ?? []).map((art) => art.collected_at),
      ...(swData?.stretches ?? []).map((st) => st.first_seen_at),
      ...(swData?.installEvents ?? []).map((evItem) => evItem.first_observed_at),
      cluster?.observedAt,
      cfgData?.collected_at,
      unit.policyReadAt,
      unit.platformFactsAt,
    ];
    let best: { readonly ms: number; readonly iso: string } | null = null;
    for (const iso of candidates) {
      const ms = parseTimeMs(iso);
      if (ms === null || !iso) continue;
      if (best === null || ms > best.ms) best = { ms, iso };
    }
    return best?.iso ?? null;
  })();

  // Build the 8 answers with explicit state badges (shape + word, hatched UNKNOWN) and evidence age stamps
  const answers: readonly AnswerCardSpec[] = (() => {
    // 1. Failover
    const foAns = unit.lenses.failover;
    const foChecks = cluster?.checks ?? [];
    const foDetail = !cluster
      ? "Standalone unit; HA pre-checks do not apply."
      : foChecks.length > 0
        ? `${foChecks.filter((c) => c.result === "PASS").length} pass · ${foChecks.filter((c) => c.result === "FAIL").length} fail · ${foChecks.filter((c) => c.result !== "PASS" && c.result !== "FAIL").length} UNKNOWN. ${cluster.reason}`
        : cluster.reason;

    // 2. Backups
    const bkAns = unit.lenses.backups;
    const newestBk = bkList && bkList.length > 0
      ? [...bkList].sort((a, b) => (parseTimeMs(b.collected_at) ?? 0) - (parseTimeMs(a.collected_at) ?? 0))[0]
      : unit.newestBackup;
    const bkDetail = !unit.backupTarget && !newestBk
      ? "Switched off as a backup target."
      : newestBk
        ? `Latest archive ${formatBytes(newestBk.size_bytes)} · ${backupDisplayWord(newestBk.validation_level)}${bkList ? ` · ${bkList.length} stored` : ""}${unit.sizeAnomaly ? ` · ${unit.sizeAnomaly.summary}` : ""}.`
        : bkAns.detail ?? "No stored archive in the encrypted store.";

    // 3. Controls
    const cmpCounts = cmpData ? complianceCounts(cmpData) : null;
    const cmpState: BadgeState = cmpCounts
      ? cmpCounts.checked === 0
        ? unit.role === "management_server"
          ? "na"
          : "unk"
        : cmpCounts.critical > 0
          ? "act"
          : cmpCounts.fail > 0
            ? "watch"
            : cmpCounts.unavailable === cmpCounts.checked
              ? "unk"
              : "ok"
      : unit.lenses.compliance.badgeState;
    const topFailingControl = cmpData?.items?.find((i) => String(i.displayStatus ?? "").toUpperCase() === "FAIL");
    const cmpHeadline = cmpCounts
      ? cmpCounts.checked === 0
        ? unit.role === "management_server"
          ? "No control set for management server"
          : "Controls not evidenced"
        : cmpCounts.fail > 0
          ? `${cmpCounts.fail} ${cmpCounts.fail === 1 ? "control fails" : "controls fail"}${cmpCounts.critical > 0 ? ` (${cmpCounts.critical} critical)` : ""}`
          : cmpCounts.pass > 0
            ? `All ${cmpCounts.pass} evidenced controls pass`
            : "No controls evidenced yet"
      : unit.lenses.compliance.summary;
    const cmpDetail = cmpCounts && cmpCounts.checked > 0
      ? `${cmpCounts.pass} pass · ${cmpCounts.fail} fail · ${cmpCounts.unavailable} no evidence.${topFailingControl ? ` Top failure: ${topFailingControl.title}.` : ""}`
      : "Evaluated against CIS, NIST, PCI-DSS and baseline checks.";

    // 4. Exposure
    const expAns = unit.lenses.exposure;
    const vuln = ld?.vulnerabilities;
    const expDetail =
      vuln?.state === "EVALUATED"
        ? `${vuln.affected ?? 0} affected (${vuln.affected_exploited ?? 0} exploited, ${vuln.affected_critical_high ?? 0} critical/high) · ${vuln.not_evaluable ?? 0} not evaluable. Version match against ${vuln.compared?.source ?? "catalog"}.`
        : vuln
          ? `Reason: ${(vuln.reason ?? vuln.state).replace(/_/g, " ").toLowerCase()}.`
          : "Advisory comparison has not run or is restricted.";

    // 5. Support / Lifecycle
    const lic = ld ? licenseState(ld) : null;
    const eosState: BadgeState = !ld
      ? "unk"
      : ld.risk === "EXPIRED" || lic?.state === "expired"
        ? "act"
        : ld.risk === "HIGH" || lic?.state === "soon"
          ? "watch"
          : ld.risk === "UNKNOWN" || ld.risk === "NOT_EVALUATED"
            ? "unk"
            : "ok";
    const eosHeadline = !ld
      ? "Support dates UNKNOWN"
      : lic?.state === "expired"
        ? `Expired: ${lic.label}`
        : ld.next_milestone
          ? `${ld.next_milestone.name}: ${ld.next_milestone.date}`
          : ld.hardware.status === "MATCHED" || ld.software.status === "MATCHED"
            ? "Supported in lifecycle catalog"
            : "End of support UNKNOWN";
    const eosDetail = !ld
      ? "Lifecycle catalog match unavailable."
      : ld.next_milestone
        ? `${ld.next_milestone.days_remaining} days remaining.${lic ? ` Nearest entitlement: ${lic.label}.` : ""}`
        : `Hardware: ${ld.hardware.status.replace(/_/g, " ").toLowerCase()} · Software: ${ld.software.status.replace(/_/g, " ").toLowerCase()}.`;
    const eosAt =
      ld?.hardware.catalog?.imported_at ??
      ld?.software.catalog?.imported_at ??
      overview?.generated_at ??
      null;

    // 6. Policy installed
    const instAns = unit.lenses.install;
    const instDetail =
      unit.role !== "gateway"
        ? "Management and appliance units do not report gateway policy packages."
        : unit.policyName || unit.policyInstalledAt
          ? `Reported package ${unit.policyName ?? "(unnamed)"}${unit.policyInstalledAt ? ` installed ${formatStamp(unit.policyInstalledAt)}` : unit.policyInstalledText ? ` (${unit.policyInstalledText})` : ""}.`
          : "No gateway policy install timestamp recorded yet.";

    // 7. Configuration & peer parity
    const cfgSections = cfgData ? changedSections(cfgData) : [];
    const clusterDiffRow = unit.clusterRef
      ? (overview?.exceptions?.cluster_diff?.rows ?? []).find((r) => r.cluster_ref === unit.clusterRef)
      : null;
    const clusterDiffListed = Boolean(
      unit.clusterRef && (overview?.exceptions?.cluster_diff?.all_refs ?? []).includes(unit.clusterRef),
    );
    const cfgState: BadgeState =
      configuration === "loading"
        ? "unk"
        : configuration.state !== "ok" || !cfgData?.collected_at
          ? unit.role === "appliance"
            ? "na"
            : "unk"
          : clusterDiffRow || clusterDiffListed
            ? "watch"
            : "ok";
    const cfgHeadline =
      cfgData?.collected_at
        ? cfgData.change_state === "changed"
          ? `Changed since previous read${cfgSections.length ? ` (${cfgSections.length} ${cfgSections.length === 1 ? "section" : "sections"})` : ""}`
          : cfgData.change_state === "first_run"
            ? "First configuration read stored"
            : "Unchanged since previous read"
        : unit.role === "appliance"
          ? "No configuration collector for this role"
          : "Configuration UNKNOWN (not read yet)";
    const cfgDetail = clusterDiffRow
      ? `Cluster members differ in ${clusterDiffRow.diff_setting_count} settings (${clusterDiffRow.diff_section_count} sections).`
      : clusterDiffListed
        ? "Cluster members differ on latest configuration comparison."
        : cfgSections.length > 0
          ? `Changed: ${cfgSections.slice(0, 4).join(", ")}${cfgSections.length > 4 ? ` +${cfgSections.length - 4} more` : ""}.`
          : "Compared locally against previous stored configuration snapshot.";

    // 8. Evidence age
    const ageAns = unit.lenses.age;
    const ageDetail = [
      `Inventory: ${invData?.collected_at ? formatStamp(invData.collected_at) : unit.lastReadAt ? formatStamp(unit.lastReadAt) : "never"}`,
      `Config: ${cfgData?.collected_at ? formatStamp(cfgData.collected_at) : "never"}`,
    ].join(" · ");

    return [
      {
        key: "failover",
        question: "Can it fail over?",
        state: foAns.badgeState,
        badgeWord: foAns.badgeWord,
        headline: foAns.summary,
        detail: foDetail,
        evidenceAt: ownLensEvidenceAt(foAns.evidenceAt, foAns.evidenceSource, unit.lastReadAt),
        evidenceSource: foAns.evidenceSource,
        href: cluster ? `?screen=operations&cluster_ref=${encodeURIComponent(cluster.ref)}` : null,
      },
      {
        key: "backups",
        question: "Is there a good backup?",
        state: bkAns.badgeState,
        badgeWord: bkAns.badgeWord,
        headline: bkAns.summary,
        detail: bkDetail,
        evidenceAt: ownLensEvidenceAt(newestBk?.collected_at ?? bkAns.evidenceAt, "backup", unit.lastReadAt),
        evidenceSource: "backup",
        href: `?screen=backups&q=${encodeURIComponent(unit.name)}`,
      },
      {
        key: "compliance",
        question: "Do its controls pass?",
        state: cmpState,
        badgeWord: cmpState === "unk" ? "UNKNOWN" : cmpState === "na" ? "Not applicable" : cmpState === "act" ? "Act" : cmpState === "watch" ? "Review" : "Fine",
        headline: cmpHeadline,
        detail: cmpDetail,
        evidenceAt: ownLensEvidenceAt(overview?.evidence?.compliance?.at ?? null, "compliance", unit.lastReadAt),
        evidenceSource: "compliance",
        href: `?screen=compliance&device=${encodeURIComponent(unit.deviceId)}`,
      },
      {
        key: "exposure",
        question: "Is its software exposed?",
        state: expAns.badgeState,
        badgeWord: expAns.badgeWord,
        headline: expAns.summary,
        detail: expDetail,
        evidenceAt: ownLensEvidenceAt(expAns.evidenceAt, expAns.evidenceSource, unit.lastReadAt),
        evidenceSource: expAns.evidenceSource,
        href: "?screen=inventory&view=lifecycle",
      },
      {
        key: "support",
        question: "When does support end?",
        state: eosState,
        badgeWord: eosState === "unk" ? "UNKNOWN" : eosState === "act" ? "Act" : eosState === "watch" ? "Review" : "Fine",
        headline: eosHeadline,
        detail: eosDetail,
        evidenceAt: ownLensEvidenceAt(eosAt, "lifecycle", unit.lastReadAt),
        evidenceSource: "lifecycle",
        href: "?screen=inventory&view=lifecycle",
      },
      {
        key: "install",
        question: "Is a policy installed?",
        state: instAns.badgeState,
        badgeWord: instAns.badgeWord,
        headline: instAns.summary,
        detail: instDetail,
        evidenceAt: ownLensEvidenceAt(instAns.evidenceAt, instAns.evidenceSource, unit.lastReadAt),
        evidenceSource: instAns.evidenceSource,
        href: unit.role === "gateway" ? standardPolicyHref(unit.deviceId) : null,
      },
      {
        key: "config",
        question: "Has its configuration changed?",
        state: cfgState,
        badgeWord: cfgState === "unk" ? "UNKNOWN" : cfgState === "na" ? "Not applicable" : cfgState === "watch" ? "Review" : "Fine",
        headline: cfgHeadline,
        detail: cfgDetail,
        evidenceAt: cfgData?.collected_at ?? null,
        evidenceSource: "config",
        href: changesHref(unit.deviceId),
      },
      {
        key: "age",
        question: "How old is what we know?",
        state: ageAns.badgeState,
        badgeWord: ageAns.badgeWord,
        headline: ageAns.summary,
        detail: ageDetail,
        evidenceAt: invData?.collected_at ?? unit.lastReadAt,
        evidenceSource: "inventory",
        href: wallHref("age"),
      },
    ];
  })();

  // Identity relationship strip (AGENTS.md Sensitive Identity Reporting Law: relationship words, never raw secrets)
  const identityVerdict = (() => {
    const ic = unit.identityCheck;
    if (!ic) return { word: "NOT_VERIFIED", detail: "No pinned comparison recorded", state: "unk" as BadgeState };
    if (ic.state === "VERIFIED") {
      return { word: `MATCH (${ic.basis.replace(/_/g, " ")})`, detail: formatStamp(ic.checked_at), state: "ok" as BadgeState };
    }
    if (ic.state === "MISMATCH") {
      return { word: `MISMATCH (${ic.basis.replace(/_/g, " ")})`, detail: formatStamp(ic.checked_at), state: "act" as BadgeState };
    }
    return {
      word: ic.first_use ? "PINNED (first use)" : "NOT_VERIFIED",
      detail: ic.checked_at ? formatStamp(ic.checked_at) : "Never compared",
      state: "unk" as BadgeState,
    };
  })();

  // 30-day timeline points from real stored evidence timestamps
  const timelineWindowMs = 30 * 86_400_000;
  const timelineStartMs = now.getTime() - timelineWindowMs;
  const xForIso = (iso: string | null | undefined): number | null => {
    const ms = parseTimeMs(iso);
    if (ms === null || ms < timelineStartMs) return null;
    const ratio = Math.min(1, Math.max(0, (ms - timelineStartMs) / timelineWindowMs));
    return 140 + ratio * (1140 - 140);
  };

  const tabs: ReadonlyArray<{ readonly id: DossierTab; readonly label: string }> = [
    { id: "interfaces", label: `Interfaces (${allInterfaces.length})` },
    { id: "routes", label: `Routes (${allRoutes.length})` },
    { id: "backups", label: `Backups (${bkList?.length ?? 0})` },
    { id: "controls", label: "Controls" },
    ...(cluster ? [{ id: "prechecks" as const, label: `Failover pre-checks (${cluster.checks.length})` }] : []),
  ];

  const cmpCounts = cmpData ? complianceCounts(cmpData) : null;
  const cmpOpenItems = cmpData
    ? cmpData.items.filter((i) => String(i.displayStatus ?? "").toUpperCase() !== "PASS" && String(i.displayStatus ?? "").toUpperCase() !== "NOT_APPLICABLE")
    : [];

  return (
    <Box data-testid="estate-dossier-screen">
      {/* Breadcrumb */}
      <Box sx={{ fontSize: "12.5px", mb: "10px", display: "flex", alignItems: "center", gap: "6px", flexWrap: "wrap" }}>
        <Box component="a" href={wallHref()} sx={{ color: ev.accent, textDecoration: "none", "&:hover": { textDecoration: "underline" } }}>
          Estate
        </Box>
        {cluster ? (
          <>
            <span style={{ color: ev.ink3 }}>/</span>
            <Box
              component="a"
              href={`?screen=operations&cluster_ref=${encodeURIComponent(cluster.ref)}`}
              sx={{ fontFamily: ESTATE_MONO, color: ev.accent, textDecoration: "none", "&:hover": { textDecoration: "underline" } }}
            >
              {cluster.title}
            </Box>
          </>
        ) : null}
        <span style={{ color: ev.ink3 }}>/</span>
        <span style={{ fontFamily: ESTATE_MONO, fontWeight: 600 }}>{unit.name}</span>
      </Box>

      {fromRewind ? (
        <Box
          data-testid="dossier-rewind-banner"
          role="note"
          sx={{ mb: "12px", p: "10px 14px", borderRadius: "8px", border: `1px solid ${ev.line}`, bgcolor: ev.surface2, fontSize: "13px", color: ev.ink2 }}
        >
          Live view; you came from Rewind at {formatUtc(fromRewind.at, false)}
          {fromRewind.compare ? ` (compared with ${formatUtc(fromRewind.compare, false)})` : ""}. Nothing on this page is historical.{" "}
          <Box component="a" href={rewindHref(fromRewind.at, fromRewind.compare)} sx={{ color: ev.accent }}>
            Back to Rewind →
          </Box>
        </Box>
      ) : null}

      {/* Header + Pair + Identity Strip */}
      <EstateCard>
        <Box
          sx={{
            display: "flex",
            flexWrap: "wrap",
            alignItems: "center",
            gap: "16px",
            p: "18px 18px 14px",
          }}
        >
          <Box sx={{ minWidth: 0 }}>
            <Box sx={{ display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap" }}>
              <Typography
                sx={{
                  fontSize: "11px",
                  letterSpacing: "0.09em",
                  textTransform: "uppercase",
                  color: ev.ink3,
                  fontWeight: 650,
                }}
              >
                Dossier
              </Typography>
              <EvidenceStamp at={unit.lastReadAt} source="inventory" now={now} missingLabel="never read" />
            </Box>
            <Typography
              component="h1"
              sx={{
                fontFamily: ESTATE_MONO,
                fontSize: { xs: "22px", md: "28px" },
                fontWeight: 700,
                lineHeight: 1.2,
                mt: "2px",
              }}
            >
              {unit.name}
            </Typography>
            <Typography sx={{ color: ev.ink2, fontSize: "14px", mt: "2px" }}>
              {unit.vendorName}
              {unit.model ? ` ${unit.model}` : ""}
              {unit.version ? ` · ${unit.version}` : ""}
              {unit.hotfix ? ` (${unit.hotfix})` : ""}
              {cluster
                ? ` · ${unit.haRole ? `${unit.haRole.toLowerCase()} member of ` : "member of "}${cluster.title}`
                : ` · ${unit.role.replace("_", " ")}`}
            </Typography>
          </Box>

          <Box sx={{ ml: "auto", display: "flex", gap: "8px", flexWrap: "wrap", justifyContent: "flex-end" }}>
            <Box
              component="button"
              type="button"
              aria-pressed={isWatched}
              onClick={() => setWatchlist(toggleEstateWatchlist(unit.deviceId))}
              sx={{
                height: "34px",
                px: "12px",
                borderRadius: "8px",
                border: `1px solid ${ev.lineStrong}`,
                bgcolor: isWatched ? ev.surface2 : ev.surface,
                color: ev.ink,
                fontWeight: 600,
                fontSize: "13px",
                cursor: "pointer",
              }}
            >
              {isWatched ? "Watching" : "Watch unit"}
            </Box>
          </Box>
        </Box>

        {/* HA Pair banner */}
        {cluster ? (
          <Box
            data-testid="dossier-pair-bar"
            sx={{
              display: "flex",
              flexWrap: "wrap",
              alignItems: "center",
              gap: "10px",
              px: "16px",
              py: "10px",
              mx: "18px",
              mb: "14px",
              bgcolor: ev.surface2,
              borderRadius: "10px",
              fontSize: "13px",
            }}
          >
            <Typography sx={{ fontSize: "11px", letterSpacing: "0.09em", textTransform: "uppercase", color: ev.ink3, fontWeight: 650 }}>
              Pair
            </Typography>
            <HaPairTiles cluster={cluster} lens="failover" size={18} selectedId={unit.deviceId} now={now} />
            <span>
              <b style={{ fontFamily: ESTATE_MONO }}>{unit.name}</b>
              {unit.haRole ? ` ${unit.haRole.toLowerCase()}` : ""}
              {peers.length > 0 ? (
                <>
                  {" ↔ "}
                  {peers.map((peer, idx) => (
                    <span key={peer.deviceId}>
                      {idx > 0 ? ", " : ""}
                      <Box
                        component="a"
                        href={dossierHref(peer.deviceId)}
                        sx={{ fontFamily: ESTATE_MONO, fontWeight: 650, color: ev.accent, textDecoration: "none", "&:hover": { textDecoration: "underline" } }}
                      >
                        {peer.name}
                      </Box>
                      {peer.haRole ? ` ${peer.haRole.toLowerCase()}` : ""}
                    </span>
                  ))}
                </>
              ) : (
                <span style={{ color: ev.ink3 }}> · peer observed via primary</span>
              )}
            </span>
            {cluster.mode ? <span style={{ color: ev.ink3 }}>· {cluster.mode}</span> : null}
            <Box sx={{ ml: "auto", display: "inline-flex", alignItems: "center", gap: "10px" }}>
              <StateBadge
                state={cluster.pairState}
                word={
                  cluster.failoverStatus === "NOT_READY"
                    ? "Not ready"
                    : cluster.failoverStatus === "UNKNOWN"
                      ? "UNKNOWN"
                      : cluster.failoverStatus === "UNSUPPORTED"
                        ? "Not supported"
                        : cluster.stale
                          ? "Ready (stale)"
                          : "Ready"
                }
              />
              <EvidenceStamp at={cluster.observedAt} source="readiness" now={now} />
            </Box>
          </Box>
        ) : null}

        <EstateDossierMemberDiffBlock
          clusterRef={unit.clusterRef}
          labels={Object.fromEntries((cluster?.members ?? []).map((m) => [m.deviceId, m.name]))}
        />

        <EstateDossierAsaSavedBlock deviceId={unit.deviceId} vendor={unit.vendor} />

        {/* Identity & relationship strip */}
        <Box
          sx={{
            display: "flex",
            gap: "24px",
            flexWrap: "wrap",
            p: "14px 18px",
            borderTop: `1px solid ${ev.line}`,
          }}
        >
          {[
            { label: "Enrollment", value: statusWord(enrollmentStateLabel(unit.enrollmentState)) },
            { label: "Management address", value: unit.managementIp ?? "Held by service" },
            { label: "Pinned identity", value: identityVerdict.word, title: identityVerdict.detail },
            { label: "Evidence source", value: unit.platformFactsSource ?? "Not read yet" },
            { label: "Backup target", value: unit.backupTarget ? "Yes (02:00 daily)" : "No" },
            { label: "Reference", value: `${unit.deviceId.slice(0, 12)}…`, title: unit.deviceId },
          ].map((item) => (
            <Box key={item.label} title={item.title} sx={{ fontSize: "12px", color: ev.ink3 }}>
              <span>{item.label}</span>
              <Box
                component="b"
                sx={{
                  display: "block",
                  fontFamily: ESTATE_MONO,
                  fontSize: "13px",
                  fontWeight: 600,
                  color: ev.ink,
                  mt: "2px",
                }}
              >
                {item.value}
              </Box>
            </Box>
          ))}
        </Box>
      </EstateCard>

      {/* Eight answers about this unit */}
      <EstateCard sx={{ mt: "16px" }}>
        <Box sx={{ display: "flex", alignItems: "baseline", gap: "12px", px: "18px", pt: "14px", pb: "10px", flexWrap: "wrap" }}>
          <Typography component="h2" sx={{ fontSize: "17px", fontWeight: 650 }}>
            Eight answers about this unit
          </Typography>
          <Typography sx={{ fontSize: "12.5px", color: ev.ink3 }}>
            each answer carries a shape+word state badge and the age of its stored evidence
          </Typography>
        </Box>
        <Box
          data-testid="dossier-eight-answers"
          sx={{
            display: "grid",
            gridTemplateColumns: {
              xs: "1fr",
              sm: "repeat(2, minmax(0, 1fr))",
              lg: "repeat(4, minmax(0, 1fr))",
            },
          }}
        >
          {answers.map((ans, index) => (
            <Box
              key={ans.key}
              data-dossier-answer={ans.key}
              sx={{
                p: "14px 18px",
                borderTop: `1px solid ${ev.line}`,
                borderLeft: {
                  xs: "none",
                  sm: index % 2 === 0 ? "none" : `1px solid ${ev.line}`,
                  lg: index % 4 === 0 ? "none" : `1px solid ${ev.line}`,
                },
                minHeight: "136px",
                display: "flex",
                flexDirection: "column",
                gap: "6px",
              }}
            >
              <Typography sx={{ fontSize: "12px", color: ev.ink3, fontWeight: 600 }}>{ans.question}</Typography>
              <Box>
                <StateBadge state={ans.state} word={ans.badgeWord} dense />
              </Box>
              <Typography sx={{ fontSize: "14.5px", fontWeight: 650, lineHeight: 1.3 }}>{ans.headline}</Typography>
              <Typography sx={{ fontSize: "12.5px", color: ev.ink2, lineHeight: 1.4 }}>{ans.detail}</Typography>
              <Box
                sx={{
                  mt: "auto",
                  pt: "6px",
                  display: "flex",
                  justifyContent: "space-between",
                  alignItems: "center",
                  gap: "8px",
                  flexWrap: "wrap",
                }}
              >
                <EvidenceStamp at={ans.evidenceAt} source={ans.evidenceSource} now={now} missingLabel="evidence time unknown" />
                {ans.href ? (
                  <Box
                    component="a"
                    href={ans.href}
                    sx={{ fontSize: "12px", color: ev.accent, textDecoration: "none", "&:hover": { textDecoration: "underline" } }}
                  >
                    {leavesEstateView(ans.href) ? "Open (standard view) →" : "Open →"}
                  </Box>
                ) : null}
              </Box>
            </Box>
          ))}
        </Box>
      </EstateCard>

      <EstateDossierLogsBlock deviceId={unit.deviceId} logs={logs} now={now} />

      <EstateDossierSshTrustBlock deviceId={unit.deviceId} />

      <EstateDossierGridBlock deviceId={unit.deviceId} inventory={inventory} now={now} />

      {/* Managed through: management-plane intent from the management tree, never device runtime truth */}
      {(() => {
        const mt = managedThroughOf(deviceId, managementTree);
        return (
          <EstateCard sx={{ mt: "16px", p: "14px 18px" }}>
            <Box data-testid="dossier-managed-through" data-managed-through={mt.state} sx={{ display: "flex", flexDirection: "column", gap: "6px" }}>
              <Typography sx={{ fontSize: "12px", color: ev.ink3, fontWeight: 600 }}>Managed through</Typography>
              <Box>
                <StateBadge state={mt.badgeState} word={mt.badgeWord} dense />
              </Box>
              <Typography sx={{ fontSize: "14.5px", fontWeight: 650, lineHeight: 1.3 }}>{mt.headline}</Typography>
              <Typography sx={{ fontSize: "12.5px", color: ev.ink2, lineHeight: 1.4 }}>{mt.detail}</Typography>
              <EvidenceStamp at={mt.evidenceAt} source={mt.evidenceSource} now={now} missingLabel="evidence time unknown" />
            </Box>
          </EstateCard>
        );
      })()}

      {/* 30-day stored evidence timeline */}
      <EstateCard sx={{ mt: "16px" }}>
        <Box sx={{ display: "flex", alignItems: "baseline", gap: "12px", px: "18px", pt: "14px", pb: "8px", flexWrap: "wrap" }}>
          <Typography component="h2" sx={{ fontSize: "17px", fontWeight: 650 }}>
            Stored observations, last 30 days
          </Typography>
          <Typography sx={{ fontSize: "12.5px", color: ev.ink3 }}>
            orders stored timestamps only — never infers causation
          </Typography>
          <Box sx={{ ml: "auto" }}>
            <EvidenceStamp at={eventsNewestAt} source="newest stored event" now={now} missingLabel="evidence time unknown" />
          </Box>
        </Box>
        <Box sx={{ px: "18px", pb: "14px" }}>
          <Box
            component="svg"
            viewBox="0 0 1160 156"
            role="img"
            aria-label={`30-day stored observation timeline for ${unit.name}`}
            sx={{ display: "block", width: "100%", height: "auto" }}
          >
            {[0, 5, 10, 15, 20, 25, 30].map((d) => {
              const x = 140 + ((1140 - 140) * d) / 30;
              return (
                <g key={d}>
                  <line x1={x} x2={x} y1={8} y2={132} stroke="var(--ev-line)" />
                  <text x={x} y={148} textAnchor="middle" fill="var(--ev-ink-3)" fontSize="11">
                    {d === 30 ? "today" : `${30 - d} d ago`}
                  </text>
                </g>
              );
            })}
            {(
              [
                ["Backups", 24],
                ["Software", 56],
                ["Readiness", 88],
                ["Reads", 120],
              ] as const
            ).map(([label, y]) => (
              <g key={label}>
                <text x={0} y={y + 4} fill="var(--ev-ink-2)" fontSize="12">
                  {label}
                </text>
                <line x1={140} x2={1140} y1={y} y2={y} stroke="var(--ev-line)" strokeDasharray="2 3" />
              </g>
            ))}

            {/* Backups lane */}
            {(bkList ?? []).map((art) => {
              const x = xForIso(art.collected_at);
              if (x === null) return null;
              const changed = art.deviation_state === "changed";
              return changed ? (
                <path
                  key={art.artefact_id}
                  d={`M${x - 5} 29 L${x} 18 L${x + 5} 29Z`}
                  fill="var(--ev-t-watch)"
                >
                  <title>{`Backup ${formatStamp(art.collected_at)} (${formatBytes(art.size_bytes)}, changed)`}</title>
                </path>
              ) : (
                <circle key={art.artefact_id} cx={x} cy={24} r={4} fill="var(--ev-t-ok)">
                  <title>{`Backup ${formatStamp(art.collected_at)} (${formatBytes(art.size_bytes)})`}</title>
                </circle>
              );
            })}

            {/* Software stretches & install events lane */}
            {(swData?.stretches ?? []).map((st, idx) => {
              const x = xForIso(st.first_seen_at);
              if (x === null) return null;
              return (
                <rect key={`sw-${idx}`} x={x - 4} y={52} width={8} height={8} rx={2} fill="var(--ev-accent)">
                  <title>{`Software ${st.software_version} first seen ${formatStamp(st.first_seen_at)}`}</title>
                </rect>
              );
            })}
            {(swData?.installEvents ?? []).map((evItem, idx) => {
              const x = xForIso(evItem.first_observed_at);
              if (x === null) return null;
              return (
                <path key={`inst-${idx}`} d={`M${x} 50 L${x + 5} 56 L${x} 62 L${x - 5} 56Z`} fill="var(--ev-t-watch)">
                  <title>{`${evItem.kind}: ${evItem.file_name} (${evItem.installed_at_local})`}</title>
                </path>
              );
            })}

            {/* Readiness lane */}
            {cluster?.observedAt ? (() => {
              const x = xForIso(cluster.observedAt);
              if (x === null) return null;
              if (cluster.failoverStatus === "NOT_READY") {
                return (
                  <rect x={x - 4.5} y={83.5} width={9} height={9} fill="var(--ev-t-act)">
                    <title>{`Not ready · ${formatStamp(cluster.observedAt)}`}</title>
                  </rect>
                );
              }
              if (cluster.failoverStatus === "UNKNOWN") {
                return (
                  <rect
                    x={x - 4.5}
                    y={83.5}
                    width={9}
                    height={9}
                    fill="none"
                    stroke="var(--ev-t-unk)"
                    strokeWidth={1.6}
                    strokeDasharray="2 2"
                  >
                    <title>{`Readiness UNKNOWN · ${formatStamp(cluster.observedAt)}`}</title>
                  </rect>
                );
              }
              return (
                <circle cx={x} cy={88} r={4.5} fill="var(--ev-t-ok)">
                  <title>{`Ready · ${formatStamp(cluster.observedAt)}`}</title>
                </circle>
              );
            })() : null}

            {/* Inventory / Config / Policy reads lane */}
            {[
              ["Inventory read", invData?.collected_at ?? unit.lastReadAt],
              ["Configuration read", cfgData?.collected_at],
              ["Policy read", unit.policyReadAt],
              ["Platform facts read", unit.platformFactsAt],
            ].map(([lbl, at]) => {
              const x = xForIso(at);
              if (x === null) return null;
              return (
                <circle key={lbl} cx={x} cy={120} r={3.5} fill="var(--ev-ink-2)">
                  <title>{`${lbl}: ${formatStamp(at)}`}</title>
                </circle>
              );
            })}
          </Box>
        </Box>
      </EstateCard>

      <EstateDossierCertificateBlock deviceId={unit.deviceId} />
      <EstateDossierJobsBlock deviceId={unit.deviceId} clusterUnit={unit.clusterRef !== null} />

      {/* Gated actions: links to the standard view for this unit; nothing runs from here */}
      <EstateCard sx={{ mt: "16px" }}>
        <DossierActions
          unitId={unit.deviceId}
          actions={unitEstateActions(
            { deviceId: unit.deviceId, vendor: unit.vendor, role: unit.role },
            actionReads,
          )}
        />
      </EstateCard>

      {/* Detail Tabs */}
      <EstateCard sx={{ mt: "16px" }}>
        <Box
          role="tablist"
          aria-label="Unit dossier details"
          sx={{
            display: "flex",
            gap: "4px",
            borderBottom: `1px solid ${ev.line}`,
            px: "18px",
            overflowX: "auto",
          }}
        >
          {tabs.map((t) => {
            const on = tab === t.id;
            return (
              <Box
                key={t.id}
                component="button"
                type="button"
                role="tab"
                aria-selected={on}
                onClick={() => setTab(t.id)}
                sx={{
                  border: 0,
                  background: "none",
                  px: "12px",
                  py: "10px",
                  fontWeight: 620,
                  fontSize: "13px",
                  fontFamily: ESTATE_SANS,
                  color: on ? ev.ink : ev.ink2,
                  borderBottom: `2px solid ${on ? ev.accent : "transparent"}`,
                  mb: "-1px",
                  cursor: "pointer",
                  whiteSpace: "nowrap",
                  "&:hover": { color: ev.ink },
                }}
              >
                {t.label}
              </Box>
            );
          })}
        </Box>

        <Box sx={{ p: "14px 18px 18px", overflowX: "auto" }}>
          {tab === "interfaces" ? (
            inventory === "loading" ? (
              <Typography sx={{ color: ev.ink2 }}>Reading stored interfaces…</Typography>
            ) : inventory.state !== "ok" ? (
              <Box sx={{ display: "flex", alignItems: "center", gap: "10px" }}>
                <StateBadge state="unk" word="UNKNOWN" />
                <Typography sx={{ color: ev.ink2 }}>Interface inventory could not be read.</Typography>
              </Box>
            ) : allInterfaces.length === 0 ? (
              <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: 1 }}>
                <Typography sx={{ color: ev.ink2 }}>
                  {invData?.collected_at
                    ? "No interfaces were reported in the stored inventory snapshot."
                    : "Inventory has not been collected for this unit yet."}
                </Typography>
                <EvidenceStamp at={invData?.collected_at} source="inventory" now={now} missingLabel="never read" />
              </Box>
            ) : (
              <>
              <Box
                component="table"
                aria-label="Unit interfaces"
                sx={{
                  width: "100%",
                  borderCollapse: "collapse",
                  fontSize: "13px",
                  "& th": {
                    textAlign: "left",
                    fontSize: "11px",
                    letterSpacing: "0.07em",
                    textTransform: "uppercase",
                    color: ev.ink3,
                    fontWeight: 650,
                    p: "8px 10px",
                    borderBottom: `1px solid ${ev.line}`,
                  },
                  "& td": { p: "8px 10px", borderBottom: `1px solid ${ev.line}` },
                  "& tr:last-of-type td": { borderBottom: 0 },
                }}
              >
                <thead>
                  <tr>
                    <th>Interface</th>
                    <th>Context</th>
                    <th>Addresses</th>
                    <th>Zone</th>
                    <th>Link state</th>
                    <th>Evidence</th>
                  </tr>
                </thead>
                <tbody>
                  {allInterfaces.slice(0, 60).map(({ context, iface }, idx) => {
                    const stRaw = (iface.state ?? "").trim().toUpperCase();
                    const configDerived = invData?.interfaces_source === "configuration" && !stRaw.startsWith("UP");
                    const linkState: BadgeState = configDerived
                      ? "unk"
                      : stRaw === "UP"
                        ? "ok"
                        : stRaw === "DOWN"
                          ? "watch"
                          : "unk";
                    const linkWord = configDerived
                      ? "UNKNOWN (config derived)"
                      : stRaw || "UNKNOWN";
                    return (
                      <tr key={`${context}-${iface.name}-${idx}`}>
                        <td style={{ fontFamily: ESTATE_MONO }}>{iface.name}</td>
                        <td style={{ fontFamily: ESTATE_MONO, color: ev.ink2 }}>{context}</td>
                        <td style={{ fontFamily: ESTATE_MONO }}>
                          {iface.addresses.length > 0 ? iface.addresses.map((a) => a.address).join(", ") : "—"}
                        </td>
                        <td>{iface.zone ?? "—"}</td>
                        <td>
                          <StateBadge state={linkState} word={linkWord} dense />
                        </td>
                        <td>
                          <EvidenceStamp at={invData?.collected_at} now={now} />
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </Box>
              <MoreFooter shown={60} total={allInterfaces.length} noun="interfaces" testId="interfaces-more" />
            </>
            )
          ) : null}

          {tab === "routes" ? (
            inventory === "loading" ? (
              <Typography sx={{ color: ev.ink2 }}>Reading stored routes…</Typography>
            ) : inventory.state !== "ok" ? (
              <Box sx={{ display: "flex", alignItems: "center", gap: "10px" }}>
                <StateBadge state="unk" word="UNKNOWN" />
                <Typography sx={{ color: ev.ink2 }}>Route table could not be read.</Typography>
              </Box>
            ) : allRoutes.length === 0 ? (
              <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: 1 }}>
                <Typography sx={{ color: ev.ink2 }}>No stored routes for this unit.</Typography>
                <EvidenceStamp at={invData?.collected_at} source="inventory" now={now} missingLabel="never read" />
              </Box>
            ) : (
              <>
              <Box
                component="table"
                aria-label="Unit routes"
                sx={{
                  width: "100%",
                  borderCollapse: "collapse",
                  fontSize: "13px",
                  "& th": {
                    textAlign: "left",
                    fontSize: "11px",
                    letterSpacing: "0.07em",
                    textTransform: "uppercase",
                    color: ev.ink3,
                    fontWeight: 650,
                    p: "8px 10px",
                    borderBottom: `1px solid ${ev.line}`,
                  },
                  "& td": { p: "8px 10px", borderBottom: `1px solid ${ev.line}` },
                  "& tr:last-of-type td": { borderBottom: 0 },
                }}
              >
                <thead>
                  <tr>
                    <th>Destination</th>
                    <th>Next hop</th>
                    <th>Interface</th>
                    <th>Protocol</th>
                    <th>Context</th>
                    <th>Evidence</th>
                  </tr>
                </thead>
                <tbody>
                  {allRoutes.slice(0, 80).map(({ context, rt }, idx) => (
                    <tr key={`${context}-${rt.destination}-${idx}`}>
                      <td style={{ fontFamily: ESTATE_MONO }}>{rt.destination}</td>
                      <td style={{ fontFamily: ESTATE_MONO }}>{rt.next_hop ?? "connected"}</td>
                      <td style={{ fontFamily: ESTATE_MONO }}>{rt.interface ?? "—"}</td>
                      <td>{rt.protocol}</td>
                      <td style={{ fontFamily: ESTATE_MONO, color: ev.ink2 }}>{context}</td>
                      <td>
                        <EvidenceStamp at={invData?.collected_at} now={now} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </Box>
              <MoreFooter shown={80} total={allRoutes.length} noun="routes" testId="routes-more" />
            </>
            )
          ) : null}

          {tab === "backups" ? (
            backups === "loading" ? (
              <Typography sx={{ color: ev.ink2 }}>Reading stored backups…</Typography>
            ) : backups.state !== "ok" ? (
              <Box sx={{ display: "flex", alignItems: "center", gap: "10px" }}>
                <StateBadge state="unk" word="UNKNOWN" />
                <Typography sx={{ color: ev.ink2 }}>Backup archives could not be read.</Typography>
              </Box>
            ) : (
              <Box>
                <Box sx={{ display: "flex", alignItems: "center", gap: "12px", mb: "12px", flexWrap: "wrap" }}>
                  <Typography sx={{ fontSize: "12.5px", color: ev.ink2, fontWeight: 600 }}>
                    Last 14 calendar days (oldest → today):
                  </Typography>
                  <Box sx={{ display: "inline-flex", gap: "3px" }} role="img" aria-label="14-day backup archive strip">
                    {nightStrip.map((c) => (
                      <Tooltip
                        key={c.day}
                        title={`${c.day}: ${c.kind === "none" ? "no archive stored" : `${c.kind} (${formatBytes(c.sizeBytes ?? 0)})`}`}
                      >
                        <Box
                          component="i"
                          sx={{
                            width: "13px",
                            height: "18px",
                            borderRadius: "2px",
                            display: "inline-block",
                            bgcolor:
                              c.kind === "stored"
                                ? ev.tOk
                                : c.kind === "changed"
                                  ? ev.tWatch
                                  : "transparent",
                            border: c.kind === "none" ? `1.5px dashed ${ev.tNa}` : "none",
                          }}
                        />
                      </Tooltip>
                    ))}
                  </Box>
                  <Box sx={{ ml: "auto", display: "flex", gap: "12px", fontSize: "12.5px" }}>
                    <Box component="a" href={backupsHref(unit.name)} sx={{ color: ev.accent }}>
                      Backups lens →
                    </Box>
                    <Box component="a" href={standardBackupsHref(unit.name)} sx={{ color: ev.accent }}>
                      Backups (standard view) →
                    </Box>
                  </Box>
                </Box>

                {(bkList ?? []).length === 0 ? (
                  <Typography sx={{ color: ev.ink2 }}>No stored archives for this unit.</Typography>
                ) : (
                  <>
                  <Box
                    component="table"
                    aria-label="Stored backup archives"
                    sx={{
                      width: "100%",
                      borderCollapse: "collapse",
                      fontSize: "13px",
                      "& th": {
                        textAlign: "left",
                        fontSize: "11px",
                        letterSpacing: "0.07em",
                        textTransform: "uppercase",
                        color: ev.ink3,
                        fontWeight: 650,
                        p: "8px 10px",
                        borderBottom: `1px solid ${ev.line}`,
                      },
                      "& td": { p: "8px 10px", borderBottom: `1px solid ${ev.line}` },
                      "& tr:last-of-type td": { borderBottom: 0 },
                    }}
                  >
                    <thead>
                      <tr>
                        <th>Collected</th>
                        <th>Size</th>
                        <th>Validation</th>
                        <th>Deviation</th>
                        <th>Age</th>
                      </tr>
                    </thead>
                    <tbody>
                      {(bkList ?? []).slice(0, 14).map((art) => (
                        <tr key={art.artefact_id}>
                          <td style={{ fontFamily: ESTATE_MONO }}>{formatStamp(art.collected_at)}</td>
                          <td style={{ fontFamily: ESTATE_MONO }}>{formatBytes(art.size_bytes)}</td>
                          <td>{backupDisplayWord(art.validation_level)}</td>
                          <td>{art.deviation_state ?? "UNKNOWN"}</td>
                          <td>
                            <EvidenceStamp at={art.collected_at} now={now} />
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </Box>
                  <MoreFooter shown={14} total={(bkList ?? []).length} noun="artefacts" testId="backups-more" />
                </>
                )}
              </Box>
            )
          ) : null}

          {tab === "controls" ? (
            compliance === "loading" ? (
              <Typography sx={{ color: ev.ink2 }}>Reading control evaluations…</Typography>
            ) : compliance.state !== "ok" || !cmpData || !cmpCounts ? (
              <Box sx={{ display: "flex", alignItems: "center", gap: "10px" }}>
                <StateBadge state="unk" word="UNKNOWN" />
                <Typography sx={{ color: ev.ink2 }}>
                  {compliance.state === "restricted"
                    ? "Compliance checks are restricted for this account."
                    : "Compliance evaluations could not be read for this unit."}
                </Typography>
              </Box>
            ) : cmpCounts.checked === 0 ? (
              <Typography sx={{ color: ev.ink2 }}>No applicable control checks recorded for this unit.</Typography>
            ) : (
              <Box>
                <Box sx={{ display: "flex", alignItems: "center", gap: "12px", mb: "10px", flexWrap: "wrap" }}>
                  <Typography sx={{ fontSize: "13px", fontWeight: 600 }}>
                    {cmpCounts.pass} pass · {cmpCounts.fail} fail ({cmpCounts.critical} critical) · {cmpCounts.unavailable} no evidence
                  </Typography>
                  <EvidenceStamp at={overview?.evidence?.compliance?.at} source="compliance" now={now} />
                  <Box sx={{ ml: "auto", display: "flex", gap: "12px", fontSize: "12.5px" }}>
                    <Box component="a" href={complianceHref(unit.deviceId)} sx={{ color: ev.accent }}>
                      Controls lens →
                    </Box>
                    <Box component="a" href={standardComplianceHref({ deviceId: unit.deviceId })} sx={{ color: ev.accent }}>
                      Compliance (standard view) →
                    </Box>
                  </Box>
                </Box>
                <HonestBar
                  height={14}
                  ariaLabel="Unit control checks"
                  segments={[
                    { key: "pass", label: "Pass", count: cmpCounts.pass, state: "ok" },
                    { key: "fail", label: "Fail", count: cmpCounts.fail, state: "act" },
                    { key: "unk", label: "No evidence (UNKNOWN)", count: cmpCounts.unavailable, state: "unk" },
                  ]}
                />
                <Box sx={{ mt: "12px" }}>
                  {cmpOpenItems
                    .slice(0, 15)
                    .map((item, idx) => {
                      const st = String(item.displayStatus ?? "").toUpperCase();
                      const bState: BadgeState = st === "FAIL" ? "act" : "unk";
                      return (
                        <Box
                          key={item.controlId}
                          sx={{
                            display: "flex",
                            justifyContent: "space-between",
                            alignItems: "center",
                            gap: "12px",
                            py: "7px",
                            borderTop: idx > 0 ? `1px solid ${ev.line}` : "none",
                            fontSize: "12.5px",
                          }}
                        >
                          <Box sx={{ minWidth: 0 }}>
                            <b style={{ fontFamily: ESTATE_MONO }}>{item.controlId}</b> · {item.title}{" "}
                            <span style={{ color: ev.ink3 }}>({item.severity.toLowerCase()})</span>
                          </Box>
                          <StateBadge state={bState} word={st === "FAIL" ? "Fail" : "UNKNOWN"} dense />
                        </Box>
                      );
                    })}
                </Box>
                <MoreFooter shown={15} total={cmpOpenItems.length} noun="control checks" testId="compare-more" />
              </Box>
            )
          ) : null}

          {tab === "prechecks" && cluster ? (
            cluster.checks.length === 0 ? (
              <Box sx={{ display: "flex", alignItems: "center", gap: "10px" }}>
                <StateBadge state="unk" word="UNKNOWN" />
                <Typography sx={{ color: ev.ink2 }}>No pre-check observations recorded yet for {cluster.title}.</Typography>
              </Box>
            ) : (
              <Box
                component="table"
                aria-label="Cluster failover pre-checks"
                sx={{
                  width: "100%",
                  borderCollapse: "collapse",
                  fontSize: "13px",
                  "& th": {
                    textAlign: "left",
                    fontSize: "11px",
                    letterSpacing: "0.07em",
                    textTransform: "uppercase",
                    color: ev.ink3,
                    fontWeight: 650,
                    p: "8px 10px",
                    borderBottom: `1px solid ${ev.line}`,
                  },
                  "& td": { p: "8px 10px", borderBottom: `1px solid ${ev.line}` },
                  "& tr:last-of-type td": { borderBottom: 0 },
                }}
              >
                <thead>
                  <tr>
                    <th>#</th>
                    <th>Pre-check</th>
                    <th>Member</th>
                    <th>Result</th>
                    <th>Summary</th>
                    <th>Evidence</th>
                  </tr>
                </thead>
                <tbody>
                  {cluster.checks.map((chk, idx) => {
                    const bState: BadgeState =
                      chk.result === "PASS"
                        ? "ok"
                        : chk.result === "FAIL"
                          ? "act"
                          : chk.result === "WARN"
                            ? "watch"
                            : "unk";
                    const word =
                      chk.result === "PASS"
                        ? "Pass"
                        : chk.result === "FAIL"
                          ? "Blocks"
                          : chk.result === "WARN"
                            ? "Review"
                            : "UNKNOWN";
                    return (
                      <tr key={`${chk.checkNo}-${chk.member}-${idx}`}>
                        <td style={{ fontFamily: ESTATE_MONO }}>{chk.checkNo}</td>
                        <td style={{ fontWeight: 600 }}>{chk.title}</td>
                        <td style={{ fontFamily: ESTATE_MONO }}>{chk.member}</td>
                        <td>
                          <StateBadge state={bState} word={word} dense />
                        </td>
                        <td style={{ color: ev.ink2 }}>{chk.summary}</td>
                        <td>
                          <EvidenceStamp at={cluster.observedAt} now={now} />
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </Box>
            )
          ) : null}
        </Box>
      </EstateCard>
    </Box>
  );
}

/** The unit's gated actions. Each available one is a link to the standard view for this unit; none runs from the Estate view. */
function DossierActions({ unitId, actions }: { readonly unitId: string; readonly actions: readonly EstateActionSpec[] }) {
  const wordFor: Record<EstateActionSpec["availability"], string> = {
    available: "AVAILABLE",
    unavailable: "UNAVAILABLE",
    unknown: "UNKNOWN",
    checking: "CHECKING",
  };
  return (
    <Box data-testid="dossier-actions" aria-label={`Gated actions for ${unitId}`} role="group" sx={{ p: "18px" }}>
      <Typography sx={{ fontWeight: 650, fontSize: "15px" }}>Actions</Typography>
      <Typography sx={{ color: ev.ink2, fontSize: "13px", mt: "2px" }}>
        Each action opens the standard view for this unit. Nothing runs from the Estate view.
      </Typography>
      <Box sx={{ display: "grid", gap: "8px", mt: "12px" }}>
        {actions.map((a) => (
          <Box
            key={a.key}
            data-testid={`dossier-action-${a.key}`}
            data-availability={a.availability}
            sx={{
              display: "grid",
              gridTemplateColumns: "minmax(140px, 200px) 1fr auto",
              alignItems: "center",
              gap: "12px",
              px: "12px",
              py: "10px",
              border: `1px solid ${ev.line}`,
              borderRadius: "10px",
            }}
          >
            <Box>
              <Typography sx={{ fontWeight: 600, fontSize: "13px" }}>{a.label}</Typography>
              <Box component="span" sx={{ fontFamily: ESTATE_MONO, fontSize: "10px", fontWeight: 700, color: ev.ink2 }}>
                {wordFor[a.availability]}
              </Box>
            </Box>
            <Typography sx={{ color: ev.ink2, fontSize: "12.5px" }}>{a.reason}</Typography>
            {a.href ? (
              <Box
                component="a"
                href={a.href}
                sx={{
                  display: "inline-flex",
                  alignItems: "center",
                  height: "32px",
                  px: "10px",
                  borderRadius: "8px",
                  border: `1px solid ${ev.lineStrong}`,
                  color: ev.ink,
                  fontWeight: 600,
                  fontSize: "13px",
                  textDecoration: "none",
                  whiteSpace: "nowrap",
                  "&:hover": { borderColor: ev.ink2, textDecoration: "none" },
                }}
              >
                {a.linkLabel} →
              </Box>
            ) : null}
          </Box>
        ))}
      </Box>
      <Typography sx={{ color: ev.ink3, fontSize: "11.5px", mt: "8px" }}>
        Availability comes from the standard views’ own reads. UNKNOWN means that read failed; the standard view makes the decision.
      </Typography>
    </Box>
  );
}
