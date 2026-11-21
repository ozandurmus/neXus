import { useEffect, useMemo, useState } from "react";
import Box from "@mui/material/Box";
import { getEstateAsOf, type EstateAsOfView } from "../auth/adminApi";
import { formatUtc } from "../shell/time";
import { EstateCard, EvidenceStamp, StateBadge, TileSwatch } from "./EstatePrimitives";
import {
  REWIND_LENSES,
  REWIND_LENS_LABEL,
  REWIND_WINDOW_DAYS,
  buildRewindSnapshot,
  countLens,
  diffSnapshots,
  nearestSnap,
  snapPoints,
  type RewindLens,
  type RewindSnapshot,
} from "./estateRewindModel";
import { dossierHref, rewindHref } from "./estateRouting";
import { ESTATE_MONO, ev } from "./estateTokens";

type Load = { readonly state: "loading" } | { readonly state: "failed"; readonly message: string } | { readonly state: "ok"; readonly view: EstateAsOfView; readonly snap: RewindSnapshot };

function useAsOf(at: string | null): Load {
  const [load, setLoad] = useState<Load>({ state: "loading" });
  useEffect(() => {
    if (!at) return;
    let active = true;
    setLoad({ state: "loading" });
    getEstateAsOf(at).then(
      (view) => { if (active) setLoad({ state: "ok", view, snap: buildRewindSnapshot(view) }); },
      (e: { message?: string }) => { if (active) setLoad({ state: "failed", message: e?.message ?? "Rewind read failed" }); },
    );
    return () => { active = false; };
  }, [at]);
  return load;
}

const chipSx = (on: boolean) => ({
  border: `1px solid ${on ? ev.ink : ev.line}`,
  bgcolor: on ? ev.surface2 : ev.surface,
  color: ev.ink,
  borderRadius: "999px",
  px: "12px",
  py: "4px",
  fontSize: "12.5px",
  cursor: "pointer",
  fontFamily: "inherit",
});

function MomentBox({ k, at, note }: { readonly k: string; readonly at: string; readonly note: string }) {
  return (
    <Box sx={{ bgcolor: ev.surface2, borderRadius: "10px", p: "10px 14px" }}>
      <Box sx={{ fontSize: 11, letterSpacing: ".08em", textTransform: "uppercase", fontWeight: 700, color: ev.ink3 }}>{k}</Box>
      <Box sx={{ fontFamily: ESTATE_MONO, fontWeight: 650, fontSize: 17 }}>{formatUtc(at, false)}</Box>
      <Box sx={{ fontSize: 12, color: ev.ink3 }}>{note}</Box>
    </Box>
  );
}

export function EstateRewindScreen({
  initialAt,
  initialCompare,
}: {
  readonly initialAt: string | null;
  readonly initialCompare: string | null;
}) {
  const [liveAt] = useState(() => new Date());
  const live = useAsOf(useMemo(() => liveAt.toISOString(), [liveAt]));
  const points = useMemo(() => snapPoints(live.state === "ok" ? live.view : null, liveAt), [live, liveAt]);

  const validAt = (v: string | null) => (v && Number.isFinite(Date.parse(v)) ? new Date(v).toISOString() : null);
  const [atA, setAtA] = useState<string | null>(() => validAt(initialAt));
  const [atB, setAtB] = useState<string | null>(() => validAt(initialCompare));
  const [compare, setCompare] = useState<boolean>(() => Boolean(initialCompare));
  const [lens, setLens] = useState<RewindLens>("failover");

  const momentA = atA ?? (points.length > 0 ? points[Math.max(0, points.length - 2)] : null);
  const momentB = compare ? (atB ?? liveAt.toISOString()) : null;
  const loadA = useAsOf(momentA);
  const loadB = useAsOf(momentB);

  useEffect(() => {
    if (momentA) window.history.replaceState(null, "", rewindHref(momentA, compare ? momentB : null));
  }, [momentA, momentB, compare]);

  const floorMs = liveAt.getTime() - REWIND_WINDOW_DAYS * 86_400_000;
  const idxA = momentA ? nearestSnap(points, Date.parse(momentA)) : -1;
  const chips: ReadonlyArray<{ label: string; days: number }> = [
    { label: "24 h ago", days: 1 },
    { label: "7 days ago", days: 7 },
    { label: "14 days ago", days: 14 },
    { label: "30 days ago", days: 30 },
  ];
  const jump = (days: number) => {
    const i = nearestSnap(points, liveAt.getTime() - days * 86_400_000);
    if (i >= 0) setAtA(points[i]);
    else setAtA(new Date(Math.max(floorMs, liveAt.getTime() - days * 86_400_000)).toISOString());
  };

  const diff = useMemo(
    () => (compare && loadA.state === "ok" && loadB.state === "ok" ? diffSnapshots(loadA.snap, loadB.snap) : null),
    [compare, loadA, loadB],
  );
  const shown = diff ? diff.filter((t) => t.lens === lens) : null;
  const changedIds = new Set(shown?.map((t) => t.unit.id) ?? []);
  const asOfNow = (iso: string) => new Date(iso);

  return (
    <Box data-testid="estate-rewind" sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
      <Box>
        <Box sx={{ fontSize: 11, letterSpacing: ".09em", textTransform: "uppercase", fontWeight: 650, color: ev.ink3 }}>Rewind</Box>
        <Box component="h1" sx={{ m: 0, fontSize: 26, lineHeight: 1.2, fontWeight: 650 }}>
          What was true then{compare ? ", and what moved" : ""}
        </Box>
        <Box sx={{ color: ev.ink2, mt: 0.5, maxWidth: 780 }}>
          Stored evidence only: nothing here is collected again and no device is contacted. Where history does not reach, the answer is UNKNOWN.
          Failover, Backups and Controls are answered exactly; Exposure, Policy installed and Evidence age are not answered by this read.
        </Box>
      </Box>

      <EstateCard sx={{ p: 2 }}>
        <Box sx={{ display: "flex", gap: 1, mb: 1.5, flexWrap: "wrap" }}>
          <Box component="button" type="button" aria-pressed={!compare} onClick={() => setCompare(false)} sx={chipSx(!compare)}>One moment</Box>
          <Box component="button" type="button" aria-pressed={compare} onClick={() => setCompare(true)} sx={chipSx(compare)}>Compare two moments</Box>
        </Box>
        <Box sx={{ display: "grid", gridTemplateColumns: compare ? "1fr auto 1fr" : "1fr", gap: 2, alignItems: "center" }}>
          {momentA ? <MomentBox k={compare ? "Moment A · then" : "As of"} at={momentA} note="snaps to a stored evidence time" /> : <Box>No stored evidence time in the last {REWIND_WINDOW_DAYS} days.</Box>}
          {compare ? <Box sx={{ fontSize: 20, color: ev.ink3 }} aria-hidden>→</Box> : null}
          {compare && momentB ? <MomentBox k="Moment B · now" at={momentB} note={atB ? "pinned" : "live"} /> : null}
        </Box>
        <Box sx={{ display: "flex", gap: 1, flexWrap: "wrap", alignItems: "center", mt: 1.5 }}>
          <Box component="span" sx={{ fontSize: 12.5, color: ev.ink3 }}>Jump {compare ? "A " : ""}to</Box>
          {chips.map((c) => (
            <Box key={c.days} component="button" type="button" onClick={() => jump(c.days)} sx={chipSx(false)}>{c.label}</Box>
          ))}
        </Box>
        <Box sx={{ mt: 1.5 }}>
          <Box
            component="input"
            type="range"
            aria-label={`Moment A on a ${REWIND_WINDOW_DAYS}-day timeline of stored evidence times`}
            data-testid="rewind-slider"
            min={0}
            max={Math.max(0, points.length - 1)}
            step={1}
            value={Math.max(0, idxA)}
            disabled={points.length === 0}
            onChange={(e) => setAtA(points[Number((e.target as HTMLInputElement).value)] ?? null)}
            sx={{ width: "100%" }}
          />
          <Box sx={{ display: "flex", justifyContent: "space-between", fontSize: 11, color: ev.ink3 }}>
            <span>{points.length > 0 ? formatUtc(points[0], false) : `${REWIND_WINDOW_DAYS} days ago`}</span>
            <span>{points.length} stored evidence times</span>
            <span>now</span>
          </Box>
        </Box>
      </EstateCard>

      <Box sx={{ display: "flex", gap: 1 }} role="tablist" aria-label="Rewind lenses">
        {REWIND_LENSES.map((l) => (
          <Box key={l} component="button" type="button" role="tab" aria-selected={lens === l} onClick={() => setLens(l)} sx={chipSx(lens === l)}>
            {REWIND_LENS_LABEL[l]}
          </Box>
        ))}
      </Box>

      {loadA.state === "failed" || loadB.state === "failed" ? (
        <EstateCard sx={{ p: 2 }}><span role="alert">Rewind read failed: stored evidence could not be read for this moment.</span></EstateCard>
      ) : null}
      {loadA.state === "ok" && loadA.snap.warnings.length > 0 ? (
        <EstateCard sx={{ p: 2 }}>
          {loadA.snap.warnings.map((w, i) => (<Box key={i} sx={{ color: ev.watch }}>{w}</Box>))}
        </EstateCard>
      ) : null}

      {loadA.state === "ok" ? (
        <EstateCard sx={{ p: 2 }}>
          <Box component="h2" sx={{ m: 0, fontSize: 17, fontWeight: 650, mb: 1 }}>
            {REWIND_LENS_LABEL[lens]}: {compare ? "only what moved" : `the wall as of ${formatUtc(loadA.snap.asOf, false)}`}
          </Box>
          {(() => {
            const c = countLens(loadA.snap, lens);
            return (
              <Box sx={{ display: "flex", gap: 2, flexWrap: "wrap", alignItems: "center", mb: 1.5 }} data-testid="rewind-counts">
                <StateBadge state="act" word={`${c.act} act`} dense />
                <StateBadge state="watch" word={`${c.watch} review`} dense />
                <StateBadge state="ok" word={`${c.ok} fine`} dense />
                <StateBadge state="unk" word={`${c.unk} UNKNOWN`} dense />
                <StateBadge state="na" word={`${c.na} n/a`} dense />
                <EvidenceStamp at={c.newestAt} now={asOfNow(loadA.snap.asOf)} source="newest evidence" />
                <EvidenceStamp at={c.oldestAt} now={asOfNow(loadA.snap.asOf)} source="oldest evidence" />
              </Box>
            );
          })()}
          <Box sx={{ color: ev.ink3, fontSize: 12.5, mb: 1 }} data-testid="rewind-note">
            Rewind answers three lenses only (Failover, Backups, Controls); Exposure, Policy and Evidence age are not rewound.
            Backups use the live 7-day stale rule; each tile states the archive age at that moment. Unit links open the live dossier.
          </Box>
          <Box sx={{ display: "flex", flexWrap: "wrap", gap: "5px" }} data-testid="rewind-wall">
            {loadA.snap.units
              .filter((u) => !compare || changedIds.has(u.id))
              .map((u) => {
                const a = u.lenses[lens];
                return (
                  <Box
                    key={u.id}
                    component="a"
                    href={dossierHref(u.id)}
                    title="Opens the live dossier (not the rewound moment)"
                    sx={{ textDecoration: "none", display: "inline-flex" }}
                  >
                    <TileSwatch state={a.state} size={22} label={`${u.name}: ${a.word}${a.detail ? `, ${a.detail}` : ""}; evidence ${a.evidenceAt ? formatUtc(a.evidenceAt, false) : "UNKNOWN"}`} />
                  </Box>
                );
              })}
            {compare && diff && changedIds.size === 0 ? <Box sx={{ color: ev.ink2 }}>Nothing moved on this lens between the two moments.</Box> : null}
          </Box>
        </EstateCard>
      ) : (
        <EstateCard sx={{ p: 2 }}>{loadA.state === "loading" ? "Reading stored evidence…" : null}</EstateCard>
      )}

      {compare && shown ? (
        <EstateCard sx={{ p: 2 }}>
          <Box component="h2" sx={{ m: 0, fontSize: 17, fontWeight: 650, mb: 1 }}>Every transition</Box>
          <Box component="table" data-testid="rewind-transitions" sx={{ width: "100%", borderCollapse: "collapse", fontSize: 13 }}>
            <thead>
              <tr><th align="left">Unit</th><th align="left">Then</th><th align="left">Now</th><th align="left">Direction</th><th align="left">Evidence then</th><th align="left">Evidence now</th></tr>
            </thead>
            <tbody>
              {shown.map((t) => (
                <tr key={`${t.unit.id}:${t.lens}`}>
                  <td><Box component="a" href={dossierHref(t.unit.id)} sx={{ color: ev.accent, textDecoration: "none" }}>{t.unit.name}</Box></td>
                  <td><StateBadge state={t.then.state} word={t.then.word} dense /></td>
                  <td><StateBadge state={t.now.state} word={t.now.word} dense /></td>
                  <td>{t.direction === "worse" ? "got worse" : t.direction === "better" ? "got better" : "changed"}</td>
                  <td>{loadA.state === "ok" ? <EvidenceStamp at={t.then.evidenceAt} now={asOfNow(loadA.snap.asOf)} /> : null}</td>
                  <td>{loadB.state === "ok" ? <EvidenceStamp at={t.now.evidenceAt} now={asOfNow(loadB.snap.asOf)} /> : null}</td>
                </tr>
              ))}
            </tbody>
          </Box>
          <Box sx={{ color: ev.ink3, fontSize: 12.5, mt: 1 }}>The cause of a transition is not inferred; each side is the newest stored read at or before its moment.</Box>
        </EstateCard>
      ) : null}
    </Box>
  );
}
