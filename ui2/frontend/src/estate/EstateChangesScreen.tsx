import { useCallback, useEffect, useMemo, useState } from "react";
import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import {
  downloadPolicyChanges,
  getChangeFeed,
  getPolicyChanges,
  type ChangeFeedPage,
  type ChangeKind,
  type PolicyChangesPage,
} from "../auth/adminApi";
import { PRODUCT_NAME } from "../brand/product";
import { sourceLabel } from "../screens/ChangeFeedPanel";
import type { SourceRead } from "../screens/overview/attentionRules";
import { isRestricted } from "../shell/States";
import { formatStamp } from "../shell/time";
import {
  EstateCard,
  EvidenceStamp,
  StateBadge,
  TileSwatch,
  UnitTile,
} from "./EstatePrimitives";
import { dossierHref, standardChangesHref } from "./estateRouting";
import {
  buildEstateChangesModel,
  groupChangeSetsByPerson,
  type EstateChangeCategory,
  type EstateChangeSet,
} from "./estateSlice2Model";
import { ESTATE_MONO, ESTATE_SANS, ev } from "./estateTokens";
import { useEstateData } from "./useEstateData";

type WindowDays = 1 | 7 | 30;

const ALL_FEED_KINDS: readonly ChangeKind[] = ["USER", "DEVICE_EVENT", "NEXUS_JOB"];

function categoryBadgeMeta(cat: EstateChangeCategory): { readonly label: string; readonly border: string; readonly fg: string } {
  switch (cat) {
    case "rule":
      return { label: "rules", border: ev.accent, fg: ev.accent };
    case "config":
      return { label: "configuration", border: ev.ok, fg: ev.ok };
    case "event":
      return { label: "device event", border: ev.watch, fg: ev.watch };
    case "job":
      return { label: `${PRODUCT_NAME} job`, border: ev.lineStrong, fg: ev.ink3 };
  }
}

function ChangeSetBlock({
  changeSet,
  open,
  onToggle,
  now,
  unitsById,
  unitsByName,
}: {
  readonly changeSet: EstateChangeSet;
  readonly open: boolean;
  readonly onToggle: () => void;
  readonly now: Date;
  readonly unitsById: ReadonlyMap<string, import("./estateModel").EstateUnit>;
  readonly unitsByName: ReadonlyMap<string, import("./estateModel").EstateUnit>;
}) {
  const catMeta = categoryBadgeMeta(changeSet.category);
  const resolvedUnits = useMemo(() => {
    const out: import("./estateModel").EstateUnit[] = [];
    const seen = new Set<string>();
    for (const id of changeSet.unitIds) {
      const u = unitsById.get(id);
      if (u && !seen.has(u.deviceId)) {
        seen.add(u.deviceId);
        out.push(u);
      }
    }
    for (const nm of changeSet.unitNames) {
      const u = unitsByName.get(nm);
      if (u && !seen.has(u.deviceId)) {
        seen.add(u.deviceId);
        out.push(u);
      }
    }
    return out;
  }, [changeSet.unitIds, changeSet.unitNames, unitsById, unitsByName]);

  const timeDisplay =
    changeSet.timeBasis === "DEVICE_LOCAL" && changeSet.deviceTimeText
      ? `${changeSet.deviceTimeText} (device clock)`
      : formatStamp(changeSet.time);

  return (
    <Box
      data-change-set={changeSet.id}
      data-change-category={changeSet.category}
      sx={{
        borderTop: `1px solid ${ev.line}`,
        p: "13px 18px",
        display: "grid",
        gridTemplateColumns: { xs: "1fr", md: "180px minmax(0, 1fr) 220px" },
        gap: { xs: "8px", md: "16px" },
        alignItems: "start",
      }}
    >
      {/* When + Evidence age */}
      <Box>
        <Typography sx={{ fontFamily: ESTATE_MONO, fontSize: "12px", color: ev.ink }}>
          {timeDisplay}
        </Typography>
        <Box sx={{ mt: "4px" }}>
          <EvidenceStamp at={changeSet.time} now={now} />
        </Box>
      </Box>

      {/* What + Before/After */}
      <Box sx={{ minWidth: 0 }}>
        <Box sx={{ display: "flex", gap: "8px", alignItems: "center", flexWrap: "wrap" }}>
          <Box
            component="span"
            sx={{
              fontFamily: ESTATE_MONO,
              fontWeight: 650,
              fontSize: "10.5px",
              letterSpacing: "0.05em",
              textTransform: "uppercase",
              px: "6px",
              py: "1px",
              borderRadius: "4px",
              border: `1px solid ${catMeta.border}`,
              color: catMeta.fg,
              whiteSpace: "nowrap",
            }}
          >
            {catMeta.label}
          </Box>
          <Typography sx={{ fontSize: "14.5px", fontWeight: 640, color: ev.ink, overflowWrap: "anywhere" }}>
            {changeSet.headline}
          </Typography>
        </Box>

        <Typography sx={{ fontSize: "12.5px", color: ev.ink2, mt: "3px", overflowWrap: "anywhere" }}>
          {changeSet.meta}
        </Typography>

        {changeSet.diffs.length > 0 ? (
          <Box sx={{ mt: "6px" }}>
            <Box
              component="button"
              type="button"
              aria-expanded={open}
              onClick={onToggle}
              sx={{
                background: "none",
                border: 0,
                p: 0,
                color: ev.accent,
                fontWeight: 620,
                fontSize: "12.5px",
                cursor: "pointer",
                fontFamily: ESTATE_SANS,
                "&:hover": { textDecoration: "underline" },
              }}
            >
              {open ? "Hide before and after ▴" : `Before and after (${changeSet.diffs.length}) ▾`}
            </Box>

            {open ? (
              <Box
                data-testid="change-before-after"
                sx={{
                  mt: "8px",
                  border: `1px solid ${ev.line}`,
                  borderRadius: "8px",
                  overflowX: "auto",
                }}
              >
                <Box
                  component="table"
                  aria-label={`Before and after for ${changeSet.headline}`}
                  sx={{
                    width: "100%",
                    borderCollapse: "collapse",
                    fontFamily: ESTATE_MONO,
                    fontSize: "12px",
                    "& th": {
                      fontFamily: ESTATE_SANS,
                      fontWeight: 650,
                      fontSize: "11px",
                      color: ev.ink3,
                      textTransform: "uppercase",
                      letterSpacing: "0.06em",
                      bgcolor: ev.surface2,
                      p: "6px 10px",
                      borderBottom: `1px solid ${ev.line}`,
                      textAlign: "left",
                    },
                    "& td": {
                      p: "6px 10px",
                      borderBottom: `1px solid ${ev.line}`,
                      verticalAlign: "top",
                      overflowWrap: "anywhere",
                    },
                    "& tr:last-of-type td": { borderBottom: 0 },
                  }}
                >
                  <thead>
                    <tr>
                      <th>Where</th>
                      <th>Field</th>
                      <th>Before</th>
                      <th>After</th>
                    </tr>
                  </thead>
                  <tbody>
                    {changeSet.diffs.slice(0, 25).map((d, idx) => (
                      <tr key={`${d.where}-${d.field}-${idx}`}>
                        <td>
                          {d.policyId && d.ruleId ? (
                            <Box
                              component="a"
                              href={`?screen=policy&policy_id=${encodeURIComponent(d.policyId)}&rule=${encodeURIComponent(d.ruleId)}&standard=1`}
                              sx={{ color: ev.accent, textDecoration: "none", "&:hover": { textDecoration: "underline" } }}
                            >
                              {d.where}
                            </Box>
                          ) : (
                            d.where
                          )}
                        </td>
                        <td style={{ color: ev.ink2 }}>{d.field}</td>
                        <td>
                          {d.before !== null ? (
                            <Box
                              component="span"
                              sx={{
                                display: "inline-block",
                                bgcolor: ev.actBg,
                                color: ev.act,
                                px: "6px",
                                py: "1px",
                                borderRadius: "4px",
                                textDecoration: "line-through",
                              }}
                            >
                              <Box
                                component="span"
                                sx={{
                                  fontFamily: ESTATE_SANS,
                                  fontWeight: 700,
                                  fontSize: "10px",
                                  letterSpacing: "0.05em",
                                  mr: "6px",
                                  display: "inline-block",
                                  textDecoration: "none",
                                }}
                              >
                                BEFORE
                              </Box>
                              {d.before}
                            </Box>
                          ) : (
                            <span style={{ color: ev.ink3 }}>—</span>
                          )}
                        </td>
                        <td>
                          {d.after !== null ? (
                            <Box
                              component="span"
                              sx={{
                                display: "inline-block",
                                bgcolor: ev.okBg,
                                color: ev.ok,
                                px: "6px",
                                py: "1px",
                                borderRadius: "4px",
                              }}
                            >
                              <Box
                                component="span"
                                sx={{
                                  fontFamily: ESTATE_SANS,
                                  fontWeight: 700,
                                  fontSize: "10px",
                                  letterSpacing: "0.05em",
                                  mr: "6px",
                                  display: "inline-block",
                                }}
                              >
                                AFTER
                              </Box>
                              {d.after}
                            </Box>
                          ) : (
                            <span style={{ color: ev.ink3 }}>—</span>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </Box>
              </Box>
            ) : null}
          </Box>
        ) : null}
      </Box>

      {/* Right side: Person badge + Affected unit tiles */}
      <Box
        sx={{
          display: "flex",
          flexDirection: "column",
          alignItems: { xs: "flex-start", md: "flex-end" },
          gap: "6px",
        }}
      >
        {changeSet.actorIsUnknown ? (
          <StateBadge
            state="unk"
            word={changeSet.actorIsAmbiguous ? "AMBIGUOUS actor" : "actor UNKNOWN"}
            dense
          />
        ) : changeSet.actorIsSystem ? (
          <StateBadge state="na" word={changeSet.actorLabel} dense />
        ) : (
          <Box
            component="span"
            sx={{
              fontFamily: ESTATE_MONO,
              fontWeight: 650,
              fontSize: "12.5px",
              color: ev.ink,
            }}
          >
            {changeSet.actorLabel}
          </Box>
        )}

        {resolvedUnits.length > 0 ? (
          <Box sx={{ display: "flex", gap: "3px", flexWrap: "wrap", justifyContent: "flex-end" }}>
            {resolvedUnits.slice(0, 8).map((u) => (
              <UnitTile key={u.deviceId} unit={u} lens="worst" size={18} now={now} />
            ))}
            {resolvedUnits.length > 8 ? (
              <span style={{ fontSize: "11.5px", color: ev.ink3 }}>+{resolvedUnits.length - 8}</span>
            ) : null}
          </Box>
        ) : changeSet.unitNames.length > 0 ? (
          <Box sx={{ display: "flex", gap: "4px", flexWrap: "wrap", justifyContent: "flex-end" }}>
            {changeSet.unitNames.slice(0, 3).map((nm, idx) => {
              const id = changeSet.unitIds[idx];
              return id ? (
                <Box
                  key={`${nm}-${idx}`}
                  component="a"
                  href={dossierHref(id)}
                  sx={{ fontFamily: ESTATE_MONO, fontSize: "11.5px", color: ev.accent }}
                >
                  {nm}
                </Box>
              ) : (
                <span key={`${nm}-${idx}`} style={{ fontFamily: ESTATE_MONO, fontSize: "11.5px", color: ev.ink2 }}>
                  {nm}
                </span>
              );
            })}
          </Box>
        ) : null}
      </Box>
    </Box>
  );
}

export function EstateChangesScreen({
  initialDeviceId = null,
  initialVendor = null,
}: {
  readonly initialDeviceId?: string | null;
  readonly initialVendor?: string | null;
}) {
  const { model } = useEstateData();
  const [windowDays, setWindowDays] = useState<WindowDays>(7);
  const [activeCategories, setActiveCategories] = useState<Set<EstateChangeCategory>>(
    () => new Set<EstateChangeCategory>(["rule", "config", "event"]),
  );
  const [includeInitial, setIncludeInitial] = useState<boolean>(false);
  const [actorFilter, setActorFilter] = useState<string | null>(null);
  const [groupingMode, setGroupingMode] = useState<"person" | "chronological">("person");
  const [openSetIds, setOpenSetIds] = useState<Record<string, boolean>>({});
  const [loading, setLoading] = useState<boolean>(true);
  const [policyRead, setPolicyRead] = useState<SourceRead<PolicyChangesPage>>({ state: "skipped" });
  const [feedRead, setFeedRead] = useState<SourceRead<ChangeFeedPage>>({ state: "skipped" });
  const [exportBusy, setExportBusy] = useState<boolean>(false);
  const [exportError, setExportError] = useState<string | null>(null);
  const [reloadSeq, setReloadSeq] = useState<number>(0);

  const now = useMemo(() => model?.now ?? new Date(), [model?.now, windowDays, reloadSeq]);

  const rangeIso = useMemo(() => {
    const end = new Date();
    const start = new Date(end.getTime() - windowDays * 86_400_000);
    return { from: start.toISOString(), to: end.toISOString() };
  }, [windowDays, reloadSeq]);

  useEffect(() => {
    let active = true;
    setLoading(true);

    const policyPromise: Promise<SourceRead<PolicyChangesPage>> = getPolicyChanges(
      {
        from: rangeIso.from,
        to: rangeIso.to,
        policy: "",
        device: initialDeviceId ?? "",
        actor: "",
        changeType: "",
        field: "",
        q: "",
        includeInitial,
      },
      "",
    ).then(
      (res) =>
        Array.isArray(res?.rows) && res?.summary
          ? ({ state: "ok", value: res } as const)
          : ({ state: "failed" } as const),
      (err: unknown) => ({ state: isRestricted(err) ? "restricted" : "failed" } as const),
    );

    const feedPromise: Promise<SourceRead<ChangeFeedPage>> = getChangeFeed({
      from: rangeIso.from,
      to: rangeIso.to,
      kinds: ALL_FEED_KINDS,
      vendor: initialVendor ?? undefined,
      device: initialDeviceId ?? undefined,
      limit: 200,
    }).then(
      (res) =>
        Array.isArray(res?.events)
          ? ({
              state: "ok",
              value: {
                ...res,
                coverage: Array.isArray(res.coverage) ? res.coverage : [],
                ordering_note: res.ordering_note ?? "",
              },
            } as const)
          : ({ state: "failed" } as const),
      (err: unknown) => ({ state: isRestricted(err) ? "restricted" : "failed" } as const),
    );

    Promise.all([policyPromise, feedPromise]).then(([pol, fd]) => {
      if (!active) return;
      setPolicyRead(pol);
      setFeedRead(fd);
      setLoading(false);
    });

    return () => {
      active = false;
    };
  }, [rangeIso, initialDeviceId, initialVendor, includeInitial]);

  const changesModel = useMemo(
    () =>
      buildEstateChangesModel({
        now,
        windowDays,
        policyChanges: policyRead,
        changeFeed: feedRead,
        activeCategories,
        actorFilter,
        model,
      }),
    [now, windowDays, policyRead, feedRead, activeCategories, actorFilter, model],
  );

  const groupedByPerson = useMemo(
    () => groupChangeSetsByPerson(changesModel.changeSets),
    [changesModel.changeSets],
  );

  const firstDiffSetId = useMemo(
    () => changesModel.changeSets.find((s) => s.diffs.length > 0)?.id ?? null,
    [changesModel.changeSets],
  );

  const isSetOpen = useCallback(
    (id: string) => {
      if (id in openSetIds) return openSetIds[id];
      return id === firstDiffSetId;
    },
    [openSetIds, firstDiffSetId],
  );

  const toggleCategory = (cat: EstateChangeCategory) => {
    setActiveCategories((prev) => {
      const next = new Set(prev);
      if (next.has(cat)) {
        if (next.size > 1) next.delete(cat);
      } else {
        next.add(cat);
      }
      return next;
    });
  };

  const handleExportCsv = async () => {
    setExportBusy(true);
    setExportError(null);
    try {
      const blob = await downloadPolicyChanges({
        from: rangeIso.from,
        to: rangeIso.to,
        policy: "",
        device: initialDeviceId ?? "",
        actor: actorFilter && actorFilter !== "UNKNOWN" ? actorFilter : "",
        changeType: "",
        field: "",
        q: "",
        includeInitial,
      });
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = "policy-changes.csv";
      link.click();
      URL.revokeObjectURL(url);
    } catch {
      setExportError("Rule changes CSV could not be exported.");
    } finally {
      setExportBusy(false);
    }
  };

  const bothFailed =
    !loading &&
    policyRead.state !== "ok" &&
    feedRead.state !== "ok";
  const bothRestricted =
    policyRead.state === "restricted" && feedRead.state === "restricted";

  const maxDayTotal = Math.max(1, ...changesModel.days.map((d) => d.total));
  const unitsById = model?.byId ?? new Map();
  const unitsByName = model?.byName ?? new Map();

  return (
    <Box data-testid="estate-changes-screen">
      {/* Page Header */}
      <Box
        sx={{
          display: "flex",
          flexWrap: "wrap",
          alignItems: "flex-end",
          gap: "16px",
          mb: "16px",
        }}
      >
        <Box sx={{ minWidth: 0, flex: "1 1 540px" }}>
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
              Changes
            </Typography>
            <EvidenceStamp
              at={changesModel.newestAt ?? model?.newestEvidenceAt}
              source="changes"
              now={now}
              missingLabel="UNKNOWN"
            />
          </Box>
          <Typography component="h1" sx={{ fontSize: "26px", fontWeight: 680, lineHeight: 1.2, mt: "2px" }}>
            Who changed what, and when
          </Typography>
          <Typography sx={{ fontSize: "14.5px", color: ev.ink2, mt: "6px", maxWidth: "860px" }}>
            Last {windowDays === 1 ? "24 hours" : `${windowDays} days`}:{" "}
            <Box component="b" sx={{ color: ev.ink }}>
              {policyRead.state === "ok" || feedRead.state === "ok" ? changesModel.totals.rules.toLocaleString("en-US") : "UNKNOWN"}
            </Box>{" "}
            rule {changesModel.totals.rules === 1 ? "change" : "changes"} by{" "}
            <Box component="b" sx={{ color: ev.ink }}>
              {changesModel.totals.distinctNamedPeople}
            </Box>{" "}
            named {changesModel.totals.distinctNamedPeople === 1 ? "actor" : "actors"},{" "}
            <Box component="b" sx={{ color: ev.ink }}>
              {changesModel.totals.config}
            </Box>{" "}
            configuration {changesModel.totals.config === 1 ? "change" : "changes"},{" "}
            <Box component="b" sx={{ color: ev.ink }}>
              {changesModel.totals.events}
            </Box>{" "}
            device {changesModel.totals.events === 1 ? "event" : "events"}.{" "}
            <Box component="b" sx={{ color: ev.unk }}>
              {changesModel.totals.unknownActorChanges} {changesModel.totals.unknownActorChanges === 1 ? "change has" : "changes have"} no recorded actor
            </Box>{" "}
            and stay UNKNOWN; {PRODUCT_NAME}’s own jobs stay apart unless selected.
          </Typography>
        </Box>

        <Box sx={{ display: "flex", gap: "8px", flexWrap: "wrap", ml: "auto" }}>
          <Box
            component="button"
            type="button"
            disabled={exportBusy || policyRead.state !== "ok"}
            onClick={() => void handleExportCsv()}
            sx={{
              height: "34px",
              px: "14px",
              borderRadius: "8px",
              border: `1px solid ${ev.lineStrong}`,
              bgcolor: ev.surface,
              color: ev.ink,
              fontSize: "13px",
              fontWeight: 580,
              cursor: policyRead.state === "ok" ? "pointer" : "not-allowed",
              opacity: policyRead.state === "ok" ? 1 : 0.6,
              fontFamily: ESTATE_SANS,
            }}
          >
            {exportBusy ? "Exporting…" : "Export rule CSV"}
          </Box>
          <Box
            component="a"
            href={standardChangesHref()}
            sx={{
              display: "inline-flex",
              alignItems: "center",
              height: "34px",
              px: "14px",
              borderRadius: "8px",
              border: `1px solid ${ev.lineStrong}`,
              bgcolor: ev.surface,
              color: ev.ink,
              fontSize: "13px",
              fontWeight: 580,
              textDecoration: "none",
              "&:hover": { borderColor: ev.ink2, textDecoration: "none" },
            }}
          >
            Standard Changes →
          </Box>
        </Box>
      </Box>

      {exportError ? (
        <Box sx={{ mb: "12px" }}>
          <StateBadge state="act" word={exportError} />
        </Box>
      ) : null}

      {/* Filter Chips */}
      <Box sx={{ display: "flex", gap: "6px", flexWrap: "wrap", alignItems: "center", mb: "14px" }}>
        {(
          [
            [1, "Last 24 h"],
            [7, "7 days"],
            [30, "30 days"],
          ] as const
        ).map(([days, label]) => {
          const on = windowDays === days;
          return (
            <Box
              key={days}
              component="button"
              type="button"
              aria-pressed={on}
              onClick={() => setWindowDays(days)}
              sx={{
                height: "27px",
                px: "11px",
                borderRadius: "999px",
                border: `1px solid ${on ? ev.ink : ev.line}`,
                bgcolor: ev.surface,
                color: on ? ev.ink : ev.ink2,
                fontWeight: on ? 650 : 500,
                fontSize: "12.5px",
                boxShadow: on ? `inset 0 0 0 1px ${ev.ink}` : "none",
                cursor: "pointer",
                fontFamily: ESTATE_SANS,
              }}
            >
              {label}
            </Box>
          );
        })}

        <span style={{ fontSize: "12px", color: ev.ink3, marginLeft: "10px", marginRight: "2px" }}>Kind</span>
        {(
          [
            ["rule", "Rule changes", changesModel.totals.rules],
            ["config", "Configuration", changesModel.totals.config],
            ["event", "Device events", changesModel.totals.events],
            ["job", `${PRODUCT_NAME} jobs`, changesModel.totals.jobs],
          ] as const
        ).map(([cat, label, count]) => {
          const on = activeCategories.has(cat);
          return (
            <Box
              key={cat}
              component="button"
              type="button"
              aria-pressed={on}
              onClick={() => toggleCategory(cat)}
              sx={{
                display: "inline-flex",
                alignItems: "center",
                gap: "6px",
                height: "27px",
                px: "11px",
                borderRadius: "999px",
                border: `1px solid ${on ? ev.ink : ev.line}`,
                bgcolor: ev.surface,
                color: on ? ev.ink : ev.ink2,
                fontWeight: on ? 650 : 500,
                fontSize: "12.5px",
                boxShadow: on ? `inset 0 0 0 1px ${ev.ink}` : "none",
                cursor: "pointer",
                fontFamily: ESTATE_SANS,
              }}
            >
              <span>{label}</span>
              <b style={{ fontFamily: ESTATE_MONO, fontSize: "11.5px" }}>{count}</b>
            </Box>
          );
        })}

        <span style={{ fontSize: "12px", color: ev.ink3, marginLeft: "10px", marginRight: "2px" }}>Include</span>
        <Box
          component="button"
          type="button"
          aria-pressed={includeInitial}
          onClick={() => setIncludeInitial((v) => !v)}
          sx={{
            display: "inline-flex",
            alignItems: "center",
            gap: "6px",
            height: "27px",
            px: "11px",
            borderRadius: "999px",
            border: `1px solid ${includeInitial ? ev.ink : ev.line}`,
            bgcolor: ev.surface,
            color: includeInitial ? ev.ink : ev.ink2,
            fontWeight: includeInitial ? 650 : 500,
            fontSize: "12.5px",
            boxShadow: includeInitial ? `inset 0 0 0 1px ${ev.ink}` : "none",
            cursor: "pointer",
            fontFamily: ESTATE_SANS,
          }}
        >
          <span>Initial captures (not changes)</span>
          {changesModel.totals.initialCaptures > 0 ? (
            <b style={{ fontFamily: ESTATE_MONO, fontSize: "11.5px" }}>{changesModel.totals.initialCaptures}</b>
          ) : null}
        </Box>

        {actorFilter ? (
          <Box
            component="button"
            type="button"
            onClick={() => setActorFilter(null)}
            sx={{
              height: "27px",
              px: "11px",
              borderRadius: "999px",
              border: `1px solid ${ev.accent}`,
              bgcolor: ev.accentBg,
              color: ev.accent,
              fontWeight: 650,
              fontSize: "12.5px",
              cursor: "pointer",
              fontFamily: ESTATE_MONO,
            }}
          >
            Person: {actorFilter} ×
          </Box>
        ) : null}
      </Box>

      {feedRead.state === "ok" && feedRead.value.next_cursor ? (
        <Box
          data-testid="changes-feed-truncated"
          sx={{ display: "flex", alignItems: "center", gap: "8px", mb: "14px", fontSize: "12.5px", color: ev.ink2 }}
        >
          <StateBadge state="watch" word="Partial feed" dense />
          <span>
            {feedRead.value.events.length} events shown, more exist in this range. Narrow the range or filter to see the rest;
            the counts on this screen cover only the shown events.
          </span>
        </Box>
      ) : null}

      {/* Source Coverage Strip (surfaces UNKNOWN / PARTIAL sources explicitly) */}
      {(policyRead.state === "failed" || feedRead.state === "failed" || changesModel.coverage.length > 0) ? (
        <Box
          sx={{
            display: "flex",
            flexWrap: "wrap",
            gap: "8px",
            alignItems: "center",
            mb: "14px",
            fontSize: "12px",
            color: ev.ink2,
          }}
        >
          <span style={{ color: ev.ink3, fontWeight: 600 }}>Source coverage:</span>
          {policyRead.state === "failed" ? (
            <StateBadge state="unk" word="Policy rule history: UNKNOWN" dense />
          ) : policyRead.state === "restricted" ? (
            <StateBadge state="na" word="Policy rule history: Restricted" dense />
          ) : null}
          {feedRead.state === "failed" ? (
            <StateBadge state="unk" word="Change feed: UNKNOWN" dense />
          ) : feedRead.state === "restricted" ? (
            <StateBadge state="na" word="Change feed: Restricted" dense />
          ) : null}
          {changesModel.coverage.map((c) => {
            const st =
              c.state === "OK"
                ? "ok"
                : c.state === "PARTIAL"
                  ? "watch"
                  : c.state === "UNAVAILABLE"
                    ? "unk"
                    : "na";
            const stateWord =
              c.state === "OK"
                ? "Read"
                : c.state === "UNAVAILABLE"
                  ? "UNKNOWN"
                  : c.state === "PARTIAL"
                    ? "Partial"
                    : c.state === "OFF"
                      ? "Off"
                      : "Not permitted";
            return (
              <StateBadge
                key={c.source}
                state={st}
                word={`${sourceLabel(c.source)}: ${stateWord}`}
                dense
              />
            );
          })}
        </Box>
      ) : null}

      {bothFailed ? (
        <EstateCard sx={{ p: 3 }}>
          <Box sx={{ display: "flex", alignItems: "center", gap: "10px", mb: 1.5 }}>
            <StateBadge state={bothRestricted ? "na" : "unk"} word={bothRestricted ? "Restricted" : "UNKNOWN"} />
            <Typography sx={{ fontWeight: 650 }}>
              {bothRestricted
                ? "Change history is restricted for this account."
                : "Neither change source could be read — change counts are UNKNOWN."}
            </Typography>
          </Box>
          {!bothRestricted ? (
            <Box
              component="button"
              type="button"
              onClick={() => setReloadSeq((n) => n + 1)}
              sx={{
                height: "32px",
                px: "12px",
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
      ) : (
        <>
          {/* Two-Column Visual Row: Changes per day + Grouped by person swimlanes */}
          <Box
            sx={{
              display: "grid",
              gridTemplateColumns: { xs: "1fr", lg: "minmax(0, 1.2fr) minmax(0, 1fr)" },
              gap: "16px",
              alignItems: "start",
            }}
          >
            {/* Card 1: Change volume by day */}
            <EstateCard>
              <Box sx={{ display: "flex", alignItems: "baseline", gap: "10px", px: "18px", pt: "14px", pb: "8px", flexWrap: "wrap" }}>
                <Typography component="h2" sx={{ fontSize: "17px", fontWeight: 650 }}>
                  Changes per day
                </Typography>
                <Box
                  sx={{
                    ml: "auto",
                    display: "flex",
                    gap: "12px",
                    flexWrap: "wrap",
                    alignItems: "center",
                    fontSize: "12px",
                    color: ev.ink2,
                  }}
                >
                  <Box component="span" sx={{ display: "inline-flex", alignItems: "center", gap: "5px" }}>
                    <Box component="span" sx={{ width: 12, height: 12, borderRadius: "3px", bgcolor: ev.accent, display: "inline-block" }} />
                    <span>rule</span>
                  </Box>
                  <Box component="span" sx={{ display: "inline-flex", alignItems: "center", gap: "5px" }}>
                    <TileSwatch state="ok" size={12} />
                    <span>configuration</span>
                  </Box>
                  <Box component="span" sx={{ display: "inline-flex", alignItems: "center", gap: "5px" }}>
                    <TileSwatch state="watch" size={12} />
                    <span>device event</span>
                  </Box>
                  <EvidenceStamp at={changesModel.newestAt} source="window" now={now} missingLabel="0" />
                </Box>
              </Box>

              <Box sx={{ px: "18px", pb: "14px" }}>
                <Box
                  component="svg"
                  viewBox="0 0 620 205"
                  role="img"
                  aria-label="Changes per day"
                  sx={{ display: "block", width: "100%", height: "auto" }}
                >
                  {[0, 0.25, 0.5, 0.75, 1].map((frac) => {
                    const y = 26 + 140 - frac * 140;
                    const val = Math.round(frac * maxDayTotal);
                    return (
                      <g key={frac}>
                        <line x1={38} x2={608} y1={y} y2={y} stroke={ev.line} strokeWidth={1} />
                        <text x={32} y={y + 4} textAnchor="end" style={{ fontSize: "11px", fill: ev.ink3, fontFamily: ESTATE_MONO }}>
                          {val}
                        </text>
                      </g>
                    );
                  })}

                  {changesModel.days.map((d, idx) => {
                    const count = changesModel.days.length;
                    const slotW = 564 / Math.max(1, count);
                    const barW = Math.max(8, Math.min(48, slotW * 0.68));
                    const x = 44 + idx * slotW + (slotW - barW) / 2;
                    const scale = 140 / maxDayTotal;
                    const hRule = d.rules * scale;
                    const hCfg = d.config * scale;
                    const hEvt = d.events * scale;
                    const hJob = d.jobs * scale;
                    const yRule = 166 - hRule;
                    const yCfg = yRule - hCfg;
                    const yEvt = yCfg - hEvt;
                    const yJob = yEvt - hJob;
                    const showLabel = count <= 10 || idx % 3 === 0 || idx === count - 1;

                    return (
                      <g key={d.dayKey} data-day-bucket={d.dayKey} data-day-total={d.total}>
                        {hRule > 0 ? (
                          <rect x={x} y={yRule} width={barW} height={Math.max(2, hRule)} rx={2} fill={ev.accent} opacity={0.88}>
                            <title>{`${d.label}: ${d.rules} rule changes`}</title>
                          </rect>
                        ) : null}
                        {hCfg > 0 ? (
                          <rect x={x} y={yCfg} width={barW} height={Math.max(2, hCfg)} rx={2} fill={ev.tOk}>
                            <title>{`${d.label}: ${d.config} configuration changes`}</title>
                          </rect>
                        ) : null}
                        {hEvt > 0 ? (
                          <rect x={x} y={yEvt} width={barW} height={Math.max(2, hEvt)} rx={2} fill={ev.tWatch}>
                            <title>{`${d.label}: ${d.events} device events`}</title>
                          </rect>
                        ) : null}
                        {hJob > 0 ? (
                          <rect x={x} y={yJob} width={barW} height={Math.max(2, hJob)} rx={2} fill={ev.tNa}>
                            <title>{`${d.label}: ${d.jobs} ${PRODUCT_NAME} jobs`}</title>
                          </rect>
                        ) : null}
                        {d.total > 0 && count <= 14 ? (
                          <text
                            x={x + barW / 2}
                            y={Math.max(14, yJob - 5)}
                            textAnchor="middle"
                            style={{ fontSize: "10.5px", fontWeight: 650, fill: ev.ink, fontFamily: ESTATE_MONO }}
                          >
                            {d.total}
                          </text>
                        ) : null}
                        {showLabel ? (
                          <text
                            x={x + barW / 2}
                            y={186}
                            textAnchor="middle"
                            style={{ fontSize: "10.5px", fill: ev.ink3, fontFamily: ESTATE_SANS }}
                          >
                            {count <= 10 ? d.label : d.shortDay}
                          </text>
                        ) : null}
                      </g>
                    );
                  })}
                </Box>
              </Box>
            </EstateCard>

            {/* Card 2: Grouped by person swimlanes */}
            <EstateCard>
              <Box sx={{ display: "flex", alignItems: "baseline", gap: "10px", px: "18px", pt: "14px", pb: "8px", flexWrap: "wrap" }}>
                <Typography component="h2" sx={{ fontSize: "17px", fontWeight: 650 }}>
                  Who (grouped by person)
                </Typography>
                <Typography sx={{ fontSize: "12px", color: ev.ink3 }}>
                  click an actor to filter; UNKNOWN stays dashed
                </Typography>
                <Box sx={{ ml: "auto" }}>
                  <EvidenceStamp at={changesModel.newestAt} source="actors" now={now} missingLabel="0" />
                </Box>
              </Box>

              <Box sx={{ px: "18px", pb: "14px" }}>
                {loading && changesModel.personLanes.length === 0 ? (
                  <Typography sx={{ fontSize: "13px", color: ev.ink2, py: 2 }}>Reading stored change actors…</Typography>
                ) : changesModel.personLanes.length === 0 ? (
                  <Typography sx={{ fontSize: "13px", color: ev.ink3, py: 2 }}>
                    No changes recorded in this window.
                  </Typography>
                ) : (
                  <Box sx={{ display: "flex", flexDirection: "column", gap: "6px" }}>
                    {changesModel.personLanes.slice(0, 10).map((lane) => {
                      const selected = actorFilter === lane.actorKey;
                      const dayCount = lane.perDay.length;
                      return (
                        <Box
                          key={lane.actorKey}
                          component="button"
                          type="button"
                          data-person-lane={lane.actorKey}
                          aria-pressed={selected}
                          onClick={() => setActorFilter(selected ? null : lane.actorKey)}
                          sx={{
                            display: "grid",
                            gridTemplateColumns: "140px minmax(0, 1fr) auto",
                            alignItems: "center",
                            gap: "10px",
                            p: "6px 8px",
                            borderRadius: "8px",
                            border: `1px solid ${selected ? ev.accent : "transparent"}`,
                            bgcolor: selected ? ev.accentBg : "transparent",
                            cursor: "pointer",
                            textAlign: "left",
                            fontFamily: ESTATE_SANS,
                            "&:hover": { bgcolor: selected ? ev.accentBg : ev.bg },
                          }}
                        >
                          <Box sx={{ minWidth: 0, overflow: "hidden", textOverflow: "ellipsis" }}>
                            {lane.isUnknown ? (
                              <StateBadge
                                state="unk"
                                word={lane.isAmbiguous ? "AMBIGUOUS" : "actor UNKNOWN"}
                                dense
                              />
                            ) : lane.isSystem ? (
                              <StateBadge state="na" word={lane.actorLabel} dense />
                            ) : (
                              <Typography
                                sx={{
                                  fontFamily: ESTATE_MONO,
                                  fontWeight: 650,
                                  fontSize: "12px",
                                  color: ev.ink,
                                }}
                                noWrap
                              >
                                {lane.actorLabel}
                              </Typography>
                            )}
                          </Box>

                          <Box
                            component="svg"
                            viewBox="0 0 240 24"
                            aria-hidden
                            sx={{ width: "100%", height: "22px", display: "block" }}
                          >
                            <line x1={8} x2={232} y1={12} y2={12} stroke={ev.line} strokeWidth={1} />
                            {lane.perDay.map((v, j) => {
                              if (v <= 0) return null;
                              const cx = dayCount <= 1 ? 120 : 12 + (j * 216) / (dayCount - 1);
                              const r = Math.min(9, Math.max(3, Math.sqrt(v) * 1.4));
                              return lane.isUnknown ? (
                                <rect
                                  key={j}
                                  x={cx - r}
                                  y={12 - r}
                                  width={r * 2}
                                  height={r * 2}
                                  fill="none"
                                  stroke={ev.unk}
                                  strokeWidth={1.6}
                                  strokeDasharray="2 2"
                                />
                              ) : (
                                <circle
                                  key={j}
                                  cx={cx}
                                  cy={12}
                                  r={r}
                                  fill={lane.isSystem ? ev.tNa : ev.accent}
                                  opacity={0.85}
                                />
                              );
                            })}
                          </Box>

                          <Box sx={{ display: "inline-flex", alignItems: "center", gap: "6px" }}>
                            <Box
                              component="b"
                              sx={{
                                fontFamily: ESTATE_MONO,
                                fontSize: "12.5px",
                                color: lane.isUnknown ? ev.unk : ev.ink,
                              }}
                            >
                              {lane.totalChanges}
                            </Box>
                            <EvidenceStamp at={lane.newestAt} now={now} />
                          </Box>
                        </Box>
                      );
                    })}
                  </Box>
                )}
              </Box>
            </EstateCard>
          </Box>

          {/* Change Sets Card (Grouped by person or Chronological, with Before and After) */}
          <EstateCard sx={{ mt: "16px" }}>
            <Box
              sx={{
                display: "flex",
                flexWrap: "wrap",
                alignItems: "center",
                gap: "12px",
                px: "18px",
                pt: "14px",
                pb: "10px",
              }}
            >
              <Typography component="h2" sx={{ fontSize: "17px", fontWeight: 650 }}>
                {groupingMode === "person" ? "Changes grouped by person" : "Change sets, newest first"}
              </Typography>
              <Typography sx={{ fontSize: "12.5px", color: ev.ink3 }}>
                rule revisions grouped by collection; the feed orders stored observations and never claims causation
              </Typography>

              <Box
                role="group"
                aria-label="Change list layout"
                sx={{
                  ml: "auto",
                  display: "inline-flex",
                  border: `1px solid ${ev.lineStrong}`,
                  borderRadius: "8px",
                  overflow: "hidden",
                }}
              >
                {(
                  [
                    ["person", "Grouped by person"],
                    ["chronological", "Newest first"],
                  ] as const
                ).map(([modeKey, label], idx) => {
                  const on = groupingMode === modeKey;
                  return (
                    <Box
                      key={modeKey}
                      component="button"
                      type="button"
                      aria-pressed={on}
                      onClick={() => setGroupingMode(modeKey)}
                      sx={{
                        border: 0,
                        borderLeft: idx > 0 ? `1px solid ${ev.line}` : "none",
                        bgcolor: on ? ev.ink : ev.surface,
                        color: on ? ev.surface : ev.ink2,
                        px: "10px",
                        py: "5px",
                        fontSize: "12px",
                        fontWeight: on ? 650 : 500,
                        cursor: "pointer",
                        fontFamily: ESTATE_SANS,
                      }}
                    >
                      {label}
                    </Box>
                  );
                })}
              </Box>
            </Box>

            {loading && changesModel.changeSets.length === 0 ? (
              <Box sx={{ p: "18px", borderTop: `1px solid ${ev.line}` }}>
                <Typography sx={{ color: ev.ink2 }}>Loading stored changes…</Typography>
              </Box>
            ) : changesModel.changeSets.length === 0 ? (
              <Box sx={{ p: "18px", borderTop: `1px solid ${ev.line}` }}>
                <Typography sx={{ color: ev.ink2 }}>
                  No stored changes match the active window and filters.
                  {changesModel.unavailableSources.length > 0
                    ? " Sources marked UNKNOWN or Partial above may hold un-read changes."
                    : ""}
                </Typography>
              </Box>
            ) : groupingMode === "person" ? (
              groupedByPerson.map((grp) => (
                <Box
                  key={grp.actorKey}
                  data-person-group={grp.actorKey}
                  sx={{ borderTop: `1px solid ${ev.line}` }}
                >
                  <Box
                    sx={{
                      display: "flex",
                      alignItems: "center",
                      gap: "10px",
                      flexWrap: "wrap",
                      px: "18px",
                      py: "9px",
                      bgcolor: ev.surface2,
                    }}
                  >
                    {grp.isUnknown ? (
                      <StateBadge
                        state="unk"
                        word={grp.isAmbiguous ? "AMBIGUOUS actor" : "actor UNKNOWN"}
                        dense
                      />
                    ) : grp.isSystem ? (
                      <StateBadge state="na" word={grp.actorLabel} dense />
                    ) : (
                      <Typography sx={{ fontFamily: ESTATE_MONO, fontWeight: 700, fontSize: "13px" }}>
                        {grp.actorLabel}
                      </Typography>
                    )}
                    <Typography sx={{ fontSize: "12.5px", color: ev.ink2 }}>
                      <b style={{ fontFamily: ESTATE_MONO, color: ev.ink }}>{grp.totalChanges}</b>{" "}
                      {grp.totalChanges === 1 ? "change" : "changes"} across {grp.sets.length}{" "}
                      {grp.sets.length === 1 ? "set" : "sets"}
                    </Typography>
                    <Box sx={{ ml: "auto" }}>
                      <EvidenceStamp at={grp.sets[0]?.time} source="latest" now={now} />
                    </Box>
                  </Box>
                  {grp.sets.slice(0, 15).map((s) => (
                    <ChangeSetBlock
                      key={s.id}
                      changeSet={s}
                      open={isSetOpen(s.id)}
                      onToggle={() => setOpenSetIds((prev) => ({ ...prev, [s.id]: !isSetOpen(s.id) }))}
                      now={now}
                      unitsById={unitsById}
                      unitsByName={unitsByName}
                    />
                  ))}
                </Box>
              ))
            ) : (
              changesModel.changeSets.slice(0, 40).map((s) => (
                <ChangeSetBlock
                  key={s.id}
                  changeSet={s}
                  open={isSetOpen(s.id)}
                  onToggle={() => setOpenSetIds((prev) => ({ ...prev, [s.id]: !isSetOpen(s.id) }))}
                  now={now}
                  unitsById={unitsById}
                  unitsByName={unitsByName}
                />
              ))
            )}

            {changesModel.orderingNote ? (
              <Box sx={{ px: "18px", py: "10px", borderTop: `1px solid ${ev.line}`, fontSize: "12px", color: ev.ink3 }}>
                {changesModel.orderingNote}
              </Box>
            ) : null}
          </EstateCard>
        </>
      )}
    </Box>
  );
}
