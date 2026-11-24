import { useMemo, useState, type ReactNode } from "react";
import Box from "@mui/material/Box";

import type { ChangeFeedEvent } from "../auth/adminApi";
import { m3, MONO } from "../theme/m3Theme";
import { RADIUS, TYPE } from "../theme/tokens";
import {
  ARROW, logSentence, pairDiffLines, plural, summariseChanges,
  type FieldChange, type KeyChange, type RefItem, type RefsChange, type Side, type TextSegment,
} from "./changeDiffModel";

/**
 * The diff block of one change-feed event, collapsed by default.
 *
 * A stored policy rule diff (`readable`) is shown the way a person reads it (PO 2026-11-24): a one-line summary, then
 * one row per changed field -- an object list as what was added and removed by name, `extras` as the keys that
 * changed, the log setting as its two halves, a text as before and after with the changed words marked. The same
 * rows serve an added rule (only "after") and a removed rule (only "before"). Object IDs stay out of the rows and
 * are one hover or one "Object IDs" fold away. The Raw toggle shows the exact stored lines, so nothing is lost.
 *
 * Any other diff -- and a rule diff the client cannot pair reliably -- is the raw mono block alone, as before.
 * Names are rendered as they arrive: the masked view stays masked because nothing here rewrites a value.
 */
export function ChangeDiff({ lines, readable }: { readonly lines: ChangeFeedEvent["diff"]; readonly readable: boolean }) {
  const changes = useMemo(() => (readable ? pairDiffLines(lines) : null), [lines, readable]);
  const [raw, setRaw] = useState(false);
  const added = lines.filter((l) => l.op === "+").length, removed = lines.filter((l) => l.op === "-").length;
  const counts = `${added} added · ${removed} removed`;
  return (
    <Box component="details" sx={{ "& > summary": { cursor: "pointer", fontSize: TYPE.label.fontSize, color: m3.onSurfaceVar, overflowWrap: "anywhere" } }}>
      <Box component="summary">{`Diff · ${changes ? summariseChanges(changes) : counts}`}</Box>
      {changes && (
        <Box sx={{ mt: 0.5, display: "flex", gap: 1, alignItems: "center", fontSize: TYPE.label.fontSize, color: m3.onSurfaceVar }}>
          <Box component="button" type="button" aria-pressed={raw} onClick={() => setRaw((on) => !on)}
            title="Show the exact stored lines instead of the readable rows"
            sx={{ font: "inherit", fontWeight: 500, lineHeight: "20px", px: 1.25, py: 0, cursor: "pointer", borderRadius: `${RADIUS.pill}px`,
                  border: `1px solid ${raw ? m3.primary : m3.outlineVar}`, bgcolor: raw ? m3.primaryContainer : m3.scLowest,
                  color: raw ? m3.onPrimaryContainer : m3.onSurface, "&:focus-visible": { outline: `2px solid ${m3.primary}`, outlineOffset: 2 } }}>
            Raw
          </Box>
          <span>{`${plural(changes.length, "field")} · stored lines: ${counts}`}</span>
        </Box>
      )}
      {changes && !raw ? <Rows changes={changes} /> : <RawLines lines={lines} />}
    </Box>
  );
}

/** The exact stored lines in mono: removed lines in the error ink, added lines in the success ink. */
function RawLines({ lines }: { readonly lines: ChangeFeedEvent["diff"] }) {
  return (
    <Box component="pre" sx={{ m: 0, mt: 0.5, p: 1, fontFamily: MONO, fontSize: TYPE.mono.fontSize, lineHeight: TYPE.mono.lineHeight,
                               bgcolor: m3.scLow, border: `1px solid ${m3.outlineVar}`, borderRadius: `${RADIUS.pill}px`, overflowX: "auto", whiteSpace: "pre" }}>
      {lines.map((line, index) => (
        <Box key={index} component="span" data-op={line.op} sx={{ display: "block", color: line.op === "+" ? m3.onSuccessContainer : line.op === "-" ? m3.onErrorContainer : m3.onSurfaceVar }}>
          {`${line.op} ${line.text}`}
        </Box>
      ))}
    </Box>
  );
}

const INK = { added: m3.onSuccessContainer, removed: m3.onErrorContainer, muted: m3.onSurfaceVar } as const;

function Rows({ changes }: { readonly changes: readonly FieldChange[] }) {
  return (
    <Box component="dl" aria-label="Changed fields"
      sx={{ m: 0, mt: 0.5, p: 1, display: "grid", gridTemplateColumns: "minmax(88px, max-content) minmax(0, 1fr)", columnGap: 1.5, rowGap: 0.75,
            fontSize: TYPE.mono.fontSize, lineHeight: TYPE.mono.lineHeight, bgcolor: m3.scLow, border: `1px solid ${m3.outlineVar}`, borderRadius: `${RADIUS.pill}px` }}>
      {changes.map((change) => (
        <Box key={change.field} data-field={change.field} data-kind={change.kind} data-side={change.side} sx={{ display: "contents" }}>
          <Box component="dt" title={change.field} sx={{ m: 0, fontWeight: 600, color: m3.onSurface }}>{change.label}</Box>
          <Box component="dd" sx={{ m: 0, minWidth: 0, color: m3.onSurface, overflowWrap: "anywhere", display: "grid", gap: 0.25 }}>
            <FieldBody change={change} />
          </Box>
        </Box>
      ))}
    </Box>
  );
}

function FieldBody({ change }: { readonly change: FieldChange }) {
  switch (change.kind) {
    case "refs": return <RefsBody change={change} />;
    case "keys": return change.keys.length === 0
      ? <Box sx={{ color: INK.muted }}>No key changed (key order only)</Box>
      : <>{change.keys.map((k) => <KeyLine key={k.key} change={k} side={change.side} />)}</>;
    case "log": return <Box data-line="log">{logSentence(change)}</Box>;
    case "text": return (
      <Box data-line="text">
        {change.before !== null && <Segments segments={change.beforeSegments} as="del" />}
        {change.side === "both" && ` ${ARROW} `}
        {change.after !== null && <Segments segments={change.afterSegments} as="ins" />}
      </Box>
    );
    default: return (
      <Box data-line="raw" title="Not readable as a value; shown as stored" sx={{ fontFamily: MONO }}>
        {change.before !== null && <Box component="span" sx={{ color: change.side === "both" ? INK.removed : undefined }}>{change.before}</Box>}
        {change.side === "both" && ` ${ARROW} `}
        {change.after !== null && <Box component="span" sx={{ color: change.side === "both" ? INK.added : undefined }}>{change.after}</Box>}
      </Box>
    );
  }
}

/** A text with its changed words marked: struck through on the before side, underlined on the after side. */
function Segments({ segments, as }: { readonly segments: readonly TextSegment[]; readonly as: "del" | "ins" }) {
  if (segments.length === 0) return <Box component="span" sx={{ color: INK.muted }}>(empty)</Box>;
  return (
    <>
      {segments.map((segment, index) => segment.changed
        ? <Box key={index} component={as} sx={{ color: as === "del" ? INK.removed : INK.added, fontWeight: 600, whiteSpace: "pre-wrap",
                                               bgcolor: as === "del" ? m3.errorContainer : m3.successContainer, borderRadius: "2px" }}>{segment.text}</Box>
        : <Box key={index} component="span" sx={{ whiteSpace: "pre-wrap" }}>{segment.text}</Box>)}
    </>
  );
}

/** One changed key of a JSON object: "key: before -> after", or its one value with (added) / (removed). */
function KeyLine({ change, side }: { readonly change: KeyChange; readonly side: Side }) {
  const label = <Box component="span" title={change.key} sx={{ color: INK.muted }}>{`${change.label}: `}</Box>;
  if (change.state === "changed") return <Box data-key={change.key}>{label}{`${change.before} ${ARROW} ${change.after}`}</Box>;
  const value = change.state === "added" ? change.after : change.before;
  return (
    <Box data-key={change.key}>
      {label}{value}
      {side === "both" && <Box component="span" sx={{ color: change.state === "added" ? INK.added : INK.removed }}>{` (${change.state})`}</Box>}
    </Box>
  );
}

/** Names with the object ID on hover; the ID itself is never in the row text. */
function Names({ refs }: { readonly refs: readonly RefItem[] }) {
  return (
    <>
      {refs.map((ref, index) => (
        <span key={`${ref.name}:${index}`}>
          {index > 0 && ", "}
          <Box component="span" data-ref title={ref.id ? `Object ID ${ref.id}` : undefined} sx={{ fontFamily: MONO }}>{ref.name}</Box>
        </span>
      ))}
    </>
  );
}

/** What a same-named object was before: its other ID, or -- when the ID is the same -- the entry as it was stored. */
function was(ref: RefItem): string {
  const previous = ref.previous;
  if (!previous) return "";
  return previous.id !== ref.id ? previous.id ?? "no ID stored" : previous.stored;
}

function RefsBody({ change }: { readonly change: RefsChange }) {
  const { added, removed, unchanged } = change;
  const reidentified = unchanged.filter((ref) => ref.previous !== undefined);
  const groups: readonly (readonly [string, readonly RefItem[]])[] = change.side === "after" ? [["After", added]]
    : change.side === "before" ? [["Before", removed]] : [["Added", added], ["Removed", removed], ["Unchanged", unchanged]];
  const hasIds = groups.some(([, refs]) => refs.some((ref) => ref.id !== null));
  const line = (name: string, ink: string | undefined, body: ReactNode) => <Box data-line={name} sx={{ color: ink }}>{body}</Box>;
  return (
    <>
      {change.side === "after" && line("after", undefined, added.length > 0 ? <Names refs={added} /> : "(none)")}
      {change.side === "before" && line("before", undefined, removed.length > 0 ? <Names refs={removed} /> : "(none)")}
      {change.side === "both" && <>
        {added.length > 0 && line("added", INK.added, <>Added: <Names refs={added} /></>)}
        {removed.length > 0 && line("removed", INK.removed, <>Removed: <Names refs={removed} /></>)}
        {line("unchanged", INK.muted, `Unchanged: ${plural(unchanged.length, "object")}`)}
        {change.reordered && line("reordered", INK.muted, "Same objects, order changed")}
        {reidentified.length > 0 && line("reidentified", undefined, <>Same name, different object: <Names refs={reidentified} /></>)}
      </>}
      {change.keys.map((k) => <KeyLine key={k.key} change={k} side={change.side} />)}
      {hasIds && (
        <Box component="details" data-line="ids" sx={{ "& > summary": { cursor: "pointer", fontSize: TYPE.label.fontSize, color: INK.muted } }}>
          <Box component="summary">Object IDs</Box>
          <Box component="ul" sx={{ m: 0, pl: 2, fontFamily: MONO, color: INK.muted }}>
            {groups.flatMap(([group, refs]) => refs.map((ref, index) => (
              <li key={`${group}:${ref.name}:${index}`}>
                {`${group} · ${ref.name} · ${ref.id ?? "no ID stored"}${ref.previous ? ` (was ${was(ref)})` : ""}`}
              </li>
            )))}
          </Box>
        </Box>
      )}
    </>
  );
}
