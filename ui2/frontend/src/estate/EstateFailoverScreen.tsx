import { useEffect, useMemo, useState, type KeyboardEvent } from "react";
import Box from "@mui/material/Box";
import Tooltip from "@mui/material/Tooltip";
import Typography from "@mui/material/Typography";
import { formatStamp } from "../shell/time";
import {
  EstateCard,
  EvidenceStamp,
  HaPairTiles,
  StateBadge,
  TileSwatch,
  VendorTag,
} from "./EstatePrimitives";
import { EstateFailoverCellDrawer, PER_UNIT_PRECHECK_NOTE, type FailoverCellTarget } from "./EstateFailoverCellDrawer";
import { EstateFailoverIncidents } from "./EstateFailoverIncidents";
import { readinessStopReason } from "../screens/ReadinessChecksTable";
import { dossierHref, standardHaHref } from "./estateRouting";
import {
  buildFailoverBoardModel,
  type FailoverBoardUnit,
  type FailoverCheckColumn,
  type FailoverMatrixCell,
  type FailoverMemberRef,
} from "./estateSlice2Model";
import { ESTATE_MONO, ESTATE_SANS, ev } from "./estateTokens";
import { useEstateData } from "./useEstateData";

function MemberLinksLine({
  activeMembers,
  standbyMembers,
  allMembers,
  mode,
}: {
  readonly activeMembers: readonly FailoverMemberRef[];
  readonly standbyMembers: readonly FailoverMemberRef[];
  readonly allMembers: readonly FailoverMemberRef[];
  readonly mode: string | null;
}) {
  const renderMember = (m: FailoverMemberRef, idx: number) => (
    <Box component="span" key={`${m.deviceId ?? m.name}-${idx}`}>
      {idx > 0 ? ", " : null}
      {m.deviceId && m.inEstate ? (
        <Box
          component="a"
          href={dossierHref(m.deviceId)}
          sx={{
            fontFamily: ESTATE_MONO,
            fontSize: "12px",
            color: ev.accent,
            fontWeight: 600,
            textDecoration: "none",
            "&:hover": { textDecoration: "underline" },
          }}
        >
          {m.name}
        </Box>
      ) : (
        <Box component="span" sx={{ fontFamily: ESTATE_MONO, fontSize: "12px", color: ev.ink }}>
          {m.name}
        </Box>
      )}
    </Box>
  );

  if (activeMembers.length > 0 || standbyMembers.length > 0) {
    return (
      <Box sx={{ fontSize: "12px", color: ev.ink3, lineHeight: 1.45 }}>
        {activeMembers.length > 0 ? (
          <>
            {activeMembers.map(renderMember)} <span>active</span>
          </>
        ) : (
          <span style={{ color: ev.unk, fontWeight: 600 }}>active UNKNOWN</span>
        )}
        {" ↔ "}
        {standbyMembers.length > 0 ? (
          <>
            {standbyMembers.map(renderMember)} <span>standby</span>
          </>
        ) : (
          <span style={{ color: ev.unk, fontWeight: 600 }}>standby UNKNOWN</span>
        )}
        {mode ? ` · ${mode}` : ""}
      </Box>
    );
  }

  return (
    <Box sx={{ fontSize: "12px", color: ev.ink3, lineHeight: 1.45 }}>
      {allMembers.length > 0 ? allMembers.map(renderMember) : "Members UNKNOWN"}
      {mode ? ` · ${mode}` : ""}
    </Box>
  );
}

function UnitKindTag({ kind, vsName }: { readonly kind: FailoverBoardUnit["unitKind"]; readonly vsName: string | null }) {
  if (kind === "CLUSTER") return null;
  const label = kind === "VSX_CHASSIS" ? "VSX chassis" : `VS · ${vsName ?? "unit"}`;
  return (
    <Box
      component="span"
      data-failover-unit-kind={kind}
      sx={{
        fontFamily: ESTATE_MONO,
        fontWeight: 650,
        fontSize: "10.5px",
        px: "5px",
        py: "1px",
        borderRadius: "4px",
        bgcolor: ev.accentBg,
        color: ev.accent,
        border: `1px solid ${ev.accent}`,
        whiteSpace: "nowrap",
      }}
    >
      {label}
    </Box>
  );
}

function BoardUnitCard({
  unit,
  highlighted,
  now,
}: {
  readonly unit: FailoverBoardUnit;
  readonly highlighted: boolean;
  readonly now: Date;
}) {
  return (
    <Box
      data-failover-card={unit.unitId}
      sx={{
        bgcolor: ev.surface,
        border: `1px solid ${highlighted ? ev.accent : unit.badgeState === "unk" ? ev.unk : ev.line}`,
        borderStyle: unit.badgeState === "unk" ? "dashed" : "solid",
        boxShadow: highlighted ? `0 0 0 2px ${ev.accentBg}` : "none",
        borderRadius: "10px",
        p: "10px 12px",
        mb: "8px",
      }}
    >
      <Box sx={{ display: "flex", alignItems: "center", gap: "7px", flexWrap: "wrap" }}>
        {unit.estateCluster && unit.unitKind !== "VIRTUAL_SYSTEM" ? (
          <HaPairTiles cluster={unit.estateCluster} lens="failover" size={18} now={now} />
        ) : (
          <TileSwatch state={unit.badgeState} size={18} label={`${unit.label}: ${unit.badgeWord}`} />
        )}
        <Box component="span" sx={{ fontFamily: ESTATE_MONO, fontWeight: 700, fontSize: "13px", color: ev.ink }}>
          {unit.clusterTitle}
        </Box>
        <UnitKindTag kind={unit.unitKind} vsName={unit.virtualSystemName} />
        <VendorTag label={unit.vendorName} />
      </Box>

      <Box sx={{ mt: "4px" }}>
        <MemberLinksLine
          activeMembers={unit.activeMembers}
          standbyMembers={unit.standbyMembers}
          allMembers={unit.allMembers}
          mode={unit.mode}
        />
      </Box>

      <Typography sx={{ fontSize: "12.5px", color: ev.ink, mt: "6px" }}>{unit.reason}</Typography>

      {readinessStopReason(unit.stopCode) ? (
        <Typography sx={{ fontSize: "12px", color: ev.ink2, mt: "3px" }}>
          {readinessStopReason(unit.stopCode)}
        </Typography>
      ) : null}

      {unit.unitKind === "VSX_CHASSIS" ? (
        <Typography sx={{ fontSize: "11.5px", color: ev.ink3, mt: "3px" }}>
          {PER_UNIT_PRECHECK_NOTE}
        </Typography>
      ) : null}

      <Box
        sx={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          gap: "8px",
          flexWrap: "wrap",
          mt: "8px",
        }}
      >
        <EvidenceStamp
          at={unit.observedAt}
          source="evaluated"
          now={now}
          missingLabel="not evaluated"
        />
        <Box sx={{ display: "inline-flex", gap: "10px", alignItems: "center", fontSize: "12px" }}>
          <Box
            component="a"
            href={`#mx-${encodeURIComponent(unit.key)}`}
            sx={{ color: ev.ink2, textDecoration: "none", "&:hover": { color: ev.ink, textDecoration: "underline" } }}
          >
            Matrix ↓
          </Box>
          <Box
            component="a"
            href={standardHaHref(unit.clusterRef)}
            sx={{ color: ev.accent, fontWeight: 600, textDecoration: "none", "&:hover": { textDecoration: "underline" } }}
          >
            Standard HA →
          </Box>
        </Box>
      </Box>
    </Box>
  );
}

/** The matrix column header names the pre-check a cell belongs to; the cell name stays short. */
function checkHeaderId(colIdx: number): string {
  return `fo-check-col-${colIdx}`;
}

function toCellTarget(open: {
  readonly unit: FailoverBoardUnit;
  readonly col: FailoverCheckColumn;
  readonly cell: FailoverMatrixCell;
}): FailoverCellTarget {
  return {
    unitLabel: open.unit.label,
    unitKind: open.unit.unitKind,
    checkTitle: open.col.title,
    cell: open.cell,
    standardHref: standardHaHref(open.unit.clusterRef),
  };
}

export function EstateFailoverScreen({
  initialClusterRef = null,
}: {
  readonly initialClusterRef?: string | null;
}) {
  const { loading, fatalError, devices, failover, model, refresh } = useEstateData();
  const [matrixFilter, setMatrixFilter] = useState<"all" | "attention">("all");
  const [vendorFilter, setVendorFilter] = useState<string>("");
  const [focusedRef, setFocusedRef] = useState<string | null>(initialClusterRef);
  const [openCell, setOpenCell] = useState<{
    readonly unit: FailoverBoardUnit;
    readonly col: FailoverCheckColumn;
    readonly cell: FailoverMatrixCell;
  } | null>(null);

  useEffect(() => {
    setFocusedRef(initialClusterRef);
  }, [initialClusterRef]);

  const now = model?.now ?? new Date();

  const board = useMemo(
    () => buildFailoverBoardModel({ now, devices, failover, model }),
    [now, devices, failover, model],
  );

  const matrixUnits = useMemo(() => {
    return board.units.filter((u) => {
      if (vendorFilter && u.vendor !== vendorFilter) return false;
      if (matrixFilter === "attention" && u.boardColumn === "ready_fresh") return false;
      return true;
    });
  }, [board.units, matrixFilter, vendorFilter]);

  const distinctVendors = useMemo(
    () =>
      [...new Map(board.units.map((u) => [u.vendor, u.vendorName] as const)).entries()].sort((a, b) =>
        a[1].localeCompare(b[1]),
      ),
    [board.units],
  );

  if (fatalError) {
    return (
      <EstateCard sx={{ p: 3 }}>
        <Typography component="h1" sx={{ fontSize: "24px", fontWeight: 650, mb: 1 }}>
          Failover readiness
        </Typography>
        <Box sx={{ mb: 1.5 }}>
          <StateBadge state={fatalError.restricted ? "na" : "unk"} word={fatalError.restricted ? "Restricted" : "UNKNOWN"} />
        </Box>
        <Typography sx={{ color: ev.ink2, mb: 2 }}>{fatalError.message}</Typography>
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
          Failover readiness
        </Typography>
        <Typography sx={{ color: ev.ink2 }}>Reading HA pairs, VSX units, and stored pre-check evaluations…</Typography>
      </EstateCard>
    );
  }

  const ct = board.clusterTotals;
  const attentionUnitCount = board.notReady.length + board.unknown.length + board.readyStale.length;

  return (
    <Box data-testid="estate-failover-screen">
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
              Estate · failover lens
            </Typography>
            <EvidenceStamp
              at={board.newestEvaluatedAt}
              source="readiness"
              now={now}
              missingLabel="not evaluated"
            />
          </Box>
          <Typography component="h1" sx={{ fontSize: "26px", fontWeight: 680, lineHeight: 1.2, mt: "2px" }}>
            Is each cluster safe to fail over?
          </Typography>
          {board.sourceState !== "ok" ? (
            <Box sx={{ display: "flex", alignItems: "center", gap: "10px", mt: "6px", flexWrap: "wrap" }}>
              <StateBadge
                state={board.sourceState === "restricted" ? "na" : "unk"}
                word={board.sourceState === "restricted" ? "Restricted" : "UNKNOWN"}
              />
              <Typography sx={{ fontSize: "14.5px", color: ev.ink2 }}>
                {board.sourceState === "restricted"
                  ? "Failover readiness summary is restricted for this account."
                  : "Failover readiness summary could not be read — every cluster stays UNKNOWN until a read succeeds."}
              </Typography>
            </Box>
          ) : (
            <Typography sx={{ fontSize: "14.5px", color: ev.ink2, mt: "6px", maxWidth: "860px" }}>
              <Box component="b" sx={{ color: ev.ink }}>
                {ct.readyFresh} of {ct.supported}
              </Box>{" "}
              evaluated HA {ct.supported === 1 ? "cluster passes" : "clusters pass"} every pre-check within 24 h.{" "}
              <Box component="b" sx={{ color: ct.notReady > 0 ? ev.act : ev.ink2 }}>
                {ct.notReady} would not fail over cleanly
              </Box>
              ,{" "}
              <Box component="b" sx={{ color: ct.unknown > 0 ? ev.unk : ev.ink2 }}>
                {ct.unknown} {ct.unknown === 1 ? "is" : "are"} UNKNOWN
              </Box>{" "}
              and{" "}
              <Box component="b" sx={{ color: ct.readyStale > 0 ? ev.watch : ev.ink2 }}>
                {ct.readyStale} passed on evidence older than 24 h
              </Box>
              .
              {ct.vsUnits > 0
                ? ` ${ct.vsUnits} VSX Virtual ${ct.vsUnits === 1 ? "System is" : "Systems are"} evaluated separately alongside ${ct.vsUnits === 1 ? "its" : "their"} chassis.`
                : ""}
              {ct.unsupported > 0 ? ` ${ct.unsupported} enrolled ${ct.unsupported === 1 ? "pair is" : "pairs are"} not supported.` : ""}
            </Typography>
          )}
        </Box>

        <Box sx={{ display: "flex", gap: "8px", flexWrap: "wrap", ml: "auto" }}>
          <Box
            component="a"
            href={standardHaHref(focusedRef)}
            data-testid="failover-standard-link"
            sx={{
              display: "inline-flex",
              alignItems: "center",
              gap: "6px",
              height: "34px",
              px: "14px",
              borderRadius: "8px",
              border: `1px solid ${ev.accent}`,
              bgcolor: ev.accent,
              color: ev.accentInk,
              fontSize: "13px",
              fontWeight: 620,
              textDecoration: "none",
              whiteSpace: "nowrap",
              "&:hover": { opacity: 0.92, textDecoration: "none" },
            }}
          >
            Open standard HA & readiness →
          </Box>
        </Box>
      </Box>

      {/* Rule Banner (no execution buttons in Estate view) */}
      <Box
        sx={{
          display: "flex",
          flexWrap: "wrap",
          gap: "12px",
          alignItems: "center",
          p: "11px 16px",
          borderRadius: "10px",
          bgcolor: ev.surface2,
          border: `1px solid ${ev.line}`,
          fontSize: "13px",
          mb: "16px",
        }}
      >
        <StateBadge state="na" word="Rule" dense />
        <span style={{ color: ev.ink2 }}>
          Readiness is one input to a failover decision, never the decision. Estate view has no execution buttons — open{" "}
          <Box
            component="a"
            href={standardHaHref(focusedRef)}
            sx={{ color: ev.accent, fontWeight: 600, textDecoration: "none", "&:hover": { textDecoration: "underline" } }}
          >
            standard HA & readiness
          </Box>{" "}
          to run pre-checks, sync configuration, or manage failover approvals.
        </span>
        <Box sx={{ ml: "auto" }}>
          <EvidenceStamp
            at={board.newestEvaluatedAt}
            source="newest evaluation"
            now={now}
            missingLabel="not evaluated"
          />
        </Box>
      </Box>

      {/* 4-Column Readiness Board */}
      <Box
        data-testid="failover-board-columns"
        sx={{
          display: "grid",
          gridTemplateColumns: {
            xs: "1fr",
            md: "repeat(2, minmax(0, 1fr))",
            lg: "minmax(0, 1.25fr) minmax(0, 1fr) minmax(0, 1fr) minmax(0, 1.15fr)",
          },
          gap: "12px",
          alignItems: "start",
        }}
      >
        {/* Column 1: Not ready */}
        <Box
          data-board-col="not_ready"
          sx={{ bgcolor: ev.surface2, borderRadius: "12px", p: "10px", border: `1px solid ${ev.line}` }}
        >
          <Box sx={{ display: "flex", alignItems: "center", gap: "8px", p: "4px 6px 10px", flexWrap: "wrap" }}>
            <StateBadge state="act" word="Not ready" />
            <span style={{ fontSize: "12px", color: ev.ink3 }}>blocking condition</span>
            <Box component="span" sx={{ ml: "auto", fontFamily: ESTATE_MONO, fontWeight: 700, fontSize: "19px" }}>
              {board.notReady.length}
            </Box>
          </Box>
          {board.notReady.length === 0 ? (
            <Box sx={{ p: "8px 6px", display: "flex", justifyContent: "space-between", alignItems: "center", gap: 1 }}>
              <span style={{ fontSize: "12.5px", color: ev.ink3 }}>0 units blocking</span>
              <EvidenceStamp at={board.newestEvaluatedAt} now={now} missingLabel="not evaluated" />
            </Box>
          ) : (
            board.notReady.map((u) => (
              <BoardUnitCard key={u.key} unit={u} highlighted={focusedRef === u.clusterRef} now={now} />
            ))
          )}
        </Box>

        {/* Column 2: UNKNOWN */}
        <Box
          data-board-col="unknown"
          sx={{ bgcolor: ev.surface2, borderRadius: "12px", p: "10px", border: `1px solid ${ev.line}` }}
        >
          <Box sx={{ display: "flex", alignItems: "center", gap: "8px", p: "4px 6px 10px", flexWrap: "wrap" }}>
            <StateBadge state="unk" word="UNKNOWN" />
            <span style={{ fontSize: "12px", color: ev.ink3 }}>not Ready</span>
            <Box component="span" sx={{ ml: "auto", fontFamily: ESTATE_MONO, fontWeight: 700, fontSize: "19px", color: ev.unk }}>
              {board.unknown.length}
            </Box>
          </Box>
          {board.unknown.map((u) => (
            <BoardUnitCard key={u.key} unit={u} highlighted={focusedRef === u.clusterRef} now={now} />
          ))}
          <Box
            sx={{
              fontSize: "12px",
              color: ev.ink2,
              borderLeft: `3px solid ${ev.unk}`,
              pl: "9px",
              py: "2px",
              m: "4px 6px",
            }}
          >
            UNKNOWN is never folded into Ready or Not ready. A one-sided peer claim is not corroboration.
          </Box>
        </Box>

        {/* Column 3: Ready, old evidence */}
        <Box
          data-board-col="ready_stale"
          sx={{ bgcolor: ev.surface2, borderRadius: "12px", p: "10px", border: `1px solid ${ev.line}` }}
        >
          <Box sx={{ display: "flex", alignItems: "center", gap: "8px", p: "4px 6px 10px", flexWrap: "wrap" }}>
            <StateBadge state="watch" word="Ready, > 24 h" />
            <Box component="span" sx={{ ml: "auto", fontFamily: ESTATE_MONO, fontWeight: 700, fontSize: "19px" }}>
              {board.readyStale.length}
            </Box>
          </Box>
          {board.readyStale.length === 0 ? (
            <Box sx={{ p: "8px 6px", display: "flex", justifyContent: "space-between", alignItems: "center", gap: 1 }}>
              <span style={{ fontSize: "12.5px", color: ev.ink3 }}>0 stale Ready units</span>
              <EvidenceStamp at={board.newestEvaluatedAt} now={now} missingLabel="not evaluated" />
            </Box>
          ) : (
            board.readyStale.map((u) => (
              <BoardUnitCard key={u.key} unit={u} highlighted={focusedRef === u.clusterRef} now={now} />
            ))
          )}
        </Box>

        {/* Column 4: Ready (< 24 h) */}
        <Box
          data-board-col="ready_fresh"
          sx={{ bgcolor: ev.surface2, borderRadius: "12px", p: "10px", border: `1px solid ${ev.line}` }}
        >
          <Box sx={{ display: "flex", alignItems: "center", gap: "8px", p: "4px 6px 10px", flexWrap: "wrap" }}>
            <StateBadge state="ok" word="Ready" />
            <span style={{ fontSize: "12px", color: ev.ink3 }}>checked &lt; 24 h</span>
            <Box component="span" sx={{ ml: "auto", fontFamily: ESTATE_MONO, fontWeight: 700, fontSize: "19px" }}>
              {board.readyFresh.length}
            </Box>
          </Box>
          {board.readyFresh.length === 0 ? (
            <Box sx={{ p: "8px 6px", display: "flex", justifyContent: "space-between", alignItems: "center", gap: 1 }}>
              <span style={{ fontSize: "12.5px", color: ev.ink3 }}>No fresh Ready unit</span>
              <EvidenceStamp at={board.newestEvaluatedAt} now={now} missingLabel="not evaluated" />
            </Box>
          ) : (
            <Box sx={{ display: "flex", flexWrap: "wrap", gap: "6px" }}>
              {board.readyFresh.map((u) => (
                <Box
                  key={u.key}
                  component="a"
                  href={`#mx-${encodeURIComponent(u.key)}`}
                  data-failover-card={u.unitId}
                  sx={{
                    display: "inline-flex",
                    alignItems: "center",
                    gap: "6px",
                    bgcolor: ev.surface,
                    border: `1px solid ${focusedRef === u.clusterRef ? ev.accent : ev.line}`,
                    borderRadius: "8px",
                    px: "8px",
                    py: "5px",
                    color: ev.ink,
                    textDecoration: "none",
                    "&:hover": { borderColor: ev.lineStrong, textDecoration: "none" },
                  }}
                >
                  {u.estateCluster && u.unitKind !== "VIRTUAL_SYSTEM" ? (
                    <HaPairTiles cluster={u.estateCluster} lens="failover" size={14} now={now} />
                  ) : (
                    <TileSwatch state="ok" size={14} label={`${u.label}: Ready`} />
                  )}
                  <span style={{ fontFamily: ESTATE_MONO, fontWeight: 600, fontSize: "12px" }}>{u.clusterTitle}</span>
                  <UnitKindTag kind={u.unitKind} vsName={u.virtualSystemName} />
                  <EvidenceStamp at={u.observedAt} now={now} />
                </Box>
              ))}
            </Box>
          )}
        </Box>
      </Box>

      {/* Incidents (read-only; release lives in the standard HA view) */}
      <EstateFailoverIncidents units={board.units} />

      {/* Pre-check Matrix */}
      <EstateCard sx={{ mt: "20px" }}>
        <Box
          sx={{
            display: "flex",
            flexWrap: "wrap",
            alignItems: "center",
            gap: "12px",
            px: "18px",
            pt: "14px",
            pb: "10px",
            borderBottom: `1px solid ${ev.line}`,
          }}
        >
          <Box>
            <Typography component="h2" sx={{ fontSize: "17px", fontWeight: 650 }}>
              Pre-check matrix
            </Typography>
            <Typography sx={{ fontSize: "12.5px", color: ev.ink3 }}>
              HA pairs, VSX chassis, and Virtual System units; one column per pre-check, with last evaluation time
            </Typography>
          </Box>

          <Box sx={{ display: "flex", gap: "6px", flexWrap: "wrap", alignItems: "center" }}>
            <Box
              component="button"
              type="button"
              aria-pressed={matrixFilter === "all"}
              onClick={() => setMatrixFilter("all")}
              sx={{
                height: "27px",
                px: "10px",
                borderRadius: "999px",
                border: `1px solid ${matrixFilter === "all" ? ev.ink : ev.line}`,
                bgcolor: ev.surface,
                color: matrixFilter === "all" ? ev.ink : ev.ink2,
                fontWeight: matrixFilter === "all" ? 650 : 500,
                fontSize: "12px",
                boxShadow: matrixFilter === "all" ? `inset 0 0 0 1px ${ev.ink}` : "none",
                cursor: "pointer",
                fontFamily: ESTATE_SANS,
              }}
            >
              All failover units ({board.units.length})
            </Box>
            <Box
              component="button"
              type="button"
              aria-pressed={matrixFilter === "attention"}
              onClick={() => setMatrixFilter("attention")}
              sx={{
                height: "27px",
                px: "10px",
                borderRadius: "999px",
                border: `1px solid ${matrixFilter === "attention" ? ev.ink : ev.line}`,
                bgcolor: ev.surface,
                color: matrixFilter === "attention" ? ev.ink : ev.ink2,
                fontWeight: matrixFilter === "attention" ? 650 : 500,
                fontSize: "12px",
                boxShadow: matrixFilter === "attention" ? `inset 0 0 0 1px ${ev.ink}` : "none",
                cursor: "pointer",
                fontFamily: ESTATE_SANS,
              }}
            >
              Not plainly Ready ({attentionUnitCount})
            </Box>
            {distinctVendors.length > 1 ? (
              <Box
                component="select"
                aria-label="Filter matrix by vendor"
                value={vendorFilter}
                onChange={(e) => setVendorFilter(e.target.value)}
                sx={{
                  height: "27px",
                  border: `1px solid ${ev.line}`,
                  borderRadius: "8px",
                  bgcolor: ev.bg,
                  color: ev.ink,
                  px: "8px",
                  fontSize: "12px",
                  fontFamily: ESTATE_SANS,
                }}
              >
                <option value="">All vendors</option>
                {distinctVendors.map(([v, label]) => (
                  <option key={v} value={v}>
                    {label}
                  </option>
                ))}
              </Box>
            ) : null}
          </Box>

          <Box
            sx={{
              ml: { xs: 0, xl: "auto" },
              display: "flex",
              gap: "12px",
              flexWrap: "wrap",
              alignItems: "center",
              fontSize: "12px",
              color: ev.ink2,
            }}
          >
            {(
              [
                ["ok", "pass"],
                ["act", "blocks (!)"],
                ["watch", "review (~)"],
                ["unk", "UNKNOWN (?)"],
                ["na", "not applicable"],
              ] as const
            ).map(([st, label]) => (
              <Box key={st} component="span" sx={{ display: "inline-flex", alignItems: "center", gap: "6px" }}>
                <TileSwatch state={st} size={15} label={label} />
                <span>{label}</span>
              </Box>
            ))}
          </Box>
        </Box>

        <Box sx={{ overflowX: "auto" }}>
          <Box
            component="table"
            aria-label="Failover pre-check matrix"
            sx={{
              width: "100%",
              borderCollapse: "collapse",
              fontSize: "13px",
              "& th": {
                textAlign: "left",
                fontSize: "11px",
                letterSpacing: "0.06em",
                textTransform: "uppercase",
                color: ev.ink3,
                fontWeight: 650,
                p: "8px 10px",
                borderBottom: `1px solid ${ev.line}`,
                bgcolor: ev.surface,
              },
              "& td": {
                p: "9px 10px",
                borderBottom: `1px solid ${ev.line}`,
                verticalAlign: "middle",
              },
              "& tr:last-of-type td": { borderBottom: 0 },
              "& tbody tr:hover td": { bgcolor: ev.bg },
            }}
          >
            <thead>
              <tr>
                <th>Cluster / VS unit</th>
                <th>Status</th>
                <th>Members (active ↔ standby)</th>
                {board.checkColumns.map((col, colIdx) => (
                  <th
                    key={col.key}
                    id={checkHeaderId(colIdx)}
                    style={{
                      textAlign: "center",
                      minWidth: "74px",
                      maxWidth: "120px",
                      whiteSpace: "normal",
                      lineHeight: 1.25,
                    }}
                  >
                    {col.title}
                  </th>
                ))}
                <th>Last evaluated</th>
                <th>Standard HA</th>
              </tr>
            </thead>
            <tbody>
              {matrixUnits.length === 0 ? (
                <tr>
                  <td colSpan={board.checkColumns.length + 5} style={{ color: ev.ink3 }}>
                    No failover units match the selected filter.
                  </td>
                </tr>
              ) : (
                matrixUnits.map((u) => {
                  const rowCells = board.cellsByUnit.get(u.key);
                  return (
                    <tr
                      key={u.key}
                      id={`mx-${encodeURIComponent(u.key)}`}
                      data-matrix-unit={u.unitId}
                      data-matrix-kind={u.unitKind}
                    >
                      <td style={{ paddingLeft: u.unitKind === "VIRTUAL_SYSTEM" ? "26px" : "10px" }}>
                        <Box sx={{ display: "flex", alignItems: "center", gap: "6px", flexWrap: "wrap" }}>
                          {u.unitKind === "VIRTUAL_SYSTEM" ? (
                            <span style={{ color: ev.ink3, fontFamily: ESTATE_MONO }}>↳</span>
                          ) : null}
                          <b style={{ fontFamily: ESTATE_MONO, fontSize: "12.5px" }}>{u.clusterTitle}</b>
                          <UnitKindTag kind={u.unitKind} vsName={u.virtualSystemName} />
                        </Box>
                        <Typography sx={{ fontSize: "11.5px", color: ev.ink3 }}>{u.vendorName}</Typography>
                      </td>
                      <td>
                        <StateBadge state={u.badgeState} word={u.badgeWord} dense />
                      </td>
                      <td>
                        <MemberLinksLine
                          activeMembers={u.activeMembers}
                          standbyMembers={u.standbyMembers}
                          allMembers={u.allMembers}
                          mode={u.mode}
                        />
                      </td>
                      {board.checkColumns.map((col, colIdx) => {
                        const cell = rowCells?.get(col.key) ?? {
                          state: "unk" as const,
                          word: "UNKNOWN",
                          summary: "Not evaluated",
                        };
                        const tipText = `${u.label} · ${col.title}: ${cell.word} — ${cell.summary}`;
                        const openDetail = () => setOpenCell({ unit: u, col, cell });
                        const onCellKeyDown = (e: KeyboardEvent<HTMLSpanElement>) => {
                          if (e.key === "Enter" || e.key === " ") {
                            e.preventDefault();
                            openDetail();
                          }
                        };
                        return (
                          <td
                            key={col.key}
                            data-check-col={col.key}
                            data-check-state={cell.state}
                            style={{ textAlign: "center", padding: "6px 6px" }}
                          >
                            <Tooltip title={tipText} describeChild>
                              <Box
                                component="span"
                                role="button"
                                tabIndex={0}
                                aria-label={`${u.label}: ${cell.word}`}
                                aria-describedby={checkHeaderId(colIdx)}
                                onClick={openDetail}
                                onKeyDown={onCellKeyDown}
                                sx={{ display: "inline-flex", cursor: "pointer", borderRadius: "3px", "&:focus-visible": { outline: `2px solid ${ev.accent}`, outlineOffset: "2px" } }}
                              >
                                <TileSwatch state={cell.state} size={20} label={tipText} />
                              </Box>
                            </Tooltip>
                          </td>
                        );
                      })}
                      <td>
                        <Box sx={{ display: "flex", flexDirection: "column", gap: "2px" }}>
                          {u.observedAt ? (
                            <Typography sx={{ fontFamily: ESTATE_MONO, fontSize: "11.5px", color: ev.ink2 }}>
                              {formatStamp(u.observedAt)}
                            </Typography>
                          ) : null}
                          <EvidenceStamp
                            at={u.observedAt}
                            now={now}
                            missingLabel="not evaluated"
                          />
                        </Box>
                      </td>
                      <td>
                        <Box
                          component="a"
                          href={standardHaHref(u.clusterRef)}
                          sx={{
                            fontSize: "12.5px",
                            fontWeight: 600,
                            color: ev.accent,
                            whiteSpace: "nowrap",
                            textDecoration: "none",
                            "&:hover": { textDecoration: "underline" },
                          }}
                        >
                          Open in HA & readiness →
                        </Box>
                      </td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </Box>
        </Box>
      </EstateCard>

      {/* Unsupported Clusters Disclosure */}
      {board.unsupported.length > 0 ? (
        <EstateCard sx={{ mt: "16px", p: "14px 18px" }}>
          <Box sx={{ display: "flex", alignItems: "center", gap: "10px", mb: "8px", flexWrap: "wrap" }}>
            <StateBadge state="na" word="Not supported" dense />
            <Typography component="h2" sx={{ fontSize: "15px", fontWeight: 650 }}>
              {board.unsupported.length} enrolled {board.unsupported.length === 1 ? "pair" : "pairs"} without readiness pre-checks
            </Typography>
          </Box>
          <Box component="ul" sx={{ listStyle: "none", m: 0, p: 0, display: "flex", flexDirection: "column", gap: "6px" }}>
            {board.unsupported.map((cl) => (
              <Box
                component="li"
                key={cl.clusterRef}
                sx={{ display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap", fontSize: "12.5px" }}
              >
                <span style={{ fontFamily: ESTATE_MONO, fontWeight: 650 }}>{cl.title}</span>
                <VendorTag label={cl.vendorName} />
                <span style={{ color: ev.ink3 }}>{cl.note}</span>
                <Box
                  component="a"
                  href={standardHaHref(cl.clusterRef)}
                  sx={{ ml: "auto", color: ev.accent, textDecoration: "none", "&:hover": { textDecoration: "underline" } }}
                >
                  Standard HA →
                </Box>
              </Box>
            ))}
          </Box>
        </EstateCard>
      ) : null}

      <EstateFailoverCellDrawer
        target={openCell ? toCellTarget(openCell) : null}
        onClose={() => setOpenCell(null)}
      />
    </Box>
  );
}
