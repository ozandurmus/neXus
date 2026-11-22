import { useEffect, useMemo, useState } from "react";
import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { useSession } from "../auth/SessionContext";
import { PRODUCT_NAME } from "../brand/product";
import { planeGranted } from "../shell/actionAccess";
import { DISPLAY_TZ } from "../shell/time";
import { actorUnknown, type AttentionRow, type RuleKey } from "../screens/overview/attentionRules";
import {
  readEstateWatchlist,
  type EstateCluster,
  type EstateModel,
  type EstateUnit,
} from "./estateModel";
import {
  EstateCard,
  EvidenceStamp,
  HaPairTiles,
  HonestBar,
  StateBadge,
  TileSwatch,
  UnitTile,
} from "./EstatePrimitives";
import {
  backupsHref,
  changesHref,
  complianceHref,
  dossierHref,
  failoverHref,
  standardBackupsHref,
  standardChangesHref,
  standardComplianceHref,
  standardHaHref,
  standardJobsHref,
  wallHref,
  type EstateLens,
} from "./estateRouting";
import { ESTATE_MONO, ESTATE_SANS, ev } from "./estateTokens";
import { useEstateData } from "./useEstateData";
import { usePlatformFindings } from "./estatePlatformFindings";
import { EstatePlatformCard } from "./EstatePlatformCard";
import { EstateSecurityScanCard } from "./EstateSecurityScanCard";

const DATE_FMT = new Intl.DateTimeFormat("en-GB", {
  timeZone: DISPLAY_TZ,
  weekday: "long",
  day: "numeric",
  month: "long",
  year: "numeric",
});

export function rowCategory(key: AttentionRow["key"]): {
  readonly kicker: string;
  readonly primaryHref: string | null;
  readonly primaryButtonLabel: string | null;
  readonly standardHrefOverride?: string;
} {
  switch (key) {
    case "ha-not-ready":
    case "ha-unknown":
    case "unknown-failover":
      return {
        kicker: "Failover",
        primaryHref: failoverHref(),
        primaryButtonLabel: "Open failover lens",
        standardHrefOverride: standardHaHref(),
      };
    case "backup-missing":
    case "backup-stale":
      return {
        kicker: "Backups",
        primaryHref: backupsHref(),
        primaryButtonLabel: "Open backups lens",
        standardHrefOverride: standardBackupsHref(),
      };
    case "advisory-exploited":
    case "advisory-critical-high":
      return { kicker: "Exposure", primaryHref: wallHref("exposure"), primaryButtonLabel: "Open exposure lens" };
    case "license-expired":
    case "license-30":
    case "license-60":
    case "live-patch-missing":
    case "unknown-lifecycle":
      return { kicker: "Lifecycle", primaryHref: wallHref("exposure"), primaryButtonLabel: "Open exposure lens" };
    case "compliance-critical":
    case "unknown-compliance":
      return {
        kicker: "Controls",
        primaryHref: complianceHref(),
        primaryButtonLabel: "Open controls lens",
        standardHrefOverride: standardComplianceHref(),
      };
    case "not-read":
    case "read-ageing":
      return { kicker: "Evidence", primaryHref: wallHref("age"), primaryButtonLabel: "Open age lens" };
    case "failed-jobs":
      return {
        kicker: "Jobs",
        primaryHref: null,
        primaryButtonLabel: "Open failed jobs (standard view)",
        standardHrefOverride: standardJobsHref(),
      };
    case "change-unknown-actor":
    case "unknown-changes":
      return {
        kicker: "Changes",
        primaryHref: changesHref(),
        primaryButtonLabel: "Open changes",
        standardHrefOverride: standardChangesHref(),
      };
    default:
      return { kicker: "Estate", primaryHref: wallHref("worst"), primaryButtonLabel: "Open estate wall" };
  }
}

/**
 * Primary destination of a Brief row. A category without its own `primaryHref` (failed jobs) opens its
 * `standardHrefOverride` (an explicit standard view), then the attention rule's href; the label always names where it goes.
 */
export function briefRowPrimary(
  cat: ReturnType<typeof rowCategory>,
  row: Pick<AttentionRow, "href" | "linkLabel">,
): { readonly href: string; readonly label: string } {
  return {
    href: cat.primaryHref ?? cat.standardHrefOverride ?? row.href,
    label: cat.primaryButtonLabel ?? `${row.linkLabel} (standard view)`,
  };
}

function affectedScopeFor(
  key: AttentionRow["key"],
  model: EstateModel,
): { readonly clusters?: readonly EstateCluster[]; readonly units?: readonly EstateUnit[]; readonly lens: EstateLens } {
  switch (key as RuleKey) {
    case "ha-not-ready":
      return {
        clusters: model.clusters.filter((c) => c.failoverStatus === "NOT_READY"),
        lens: "failover",
      };
    case "ha-unknown":
      return {
        clusters: model.clusters.filter((c) => c.failoverStatus === "UNKNOWN"),
        lens: "failover",
      };
    case "backup-missing":
      return {
        units: model.units.filter((u) => u.noArchive),
        lens: "backups",
      };
    case "backup-stale":
      return {
        units: model.units.filter((u) => !u.noArchive && u.lenses.backups.tileState === "watch"),
        lens: "backups",
      };
    case "advisory-exploited":
      return {
        units: model.units.filter(
          (u) => u.lifecycle?.vulnerabilities?.state === "EVALUATED" && (u.lifecycle.vulnerabilities.affected_exploited ?? 0) > 0,
        ),
        lens: "exposure",
      };
    case "advisory-critical-high":
      return {
        units: model.units.filter(
          (u) =>
            u.lifecycle?.vulnerabilities?.state === "EVALUATED" &&
            (u.lifecycle.vulnerabilities.affected_exploited ?? 0) === 0 &&
            (u.lifecycle.vulnerabilities.affected_critical_high ?? 0) > 0,
        ),
        lens: "exposure",
      };
    case "compliance-critical":
      return {
        units: model.units.filter((u) => (u.criticalFail ?? 0) > 0),
        lens: "compliance",
      };
    case "not-read":
      return {
        units: model.units.filter((u) => u.ageBucket === "stale" || u.ageBucket === "never"),
        lens: "age",
      };
    case "read-ageing":
      return {
        units: model.units.filter((u) => u.ageBucket === "old"),
        lens: "age",
      };
    default:
      return { lens: "worst" };
  }
}

function deltaSentenceParts(overview: ReturnType<typeof useEstateData>["overview"]): string | null {
  if (!overview?.attention) return null;
  const parts: string[] = [];
  const check = (label: string, count: number | undefined, prev: number | null | undefined) => {
    if (typeof count !== "number" || prev === null || prev === undefined) return;
    const diff = count - prev;
    if (diff > 0) parts.push(`${label} increased by ${diff}`);
    else if (diff < 0) parts.push(`${label} dropped by ${Math.abs(diff)}`);
  };
  check("failed jobs", overview.attention.failed_jobs_24h?.count, overview.attention.failed_jobs_24h?.previous);
  check("missing backups", overview.attention.backup_missing?.count, overview.attention.backup_missing?.previous);
  check("cluster differences", overview.attention.cluster_diff?.count, overview.attention.cluster_diff?.previous);
  check("changed configs", overview.attention.config_changed?.count, overview.attention.config_changed?.previous);
  if (parts.length === 0) return null;
  return `Since yesterday: ${parts.join("; ")}.`;
}

function rowDeltaText(
  key: AttentionRow["key"],
  overview: ReturnType<typeof useEstateData>["overview"],
): { readonly text: string; readonly tone: "act" | "ok" | "faint" } | null {
  const att = overview?.attention;
  if (!att) return null;
  const tile =
    key === "failed-jobs"
      ? att.failed_jobs_24h
      : key === "backup-missing"
        ? att.backup_missing
        : null;
  if (!tile || tile.previous === null || tile.previous === undefined) return null;
  const d = tile.count - tile.previous;
  if (d === 0) return { text: "no change vs yesterday", tone: "faint" };
  return d > 0
    ? { text: `+${d} vs yesterday`, tone: "act" }
    : { text: `−${Math.abs(d)} vs yesterday`, tone: "ok" };
}

function BriefItemRow({
  row,
  model,
  overview,
}: {
  readonly row: AttentionRow;
  readonly model: EstateModel;
  readonly overview: ReturnType<typeof useEstateData>["overview"];
}) {
  const cat = rowCategory(row.key);
  const scope = affectedScopeFor(row.key, model);
  const delta = rowDeltaText(row.key, overview);
  const maxTiles = 40;
  const shownClusters = scope.clusters?.slice(0, 20) ?? [];
  const extraClusters = Math.max(0, (scope.clusters?.length ?? 0) - shownClusters.length);
  const shownUnits = scope.units?.slice(0, maxTiles) ?? [];
  const extraUnits = Math.max(0, (scope.units?.length ?? 0) - shownUnits.length);

  const { href: primaryHref, label: primaryLabel } = briefRowPrimary(cat, row);
  const standardHref = cat.standardHrefOverride ?? row.href;

  return (
    <Box
      data-brief-item={row.key}
      sx={{
        display: "grid",
        gridTemplateColumns: { xs: "1fr", sm: "100px minmax(0, 1fr) auto" },
        gap: { xs: "8px", sm: "16px" },
        px: "18px",
        py: "14px",
        borderTop: `1px solid ${ev.line}`,
        alignItems: "start",
      }}
    >
      <Box>
        <Typography
          sx={{
            fontSize: "11px",
            letterSpacing: "0.09em",
            textTransform: "uppercase",
            color: ev.ink3,
            fontWeight: 650,
          }}
        >
          {cat.kicker}
        </Typography>
      </Box>

      <Box sx={{ minWidth: 0 }}>
        <Typography sx={{ fontSize: "15.5px", fontWeight: 650, color: ev.ink, lineHeight: 1.3 }}>
          {row.what}
        </Typography>
        {row.detail ? (
          <Typography sx={{ fontSize: "13px", color: ev.ink2, mt: "3px" }}>{row.detail}</Typography>
        ) : null}
        {shownClusters.length === 0 && shownUnits.length === 0 && row.names.length > 0 ? (
          <Typography sx={{ fontSize: "12.5px", color: ev.ink2, mt: "4px" }}>
            {row.namesLead ? `${row.namesLead} ` : ""}
            <Box component="span" sx={{ fontFamily: ESTATE_MONO, fontWeight: 600, color: ev.ink }}>
              {row.names.join(", ")}
            </Box>
            {row.more > 0 ? ` and ${row.more} more` : ""}
          </Typography>
        ) : null}
        {shownClusters.length > 0 ? (
          <Box sx={{ display: "flex", flexWrap: "wrap", gap: "5px", mt: "10px", alignItems: "center" }}>
            {shownClusters.map((cl) => (
              <HaPairTiles key={cl.ref} cluster={cl} lens={scope.lens} size={16} now={model.now} />
            ))}
            {extraClusters > 0 ? (
              <Box component="span" sx={{ fontSize: "12px", color: ev.ink3, ml: "4px" }}>
                +{extraClusters} clusters
              </Box>
            ) : null}
          </Box>
        ) : null}
        {shownUnits.length > 0 ? (
          <Box sx={{ display: "flex", flexWrap: "wrap", gap: "3px", mt: "10px", alignItems: "center" }}>
            {shownUnits.map((u) => (
              <UnitTile key={u.deviceId} unit={u} lens={scope.lens} size={16} now={model.now} />
            ))}
            {extraUnits > 0 ? (
              <Box component="span" sx={{ fontSize: "12px", color: ev.ink3, ml: "4px" }}>
                +{extraUnits}
              </Box>
            ) : null}
          </Box>
        ) : null}
      </Box>

      <Box
        sx={{
          display: "flex",
          flexDirection: "column",
          gap: "6px",
          alignItems: { xs: "flex-start", sm: "flex-end" },
          textAlign: { xs: "left", sm: "right" },
        }}
      >
        <Box sx={{ display: "flex", gap: "6px", flexWrap: "wrap", justifyContent: "flex-end" }}>
          <Box
            component="a"
            href={primaryHref}
            sx={{
              display: "inline-flex",
              alignItems: "center",
              height: "28px",
              px: "10px",
              borderRadius: "8px",
              border: `1px solid ${ev.lineStrong}`,
              bgcolor: ev.surface,
              color: ev.ink,
              fontSize: "12.5px",
              fontWeight: 580,
              textDecoration: "none",
              whiteSpace: "nowrap",
              "&:hover": { borderColor: ev.ink2, textDecoration: "none" },
            }}
          >
            {primaryLabel}
          </Box>
          {cat.primaryHref ? (
            <Box
              component="a"
              href={standardHref}
              sx={{
                display: "inline-flex",
                alignItems: "center",
                height: "28px",
                px: "8px",
                fontSize: "12px",
                color: ev.accent,
                textDecoration: "none",
                whiteSpace: "nowrap",
                "&:hover": { textDecoration: "underline" },
              }}
            >
              Standard →
            </Box>
          ) : null}
        </Box>
        <EvidenceStamp
          at={row.at}
          source={row.atLabel !== "—" ? row.atLabel : undefined}
          now={model.now}
          missingLabel={row.at === null && row.atLabel !== "—" ? row.atLabel : "UNKNOWN"}
        />
        {delta ? (
          <Box
            component="span"
            sx={{
              fontFamily: ESTATE_MONO,
              fontSize: "12px",
              color: delta.tone === "act" ? ev.act : delta.tone === "ok" ? ev.ok : ev.ink3,
            }}
          >
            {delta.text}
          </Box>
        ) : null}
      </Box>
    </Box>
  );
}

export function EstateBriefScreen() {
  const { loading, fatalError, overview, changes, model, refresh } = useEstateData();
  const session = useSession();
  const [watchlistIds, setWatchlistIds] = useState<string[]>(() => readEstateWatchlist());
  const platform = usePlatformFindings();

  useEffect(() => {
    setWatchlistIds(readEstateWatchlist());
  }, []);

  const casesHref = planeGranted(session?.permissions, "PacketAnalysis")
    ? "?screen=packet_analysis"
    : "?screen=diagnostics";

  const watchlistUnits = useMemo(() => {
    if (!model) return [];
    const explicit = watchlistIds
      .map((id) => model.byId.get(id) ?? model.byName.get(id))
      .filter((u): u is EstateUnit => Boolean(u));
    if (explicit.length > 0) return explicit;
    // Default sample: up to 4 units currently needing attention or UNKNOWN, else first 4 units
    return [...model.units]
      .sort((a, b) => {
        const ra = a.lenses.worst.badgeState === "act" ? 0 : a.lenses.worst.badgeState === "unk" ? 1 : a.lenses.worst.badgeState === "watch" ? 2 : 3;
        const rb = b.lenses.worst.badgeState === "act" ? 0 : b.lenses.worst.badgeState === "unk" ? 1 : b.lenses.worst.badgeState === "watch" ? 2 : 3;
        return ra - rb || a.name.localeCompare(b.name);
      })
      .slice(0, 4);
  }, [model, watchlistIds]);

  if (fatalError) {
    return (
      <EstateCard sx={{ p: 3 }}>
        <Typography component="h1" sx={{ fontSize: "24px", fontWeight: 650, mb: 1 }}>
          Shift brief
        </Typography>
        <Box sx={{ mb: 1.5 }}>
          <StateBadge state={fatalError.restricted ? "na" : "unk"} word={fatalError.restricted ? "Restricted" : "UNKNOWN"} />
        </Box>
        <Typography sx={{ color: ev.ink2, mb: 2 }}>
          {fatalError.restricted
            ? "This account cannot view the estate overview."
            : `The estate reads failed (${fatalError.message}). Stored state is UNKNOWN until a read succeeds.`}
        </Typography>
        {!fatalError.restricted ? (
          <Box
            component="button"
            type="button"
            onClick={refresh}
            sx={{
              height: "34px",
              px: "14px",
              borderRadius: "8px",
              border: `1px solid ${ev.lineStrong}`,
              bgcolor: ev.surface,
              color: ev.ink,
              fontWeight: 600,
              cursor: "pointer",
            }}
          >
            Retry
          </Box>
        ) : null}
      </EstateCard>
    );
  }

  if (loading && !model) {
    return (
      <EstateCard sx={{ p: 3 }}>
        <Typography component="h1" sx={{ fontSize: "24px", fontWeight: 650, mb: 0.5 }}>
          Shift brief
        </Typography>
        <Typography sx={{ color: ev.ink2 }}>Reading stored estate evidence…</Typography>
      </EstateCard>
    );
  }

  if (!model) return null;

  const rows = model.attention?.rows ?? [];
  const actRows = rows.filter((r) => r.severity === "critical");
  const reviewRows = rows.filter((r) => r.severity === "serious" || r.severity === "warning");
  const unknownRows = rows.filter((r) => r.severity === "unknown");
  // Platform findings (device health, daily security scan) count in the same act / review / UNKNOWN totals.
  const platformAct = platform.items.filter((i) => i.severity === "critical").length;
  const platformReview = platform.items.filter((i) => i.severity === "serious").length;
  const platformUnknown = platform.items.filter((i) => i.severity === "unknown").length;
  const actCount = actRows.length + platformAct;
  const reviewCount = reviewRows.length + platformReview;
  const unknownCount = unknownRows.length + platformUnknown;

  const deltaLine = deltaSentenceParts(overview);
  const haUnknown = model.haCounts?.unknown ?? 0;
  const neverRead = model.freshness.never;
  const dataGaps = overview?.compliance?.state === "OK" ? (overview.compliance.data_gaps ?? 0) : null;

  const unknownBadgeParts: string[] = [];
  if (haUnknown > 0) unknownBadgeParts.push(`${haUnknown} ${haUnknown === 1 ? "cluster" : "clusters"} UNKNOWN`);
  if (neverRead > 0) unknownBadgeParts.push(`${neverRead} never read`);
  if (unknownRows.length > 0) unknownBadgeParts.push(`${unknownRows.length} ${unknownRows.length === 1 ? "source" : "sources"} UNKNOWN`);
  const unknownBadgeWord = unknownBadgeParts.length > 0 ? unknownBadgeParts.join(" · ") : "0 UNKNOWN sources";

  // The overview read failing while /devices succeeds leaves `attention` null: that is UNKNOWN, never "nothing to do".
  const overviewUnreadable = model.attention === null;
  const unknownEvidenceCount = haUnknown + neverRead;
  const unknownHeadlineText = (() => {
    const parts: string[] = [];
    if (unknownCount > 0)
      parts.push(`${unknownCount} ${unknownCount === 1 ? "source is" : "sources are"} UNKNOWN`);
    if (haUnknown > 0) parts.push(`${haUnknown} ${haUnknown === 1 ? "cluster is" : "clusters are"} UNKNOWN`);
    if (neverRead > 0) parts.push(`${neverRead} ${neverRead === 1 ? "unit was" : "units were"} never read`);
    return `${parts.join(", ")}.`;
  })();
  // Distinct units referenced by the act/review rows. A row with no unit reference (failed jobs, licences,
  // platform findings) still counts as an item, but adds no unit and is said so in the headline.
  const affectedUnitIds = new Set<string>();
  let itemsWithUnit = 0;
  for (const r of [...actRows, ...reviewRows]) {
    const sc = affectedScopeFor(r.key, model);
    const rowUnitIds = new Set<string>();
    for (const u of sc.units ?? []) rowUnitIds.add(u.deviceId);
    for (const c of sc.clusters ?? []) for (const m of c.members) rowUnitIds.add(m.deviceId);
    for (const id of rowUnitIds) affectedUnitIds.add(id);
    if (rowUnitIds.size > 0) itemsWithUnit += 1;
  }
  const itemCount = actCount + reviewCount;
  const itemsWithoutUnit = itemCount - itemsWithUnit;

  const unknownChangeCount =
    changes.state === "ok"
      ? changes.value.events.filter((e) => e.kind === "USER" && actorUnknown(e.actor)).length
      : null;
  // The change read is one page; a non-empty next_cursor means more events exist than were counted.
  const changesTruncated = changes.state === "ok" && Boolean(changes.value.next_cursor);
  const moreMark = changesTruncated ? "+" : "";
  const totalUserChanges =
    changes.state === "ok" ? changes.value.events.filter((e) => e.kind === "USER").length : null;
  const newestChangeAt =
    changes.state === "ok" && changes.value.events.length > 0
      ? [...changes.value.events].map((e) => e.time).filter(Boolean).sort().at(-1) ?? null
      : null;

  const domainStamps: ReadonlyArray<[string, string | null | undefined, string]> = [
    ["Inventory", overview?.evidence?.inventory?.at ?? model.newestEvidenceAt, wallHref("age")],
    ["Configuration (standard view)", overview?.evidence?.configuration?.at ?? null, "?screen=inventory&standard=1"],
    ["Compliance", overview?.evidence?.compliance?.at ?? null, complianceHref()],
    ["Backups", overview?.evidence?.backup?.at ?? null, backupsHref()],
    ["Jobs (standard view)", overview?.evidence?.jobs?.at ?? null, "?screen=operations&tab=jobs&standard=1"],
  ];

  return (
    <Box data-testid="estate-brief-screen">
      <Box sx={{ display: "flex", flexWrap: "wrap", alignItems: "center", gap: "12px" }}>
        <Typography
          sx={{
            fontSize: "11px",
            letterSpacing: "0.09em",
            textTransform: "uppercase",
            color: ev.ink3,
            fontWeight: 650,
          }}
        >
          Shift brief · {DATE_FMT.format(model.now)} · {model.units.length} active units
          {model.draftCount > 0 ? ` (${model.draftCount} draft not enrolled)` : ""}
        </Typography>
        <EvidenceStamp at={overview?.generated_at ?? model.newestEvidenceAt} source="brief read" now={model.now} />
      </Box>

      <Typography
        component="h1"
        data-testid="brief-headline"
        sx={{
          fontSize: { xs: "24px", md: "29px" },
          lineHeight: 1.22,
          fontWeight: 680,
          letterSpacing: "-0.02em",
          maxWidth: "960px",
          mt: "8px",
          color: ev.ink,
        }}
      >
        {overviewUnreadable ? (
          <>
            The overview read failed, so what needs action is UNKNOWN. Nothing here is counted as fine.
            {unknownCount > 0 || unknownEvidenceCount > 0 ? ` ${unknownHeadlineText}` : ""}
          </>
        ) : actCount > 0 || reviewCount > 0 ? (
          <>
            <Box component="em" sx={{ fontStyle: "normal", color: actCount > 0 ? ev.act : ev.ink }}>
              {itemCount} {itemCount === 1 ? "item" : "items"}
            </Box>
            {affectedUnitIds.size > 0
              ? ` on ${affectedUnitIds.size} ${affectedUnitIds.size === 1 ? "unit" : "units"}`
              : " with no unit"}
            {affectedUnitIds.size > 0 && itemsWithoutUnit > 0
              ? `, ${itemsWithoutUnit} with no unit`
              : ""}
            :{" "}
            <Box component="span" sx={{ color: actCount > 0 ? ev.act : ev.ink2 }}>
              {actCount} act now
            </Box>
            {", "}
            <Box component="span" sx={{ color: reviewCount > 0 ? ev.watch : ev.ink2 }}>
              {reviewCount} to review
            </Box>
            .{unknownCount > 0 || unknownEvidenceCount > 0 ? ` ${unknownHeadlineText}` : ""}
            {deltaLine ? ` ${deltaLine}` : ""}
          </>
        ) : unknownCount > 0 || unknownEvidenceCount > 0 ? (
          <>
            Nothing is proven to need action, but {unknownHeadlineText.charAt(0).toLowerCase()}
            {unknownHeadlineText.slice(1)}
            {deltaLine ? ` ${deltaLine}` : ""}
          </>
        ) : (
          <>
            Nothing needs immediate action across the evidenced estate.{deltaLine ? ` ${deltaLine}` : ""}
          </>
        )}
      </Typography>

      <Box
        sx={{
          display: "flex",
          flexWrap: "wrap",
          gap: "10px",
          alignItems: "center",
          mt: "10px",
          mb: "20px",
          color: ev.ink2,
          fontSize: "13.5px",
        }}
      >
        <StateBadge state="unk" word={unknownBadgeWord} />
        <span>
          Not counted as fine and not counted as failed.
          {dataGaps !== null && dataGaps > 0 ? ` ${dataGaps} control checks have no evidence yet.` : ""}
        </span>
      </Box>

      <Box
        sx={{
          display: "grid",
          gridTemplateColumns: { xs: "1fr", lg: "minmax(0, 1fr) 356px" },
          gap: "20px",
          alignItems: "start",
        }}
      >
        {/* Left column: Ranked action/review items + Checked and fine */}
        <Box sx={{ display: "flex", flexDirection: "column", gap: "16px", minWidth: 0 }}>
          <EstateCard>
            {overviewUnreadable ? (
              <Box sx={{ p: "18px", display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap" }} data-testid="brief-overview-unreadable">
                <StateBadge state="unk" word="UNKNOWN" />
                <Typography sx={{ color: ev.ink2 }}>
                  The overview read failed, so the attention list could not be built. This is not "all clear".
                </Typography>
              </Box>
            ) : actRows.length === 0 && reviewRows.length === 0 && unknownRows.length === 0 && unknownEvidenceCount === 0 ? (
              <Box sx={{ p: "18px", display: "flex", alignItems: "center", justifyContent: "space-between", gap: 2, flexWrap: "wrap" }}>
                <Box sx={{ display: "flex", alignItems: "center", gap: "10px" }}>
                  <StateBadge state="ok" word="All clear" />
                  <Typography sx={{ color: ev.ink2 }}>
                    No act-now, review, or failed-source items are open on the stored reads.
                  </Typography>
                </Box>
                <EvidenceStamp at={overview?.generated_at ?? model.newestEvidenceAt} source="overview" now={model.now} />
              </Box>
            ) : (
              <>
                {actRows.length > 0 ? (
                  <>
                    <Box sx={{ px: "18px", pt: "14px", pb: "6px", display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap" }}>
                      <StateBadge state="act" word="Act now" />
                      <Typography sx={{ fontSize: "12.5px", color: ev.ink3 }}>
                        ranked by operational impact, then affected count, with evidence age on each item
                      </Typography>
                    </Box>
                    {actRows.map((r) => (
                      <BriefItemRow key={r.key} row={r} model={model} overview={overview} />
                    ))}
                  </>
                ) : null}

                {reviewRows.length > 0 ? (
                  <>
                    <Box
                      sx={{
                        px: "18px",
                        pt: "14px",
                        pb: "6px",
                        borderTop: actRows.length > 0 ? `1px solid ${ev.line}` : "none",
                        display: "flex",
                        alignItems: "center",
                        gap: "10px",
                      }}
                    >
                      <StateBadge state="watch" word="Review" />
                      <Typography sx={{ fontSize: "12.5px", color: ev.ink3 }}>
                        degraded coverage, expiring entitlements, or ageing reads
                      </Typography>
                    </Box>
                    {reviewRows.map((r) => (
                      <BriefItemRow key={r.key} row={r} model={model} overview={overview} />
                    ))}
                  </>
                ) : null}

                {unknownRows.length > 0 ? (
                  <>
                    <Box
                      sx={{
                        px: "18px",
                        pt: "14px",
                        pb: "6px",
                        borderTop: actRows.length > 0 || reviewRows.length > 0 ? `1px solid ${ev.line}` : "none",
                        display: "flex",
                        alignItems: "center",
                        gap: "10px",
                      }}
                    >
                      <StateBadge state="unk" word="UNKNOWN" />
                      <Typography sx={{ fontSize: "12.5px", color: ev.ink3 }}>
                        evidence sources that could not be read — never assumed fine
                      </Typography>
                    </Box>
                    {unknownRows.map((r) => (
                      <BriefItemRow key={r.key} row={r} model={model} overview={overview} />
                    ))}
                  </>
                ) : null}
              </>
            )}
          </EstateCard>

          <Box
            data-testid="estate-brief-platform-row"
            sx={{ display: "grid", gap: "12px", gridTemplateColumns: { xs: "1fr", md: "minmax(0, 2fr) minmax(0, 1fr)" }, alignItems: "start" }}
          >
            <EstatePlatformCard platform={platform} now={model.now} />
            <EstateSecurityScanCard now={model.now} />
          </Box>

          <EstateCard>
            <Box sx={{ display: "flex", alignItems: "baseline", gap: "12px", px: "18px", pt: "14px", pb: "8px", flexWrap: "wrap" }}>
              <Typography component="h2" sx={{ fontSize: "17px", fontWeight: 650 }}>
                Checked and fine
              </Typography>
              <Typography sx={{ fontSize: "12.5px", color: ev.ink3 }}>
                absence is stated alongside its population and any UNKNOWN share
              </Typography>
            </Box>
            <Box sx={{ px: "18px", pb: "14px" }}>
              {model.checkedFine.length === 0 ? (
                <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", py: "6px", gap: 2, flexWrap: "wrap" }}>
                  <Typography sx={{ fontSize: "13px", color: ev.ink2 }}>
                    Every evaluated plane currently has at least one item listed above, or awaits its first read.
                  </Typography>
                  <EvidenceStamp at={overview?.generated_at ?? model.newestEvidenceAt} source="overview" now={model.now} />
                </Box>
              ) : (
                <Box component="ul" sx={{ m: 0, p: 0, listStyle: "none" }}>
                  {model.checkedFine.map((item, index) => (
                    <Box
                      component="li"
                      key={item.key}
                      sx={{
                        py: "8px",
                        borderTop: index > 0 ? `1px solid ${ev.line}` : "none",
                        display: "flex",
                        justifyContent: "space-between",
                        alignItems: "center",
                        gap: "12px",
                        flexWrap: "wrap",
                        fontSize: "13px",
                      }}
                    >
                      <span style={{ color: ev.ink }}>{item.label}</span>
                      <Box sx={{ display: "inline-flex", alignItems: "center", gap: "10px", flexWrap: "wrap" }}>
                        <span>
                          <Box component="b" sx={{ fontFamily: ESTATE_MONO }}>
                            {item.figure}
                          </Box>{" "}
                          <Box component="span" sx={{ color: ev.ink2 }}>
                            · {item.context}
                          </Box>
                          {item.unknownNote ? (
                            <>
                              {" · "}
                              <Box component="span" sx={{ color: ev.unk, fontWeight: 600 }}>
                                {item.unknownNote}
                              </Box>
                            </>
                          ) : null}
                        </span>
                        <EvidenceStamp at={item.evidenceAt} source={item.evidenceSource} now={model.now} />
                      </Box>
                    </Box>
                  ))}
                </Box>
              )}
            </Box>
          </EstateCard>
        </Box>

        {/* Right sidebar */}
        <Box component="aside" sx={{ display: "flex", flexDirection: "column", gap: "16px", minWidth: 0 }}>
          {/* Freshness card */}
          <EstateCard>
            <Box sx={{ display: "flex", alignItems: "baseline", justifyContent: "space-between", px: "18px", pt: "14px", pb: "10px" }}>
              <Typography component="h2" sx={{ fontSize: "16px", fontWeight: 650 }}>
                How fresh is the evidence?
              </Typography>
              <EvidenceStamp at={model.newestEvidenceAt} source="newest" now={model.now} />
            </Box>
            <Box sx={{ px: "18px", pb: "16px" }}>
              <HonestBar
                height={14}
                ariaLabel="Device evidence freshness"
                segments={[
                  { key: "fresh", label: "Under 6 h", count: model.freshness.fresh, state: "fresh" },
                  { key: "day", label: "6–24 h", count: model.freshness.day, state: "day" },
                  { key: "old", label: "24–72 h", count: model.freshness.old, state: "old" },
                  { key: "stale", label: "Over 72 h", count: model.freshness.stale, state: "stale" },
                  { key: "never", label: "Never read (UNKNOWN)", count: model.freshness.never, state: "unk" },
                ]}
              />
              <Box sx={{ display: "flex", flexWrap: "wrap", gap: "12px", mt: "10px", fontSize: "12px", color: ev.ink2 }}>
                {(
                  [
                    ["fresh", "under 6 h", model.freshness.fresh],
                    ["day", "6–24 h", model.freshness.day],
                    ["old", "24–72 h", model.freshness.old],
                    ["stale", "over 72 h", model.freshness.stale],
                    ["unk", "never read", model.freshness.never],
                  ] as const
                ).map(([st, label, count]) => (
                  <Box key={label} component="span" sx={{ display: "inline-flex", alignItems: "center", gap: "6px" }}>
                    <TileSwatch state={st} size={14} />
                    <span>{label}</span>
                    <Box component="b" sx={{ fontFamily: ESTATE_MONO, color: ev.ink }}>
                      {count}
                    </Box>
                  </Box>
                ))}
              </Box>

              <Box sx={{ mt: "12px", pt: "10px", borderTop: `1px solid ${ev.line}`, display: "flex", flexDirection: "column", gap: "6px" }}>
                {domainStamps.map(([label, at, href]) => (
                  <Box key={label} sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", fontSize: "12.5px" }}>
                    <Box component="a" href={href} sx={{ color: ev.ink2, textDecoration: "none", "&:hover": { color: ev.accent, textDecoration: "underline" } }}>
                      {label}
                    </Box>
                    <EvidenceStamp at={at} now={model.now} />
                  </Box>
                ))}
              </Box>
            </Box>
          </EstateCard>

          {/* Stored activity & deltas */}
          <EstateCard>
            <Box sx={{ display: "flex", alignItems: "baseline", justifyContent: "space-between", px: "18px", pt: "14px", pb: "8px" }}>
              <Typography component="h2" sx={{ fontSize: "16px", fontWeight: 650 }}>
                Recent activity
              </Typography>
              <EvidenceStamp at={overview?.generated_at ?? model.newestEvidenceAt} source="stored" now={model.now} />
            </Box>
            <Box sx={{ px: "18px", pb: "16px" }}>
              {[
                {
                  label: `${PRODUCT_NAME} jobs completed (24 h)`,
                  value: overview ? String(overview.nexus?.completed_24h ?? "UNKNOWN") : "UNKNOWN",
                  isUnknown: !overview || overview.nexus?.completed_24h === undefined,
                  at: overview?.evidence?.jobs?.at ?? overview?.generated_at ?? null,
                  source: "jobs",
                },
                {
                  label: "Devices with changed configuration",
                  value:
                    overview?.attention?.config_changed?.state === "OK"
                      ? String(overview.attention.config_changed.count)
                      : "UNKNOWN",
                  isUnknown: overview?.attention?.config_changed?.state !== "OK",
                  at: overview?.evidence?.configuration?.at ?? null,
                  source: "config",
                },
                {
                  label: `User changes (${7} d)`,
                  value: totalUserChanges !== null ? `${totalUserChanges}${moreMark}${changesTruncated ? " (more exist)" : ""}` : changes.state === "failed" ? "UNKNOWN" : "—",
                  isUnknown: changes.state === "failed",
                  at: newestChangeAt ?? overview?.generated_at ?? null,
                  source: "changes",
                },
                {
                  label: `Changes with actor UNKNOWN (${7} d)`,
                  value: unknownChangeCount !== null ? `${unknownChangeCount}${moreMark}${changesTruncated ? " (first page only)" : ""}` : changes.state === "failed" ? "UNKNOWN" : "—",
                  isUnknown: changes.state === "failed" || (unknownChangeCount !== null && unknownChangeCount > 0),
                  at: newestChangeAt ?? overview?.generated_at ?? null,
                  source: "changes",
                },
              ].map((item, idx) => (
                <Box
                  key={item.label}
                  sx={{
                    display: "flex",
                    justifyContent: "space-between",
                    alignItems: "center",
                    gap: "10px",
                    py: "8px",
                    borderTop: idx > 0 ? `1px solid ${ev.line}` : "none",
                    fontSize: "13px",
                  }}
                >
                  <span style={{ color: ev.ink2 }}>{item.label}</span>
                  <Box sx={{ display: "inline-flex", alignItems: "center", gap: "8px" }}>
                    {item.value === "UNKNOWN" ? (
                      <StateBadge state="unk" word="UNKNOWN" dense />
                    ) : (
                      <Box
                        component="span"
                        sx={{
                          fontFamily: ESTATE_MONO,
                          fontWeight: 650,
                          color: item.isUnknown ? ev.unk : ev.ink,
                        }}
                      >
                        {item.value}
                      </Box>
                    )}
                    <EvidenceStamp at={item.at} source={item.source} now={model.now} />
                  </Box>
                </Box>
              ))}

              <Box
                sx={{
                  mt: "10px",
                  pt: "10px",
                  borderTop: `1px solid ${ev.line}`,
                  display: "flex",
                  flexDirection: "column",
                  gap: "6px",
                  fontSize: "12.5px",
                }}
              >
                <Typography sx={{ fontSize: "11px", textTransform: "uppercase", letterSpacing: "0.07em", color: ev.ink3, fontWeight: 650 }}>
                  Related workspaces
                </Typography>
                <Box component="a" href={changesHref()} sx={{ color: ev.accent, textDecoration: "none", "&:hover": { textDecoration: "underline" } }}>
                  Change history &amp; actors → Open Changes
                </Box>
                <Box component="a" href="?screen=trace" sx={{ color: ev.accent, textDecoration: "none", "&:hover": { textDecoration: "underline" } }}>
                  Ask (reachability & deciding rule) → Open Trace
                </Box>
                <Box component="a" href={casesHref} sx={{ color: ev.accent, textDecoration: "none", "&:hover": { textDecoration: "underline" } }}>
                  Cases (captures, sanitizer, diagnostics) → Open Tools
                </Box>
              </Box>
            </Box>
          </EstateCard>

          {/* Watchlist */}
          <EstateCard>
            <Box sx={{ display: "flex", alignItems: "baseline", justifyContent: "space-between", px: "18px", pt: "14px", pb: "8px" }}>
              <Typography component="h2" sx={{ fontSize: "16px", fontWeight: 650 }}>
                {watchlistIds.length > 0 ? "Your watchlist" : "Priority units"}
              </Typography>
              <Box component="a" href={wallHref()} sx={{ fontSize: "12.5px", color: ev.accent, textDecoration: "none" }}>
                Estate wall →
              </Box>
            </Box>
            <Box sx={{ px: "18px", pb: "14px" }}>
              {watchlistUnits.length === 0 ? (
                <Typography sx={{ fontSize: "13px", color: ev.ink3 }}>No enrolled units yet.</Typography>
              ) : (
                watchlistUnits.map((u, idx) => (
                  <Box
                    key={u.deviceId}
                    sx={{
                      display: "flex",
                      justifyContent: "space-between",
                      alignItems: "center",
                      gap: "8px",
                      py: "8px",
                      borderTop: idx > 0 ? `1px solid ${ev.line}` : "none",
                      fontSize: "13px",
                    }}
                  >
                    <Box sx={{ minWidth: 0 }}>
                      <Box
                        component="a"
                        href={dossierHref(u.deviceId)}
                        sx={{
                          fontFamily: ESTATE_MONO,
                          fontWeight: 600,
                          fontSize: "12.5px",
                          color: ev.accent,
                          textDecoration: "none",
                          "&:hover": { textDecoration: "underline" },
                        }}
                      >
                        {u.name}
                      </Box>
                      {u.clusterRef ? (
                        <Typography sx={{ fontSize: "11.5px", color: ev.ink3, fontFamily: ESTATE_SANS }}>
                          {u.clusterRef}
                        </Typography>
                      ) : null}
                    </Box>
                    <Box sx={{ display: "inline-flex", alignItems: "center", gap: "8px", flexShrink: 0 }}>
                      <StateBadge state={u.lenses.worst.badgeState} word={u.lenses.worst.badgeWord} dense />
                      {/* The stamp is the evidence time of the lens answer that produced the badge, never the inventory read. */}
                      <EvidenceStamp
                        at={u.lenses.worst.evidenceAt}
                        source={u.lenses.worst.evidenceSource}
                        missingLabel="evidence time unknown"
                        now={model.now}
                      />
                    </Box>
                  </Box>
                ))
              )}
            </Box>
          </EstateCard>
        </Box>
      </Box>
    </Box>
  );
}
