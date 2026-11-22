import { useEffect, useState } from "react";
import Box from "@mui/material/Box";
import {
  listCutoverSegments,
  listPacketCases,
  listSupportBundles,
  type PacketCase,
  type SupportBundleRequest,
} from "../auth/adminApi";
import { EstateCard, MoreFooter, StateBadge } from "./EstatePrimitives";
import { CASE_ACTION_HREFS, buildCasesModel, type CaseRow, type CasesModel } from "./estateCasesModel";
import {
  CUTOVER_STANDARD_HREF,
  buildCutoverSegmentsModel,
  buildFailedCutoverSegmentsModel,
  type CutoverSegmentsModel,
} from "./estateCutoverSegmentsModel";
import { ESTATE_MONO, ev } from "./estateTokens";

type Load =
  | { readonly state: "loading" }
  | { readonly state: "failed"; readonly message: string }
  | { readonly state: "ok"; readonly model: CasesModel };

type CutoverRead =
  | { readonly ok: true; readonly readAt: Date; readonly segments: Parameters<typeof buildCutoverSegmentsModel>[0] }
  | { readonly ok: false; readonly readAt: Date };

/** Reads the cutover segment list once; a failed read is UNKNOWN with its own attempt time, never an empty list. */
function useCutoverRead(): CutoverRead | null {
  const [read, setRead] = useState<CutoverRead | null>(null);
  useEffect(() => {
    let active = true;
    listCutoverSegments().then(
      (res) => {
        // A response without a segment array is not an empty list: it is a failed read.
        if (!active) return;
        setRead(Array.isArray(res?.segments)
          ? { ok: true, readAt: new Date(), segments: res.segments }
          : { ok: false, readAt: new Date() });
      },
      () => {
        if (active) setRead({ ok: false, readAt: new Date() });
      },
    );
    return () => {
      active = false;
    };
  }, []);
  return read;
}

/** Age stamps are computed at render from the read's own time, so they keep moving while the page stays open. */
function cutoverModelOf(read: CutoverRead, now: Date): CutoverSegmentsModel {
  return read.ok
    ? buildCutoverSegmentsModel(read.segments, read.readAt, now)
    : buildFailedCutoverSegmentsModel(read.readAt, now);
}

function CutoverSegmentsSection({ read }: { readonly read: CutoverRead | null }) {
  return (
    <Box data-testid="cutover-segments-section" sx={{ display: "flex", flexDirection: "column", gap: 1.5, mt: 1 }}>
      <Box component="h2" sx={{ m: 0, fontSize: 17, fontWeight: 650 }}>Cutover segments</Box>
      <Box sx={{ color: ev.ink2, fontSize: 13, maxWidth: 780 }}>
        Read-only list of the segments the standard Cutover screen holds. Counts are the list response&apos;s own fields;
        a field it does not carry reads &quot;not reported&quot;.
      </Box>
      <Box sx={{ display: "flex", gap: 1, flexWrap: "wrap" }}>
        <Box component="a" href={CUTOVER_STANDARD_HREF} sx={linkSx}>Open cutover (standard view)</Box>
      </Box>
      {read === null ? <Box sx={{ color: ev.ink3 }}>Reading cutover segments…</Box> : null}
      {read !== null ? (() => {
        const model = cutoverModelOf(read, new Date());
        if (model.state === "failed") {
          return (
            <EstateCard sx={{ p: 2 }}>
              <StateBadge state="unk" word="UNKNOWN" />
              <Box data-testid="cutover-segments-failed" sx={{ mt: 1 }}>{model.text} · {model.stamp}</Box>
            </EstateCard>
          );
        }
        if (model.total === 0) return <Box sx={{ color: ev.ink3 }}>No cutover segments are stored.</Box>;
        return (
          <>
            <Box data-testid="cutover-segments-counts" sx={{ fontSize: 13, color: ev.ink2 }}>
              {model.total} segment{model.total === 1 ? "" : "s"} listed · {model.stamp}
            </Box>
            {model.rows.map((row) => (
              <EstateCard key={row.segmentId} sx={{ p: 2 }}>
                <Box data-testid="cutover-segment-row" data-segment={row.segmentId} sx={{ display: "flex", gap: 2, flexWrap: "wrap", alignItems: "baseline" }}>
                  <Box sx={{ fontWeight: 650 }}>{row.name}</Box>
                  {row.fields.map((f) => (
                    <Box key={f.label} sx={{ fontSize: 12.5, color: ev.ink2 }}>
                      {f.label}: {f.value}
                      {f.value === "not reported" ? null : <Box component="span" sx={{ color: ev.ink3 }}> ({f.stamp})</Box>}
                    </Box>
                  ))}
                </Box>
              </EstateCard>
            ))}
            {model.rowsMore > 0 ? (
              <Box sx={{ fontSize: 13, color: ev.ink3 }}>
                <MoreFooter shown={model.rows.length} total={model.total} noun="segments" testId="cutover-segments-more" />
                <Box sx={{ display: "block" }}>in the standard view</Box>
              </Box>
            ) : null}
          </>
        );
      })() : null}
    </Box>
  );
}

/** Reads the packet case list and the bundle list; a failed bundle read makes the bundle link UNKNOWN, not empty. */
function useCases(): Load {
  const [load, setLoad] = useState<Load>({ state: "loading" });
  useEffect(() => {
    let active = true;
    Promise.allSettled([listPacketCases(), listSupportBundles()]).then(([casesRes, bundlesRes]) => {
      if (!active) return;
      if (casesRes.status === "rejected") {
        setLoad({ state: "failed", message: casesRes.reason?.message ?? "Cases could not be read." });
        return;
      }
      const cases: readonly PacketCase[] = casesRes.value.cases;
      const bundles: readonly SupportBundleRequest[] | null =
        bundlesRes.status === "fulfilled" ? bundlesRes.value.support_bundles : null;
      setLoad({ state: "ok", model: buildCasesModel(cases, bundles, new Date()) });
    });
    return () => {
      active = false;
    };
  }, []);
  return load;
}

const linkSx = {
  display: "inline-flex",
  alignItems: "center",
  border: `1px solid ${ev.line}`,
  bgcolor: ev.surface,
  color: ev.accent,
  borderRadius: "999px",
  px: "12px",
  py: "4px",
  fontSize: "12.5px",
  textDecoration: "none",
  fontFamily: "inherit",
} as const;

function caseBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return "UNKNOWN";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function CaseCard({ row, bundlesKnown }: { readonly row: CaseRow; readonly bundlesKnown: boolean }) {
  const bundleLine = (text: string) => <Box sx={{ fontSize: 12.5, color: ev.ink2 }}>{text}</Box>;
  return (
    <Box data-testid="case-row" data-case={row.caseId}>
    <EstateCard sx={{ p: 2 }}>
      <Box sx={{ display: "flex", gap: 1.5, alignItems: "baseline", flexWrap: "wrap" }}>
        <Box component="h2" sx={{ m: 0, fontSize: 17, fontWeight: 650 }}>{row.title}</Box>
        <StateBadge state={row.view.state} word={row.view.word} dense />
        <Box sx={{ fontFamily: ESTATE_MONO, fontSize: 11.5, color: ev.ink3 }}>{row.caseId}</Box>
      </Box>
      <Box sx={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(230px, 1fr))", gap: 1.5, mt: 1.5 }}>
        <Box>
          <Box sx={{ fontSize: 11, textTransform: "uppercase", letterSpacing: ".08em", fontWeight: 700, color: ev.ink3 }}>Captures</Box>
          {bundleLine(`${row.captureCount} capture${row.captureCount === 1 ? "" : "s"} · ${caseBytes(row.totalBytes)}`)}
          <Box sx={{ fontSize: 12, color: ev.ink3 }}>Created {row.createdAge}</Box>
        </Box>
        <Box>
          <Box sx={{ fontSize: 11, textTransform: "uppercase", letterSpacing: ".08em", fontWeight: 700, color: ev.ink3 }}>Sanitiser</Box>
          <StateBadge state="unk" word="UNKNOWN" dense />
          <Box sx={{ fontSize: 12, color: ev.ink3 }}>Runs are not recorded per case</Box>
        </Box>
        <Box>
          <Box sx={{ fontSize: 11, textTransform: "uppercase", letterSpacing: ".08em", fontWeight: 700, color: ev.ink3 }}>Diagnostics</Box>
          <StateBadge state="unk" word="UNKNOWN" dense />
          <Box sx={{ fontSize: 12, color: ev.ink3 }}>Runs are not recorded per case</Box>
        </Box>
        <Box>
          <Box sx={{ fontSize: 11, textTransform: "uppercase", letterSpacing: ".08em", fontWeight: 700, color: ev.ink3 }}>Support bundles</Box>
          {!bundlesKnown ? <StateBadge state="unk" word="UNKNOWN" dense /> : bundleLine(`${row.bundleCount} name this case`)}
          {bundlesKnown ? row.bundles.map((b) => (
            <Box key={b.id} sx={{ fontSize: 12, color: ev.ink2 }}>
              {b.vendor} · {b.stateWord} · requested {b.requestedAge}
            </Box>
          )) : null}
          {bundlesKnown && row.bundlesMore > 0 ? <Box sx={{ fontSize: 12, color: ev.ink3 }}>+{row.bundlesMore} more</Box> : null}
        </Box>
      </Box>
      <Box sx={{ display: "flex", gap: 1, flexWrap: "wrap", mt: 1.5 }}>
        <Box component="a" href={CASE_ACTION_HREFS.newCapture} sx={linkSx}>New capture (standard view)</Box>
        <Box component="a" href={CASE_ACTION_HREFS.diagnostics} sx={linkSx}>Run diagnostics (standard view)</Box>
        <Box component="a" href={CASE_ACTION_HREFS.bundle} sx={linkSx}>Create bundle (standard view)</Box>
        <Box component="a" href={CASE_ACTION_HREFS.sanitiser} sx={linkSx}>Sanitise a file (standard view)</Box>
      </Box>
    </EstateCard>
    </Box>
  );
}

export function EstateCasesScreen() {
  const load = useCases();
  const cutoverRead = useCutoverRead();
  return (
    <Box data-testid="estate-cases" sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
      <Box>
        <Box sx={{ fontSize: 11, letterSpacing: ".09em", textTransform: "uppercase", fontWeight: 650, color: ev.ink3 }}>Cases</Box>
        <Box component="h1" sx={{ m: 0, fontSize: 26, lineHeight: 1.2, fontWeight: 650 }}>
          Captures, sanitiser, diagnostics and bundles per case
        </Box>
        <Box sx={{ color: ev.ink2, mt: 0.5, maxWidth: 780 }}>
          Read-only, newest case first. Every action opens the standard view. Sanitiser and diagnostics runs are not recorded
          against a case, so they read UNKNOWN here rather than OK.
        </Box>
      </Box>

      {load.state === "loading" ? <Box sx={{ color: ev.ink3 }}>Reading cases…</Box> : null}
      {load.state === "failed" ? (
        <EstateCard sx={{ p: 2 }}>
          <StateBadge state="unk" word="UNKNOWN" />
          <Box sx={{ mt: 1 }}>Cases could not be read: {load.message}</Box>
        </EstateCard>
      ) : null}

      {load.state === "ok" ? (
        <>
          <Box data-testid="cases-counts" sx={{ fontSize: 13, color: ev.ink2 }}>
            {load.model.caseCount} case{load.model.caseCount === 1 ? "" : "s"} listed ·{" "}
            {load.model.bundlesKnown
              ? `${load.model.bundleTotal} support bundle${load.model.bundleTotal === 1 ? "" : "s"} read, ${load.model.bundlesUnlinked} naming no listed case`
              : "support bundles could not be read, so their link to each case is UNKNOWN"}
            {load.model.bundlesKnown && load.model.bundlesReferenceHidden > 0
              ? ` · ${load.model.bundlesReferenceHidden} with the case reference hidden for this role`
              : ""}
          </Box>
          <Box sx={{ display: "flex", gap: 1, flexWrap: "wrap" }}>
            <Box component="a" href={CASE_ACTION_HREFS.newCapture} sx={linkSx}>New case and capture (standard view)</Box>
          </Box>
          {load.model.rows.length === 0 ? (
            <Box sx={{ color: ev.ink3 }}>No packet analysis cases yet.</Box>
          ) : (
            load.model.rows.map((row) => <CaseCard key={row.caseId} row={row} bundlesKnown={load.model.bundlesKnown} />)
          )}
          {load.model.rowsMore > 0 ? (
            <Box sx={{ fontSize: 13, color: ev.ink3 }}>
              <MoreFooter shown={load.model.rows.length} total={load.model.caseCount} noun="cases" />
              <Box sx={{ display: "block" }}>in the standard view</Box>
            </Box>
          ) : null}
        </>
      ) : null}

      <CutoverSegmentsSection read={cutoverRead} />
    </Box>
  );
}
