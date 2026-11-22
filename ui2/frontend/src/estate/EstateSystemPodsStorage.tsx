import Box from "@mui/material/Box";
import type { PodStatusView, PodsView, StorageView } from "../auth/adminApi";
import { formatBytes } from "../screens/SystemStatusPanel";
import { EstateCard, EvidenceStamp, MoreFooter, StateBadge } from "./EstatePrimitives";
import { systemHref } from "./estateRouting";
import { podStateWord, POD_STATE_WORDS, type TimedRead } from "./estateSystemModel";
import { ESTATE_MONO, ev } from "./estateTokens";
import type { BadgeState } from "./estateModel";

/** Pod rows shown before "+N more pods" (the same cap the standard Platform status uses for its list). */
export const PODS_ROW_CAP = 50;

/** The standard System view; `standard=1` keeps it out of the Estate shell. */
export const PODS_STORAGE_STANDARD_HREF = `${systemHref()}&standard=1`;

const SOURCE = "pods and storage";
const RESTRICTED_SENTENCE = "Your role cannot read pods and storage.";

type BadgeFor = (state: string) => BadgeState;

/** Badge of one pod state. Running is the only OK; an unknown state is UNKNOWN; a failing state is ACT. */
export const podBadgeOf: BadgeFor = (state) => {
  switch (state) {
    case "Running": return "ok";
    case "Failed":
    case "CrashLoopBackOff": return "act";
    case "Pending":
    case "Waiting":
    case "Terminating": return "watch";
    case "Succeeded":
    case "Completed": return "na";
    default: return "unk";
  }
};

/** A byte figure, or UNKNOWN when the server did not send it. */
export function figureOrUnknown(value: number | null | undefined): string {
  return typeof value === "number" && Number.isFinite(value) ? formatBytes(value) : "UNKNOWN";
}

/** The count of each pod state word, in the listed order, plus UNKNOWN for any other state. */
export function countPodStates(pods: readonly PodStatusView[]): ReadonlyArray<readonly [string, number]> {
  const counts = new Map<string, number>();
  for (const p of pods) {
    const word = podStateWord(p.state);
    counts.set(word, (counts.get(word) ?? 0) + 1);
  }
  const ordered = [...POD_STATE_WORDS, "UNKNOWN"].filter((w) => (counts.get(w) ?? 0) > 0);
  return ordered.map((w) => [w, counts.get(w) ?? 0] as const);
}

function restartsLabel(restarts: unknown): string {
  return typeof restarts === "number" && Number.isFinite(restarts) ? String(restarts) : "UNKNOWN";
}

/** The evidence age of this section: the oldest instant among the reads that succeeded (never the inventory read). */
function evidenceInstant(reads: ReadonlyArray<TimedRead<unknown>>): string | null {
  const instants = reads
    .filter((r) => r.read.state === "ok" && r.readAt)
    .map((r) => r.readAt as string);
  if (instants.length === 0) return null;
  return instants.reduce((a, b) => (Date.parse(b) < Date.parse(a) ? b : a));
}

function Line({ label, value }: { readonly label: string; readonly value: string }) {
  return (
    <Box sx={{ display: "contents" }}>
      <Box component="dt" sx={{ m: 0, fontSize: 12.5, lineHeight: 1.35, color: ev.ink2 }}>{label}</Box>
      <Box component="dd" sx={{ m: 0, textAlign: "right", fontFamily: ESTATE_MONO, fontSize: 13.5, fontWeight: 600, color: ev.ink, wordBreak: "break-word" }}>{value}</Box>
    </Box>
  );
}

function PodRow({ pod }: { readonly pod: PodStatusView }) {
  const word = podStateWord(pod.state);
  return (
    <Box component="li" data-testid="estate-system-pod-row" data-state={word}
      sx={{ display: "grid", gridTemplateColumns: "minmax(0, 1fr) auto", gap: "4px 12px", alignItems: "center", py: "6px", borderTop: `1px solid ${ev.line}`, listStyle: "none" }}>
      <Box sx={{ minWidth: 0, fontFamily: ESTATE_MONO, fontSize: 12.5, color: ev.ink, overflowWrap: "anywhere" }}>{pod.name}</Box>
      <StateBadge state={podBadgeOf(word)} word={word} dense />
      <Box sx={{ gridColumn: "1 / -1", fontSize: 12, color: ev.ink3 }}>restarts: {restartsLabel(pod.restarts)}</Box>
    </Box>
  );
}

export interface EstateSystemPodsStorageProps {
  readonly pods: TimedRead<PodsView>;
  readonly storage: TimedRead<StorageView>;
  readonly now: Date;
}

/**
 * Read-only "Pods and storage" section of the Estate System screen. Same source reads as the standard System status
 * panel (`getSystemPods`, `getSystemStorage`). A failed or missing read is an error line, never a clean state.
 */
export function EstateSystemPodsStorage({ pods, storage, now }: EstateSystemPodsStorageProps) {
  const podsRead = pods.read;
  const storageRead = storage.read;
  const restricted = podsRead.state === "restricted" || storageRead.state === "restricted";
  const podsOk = podsRead.state === "ok" ? podsRead.value : null;
  const storageOk = storageRead.state === "ok" ? storageRead.value : null;

  const badge: BadgeState = restricted ? "na" : podsOk && storageOk && podsOk.available ? "na" : "unk";
  const word = restricted ? "Restricted" : badge === "na" ? "Counted" : "UNKNOWN";

  const podList = podsOk && podsOk.available ? podsOk.pods : [];
  const shown = podList.slice(0, PODS_ROW_CAP);
  const vol = storageOk?.artefact_volume;

  return (
    <EstateCard sx={{ p: "16px 18px", display: "flex", flexDirection: "column", gap: "10px", minWidth: 0 }}>
      <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 1 }}>
        <Box component="h2" sx={{ m: 0, fontSize: 16, fontWeight: 700 }}>Pods and storage</Box>
        <StateBadge state={badge} word={word} dense />
      </Box>
      <Box sx={{ fontSize: 12, color: ev.ink3 }}>Read-only. Changes happen in the standard view.</Box>

      {restricted ? (
        <Box sx={{ fontSize: 13, color: ev.ink2 }}>{RESTRICTED_SENTENCE}</Box>
      ) : (
        <>
          {podsRead.state !== "ok" ? (
            <Box role="alert" sx={{ fontSize: 13, color: ev.ink2 }}>Pods could not be read: UNKNOWN. This is not a clean state.</Box>
          ) : !podsOk || !podsOk.available ? (
            <Box role="alert" sx={{ fontSize: 13, color: ev.ink2 }}>
              Pods are UNKNOWN: {podsOk?.reason ?? "the status service did not report them"}.
            </Box>
          ) : (
            <Box component="dl" sx={{ m: 0, display: "grid", gridTemplateColumns: "minmax(0, 1fr) auto", rowGap: "6px", columnGap: "12px" }}>
              <Line label="Pods listed" value={String(podList.length)} />
              {countPodStates(podList).map(([w, n]) => (
                <Line key={w} label={`Pods in state ${w}`} value={String(n)} />
              ))}
            </Box>
          )}

          {podsOk && podsOk.available && podList.length > 0 ? (
            <Box component="ul" aria-label="Pods" sx={{ m: 0, p: 0 }}>
              {shown.map((p, i) => <PodRow key={`${p.name}:${i}`} pod={p} />)}
              {podList.length > PODS_ROW_CAP ? (
                <Box component="li" sx={{ listStyle: "none", pt: "6px", fontSize: 12, color: ev.ink3 }}>
                  <MoreFooter shown={shown.length} total={podList.length} noun="pods" testId="estate-system-pods-more" />
                </Box>
              ) : null}
            </Box>
          ) : null}

          {storageRead.state !== "ok" ? (
            <Box role="alert" sx={{ fontSize: 13, color: ev.ink2 }}>Storage could not be read: UNKNOWN. This is not a clean state.</Box>
          ) : (
            <Box component="dl" aria-label="Storage" sx={{ m: 0, display: "grid", gridTemplateColumns: "minmax(0, 1fr) auto", rowGap: "6px", columnGap: "12px" }}>
              <Line label="Artefact volume, used of total" value={`${figureOrUnknown(vol?.used_bytes)} of ${figureOrUnknown(vol?.total_bytes)}`} />
              <Line label="Database size" value={figureOrUnknown(storageOk?.database_bytes)} />
            </Box>
          )}
        </>
      )}

      <EvidenceStamp at={evidenceInstant([pods, storage])} source={SOURCE} now={now} />
      <Box component="a" href={PODS_STORAGE_STANDARD_HREF} sx={{ mt: "auto", fontSize: 13, color: ev.accent, fontWeight: 600 }}>
        Open system status (standard) →
      </Box>
    </EstateCard>
  );
}
