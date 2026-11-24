/**
 * The readable form of a stored policy rule diff (PO 2026-11-24). The change feed sends a rule revision as lines of
 * `- field: <JSON>` and `+ field: <JSON>`; this module pairs them by field and describes each changed field the way
 * a person reads it: an object list as a set difference by name, a JSON object as the keys that changed, the log
 * setting as its two halves, a text as before and after with the changed words marked.
 *
 * Pure and presentation-only: it never rewrites a name or an identifier, so what the server sent masked stays
 * masked, and it drops nothing -- the screen keeps the exact lines behind its Raw toggle. Whatever it cannot read
 * reliably it hands back as raw text (one field), or it declines the whole diff (`null`) so the caller shows the
 * raw lines alone.
 */

export interface DiffLine { readonly op: "-" | "+" | " "; readonly text: string }

/**
 * One object of a `{"refs":[...]}` list: its name, its ID where one is stored, and `stored`, the entry exactly as
 * stored (keys sorted). `previous` is set when the same name was a different stored entry before -- another ID, or
 * another attribute -- so "same name" is never shown as "nothing changed".
 */
export interface RefItem { readonly name: string; readonly id: string | null; readonly stored: string; readonly previous?: { readonly id: string | null; readonly stored: string } }
export interface KeyChange { readonly key: string; readonly label: string; readonly state: "added" | "removed" | "changed"; readonly before: string | null; readonly after: string | null }
export interface TextSegment { readonly text: string; readonly changed: boolean }
export interface LogPart { readonly when: "start" | "end"; readonly before: string | null; readonly after: string | null }

/** Which sides the stored diff carries for a field: both (changed), only after (added) or only before (removed). */
export type Side = "both" | "after" | "before";
interface Base { readonly field: string; readonly label: string; readonly side: Side }
export interface RefsChange extends Base {
  readonly kind: "refs";
  readonly added: readonly RefItem[]; readonly removed: readonly RefItem[]; readonly unchanged: readonly RefItem[];
  /** True when both sides hold the same objects in a different order. */
  readonly reordered: boolean;
  /** The other keys of the cell (for example `negated`) that changed. */
  readonly keys: readonly KeyChange[];
}
export interface KeysChange extends Base { readonly kind: "keys"; readonly keys: readonly KeyChange[] }
export interface LogChange extends Base { readonly kind: "log"; readonly parts: readonly LogPart[] }
export interface TextChange extends Base {
  readonly kind: "text"; readonly before: string | null; readonly after: string | null;
  readonly beforeSegments: readonly TextSegment[]; readonly afterSegments: readonly TextSegment[];
}
/** A value that is not JSON, or a shape this module has no reading for: shown exactly as stored. */
export interface RawChange extends Base { readonly kind: "raw"; readonly before: string | null; readonly after: string | null }
export type FieldChange = RefsChange | KeysChange | LogChange | TextChange | RawChange;

export const ARROW = "→";

const FIELD_LABELS: Readonly<Record<string, string>> = {
  name: "Name", number: "Rule number", enabled: "Enabled", source: "Source", destination: "Destination", service: "Service",
  application: "Application", action: "Action", log: "Logging", comment: "Comment", extras: "Other settings",
  container: "Container", section: "Section", parentRuleId: "Parent rule",
};
/** Readable words for the keys inside `extras`; a key that is not listed is shown by its raw name. */
const KEY_LABELS: Readonly<Record<string, string>> = {
  from: "Zones from", to: "Zones to", "source-user": "Source user", category: "Category", tag: "Tags",
  "log-setting": "Log setting", "profile-setting/group": "Profile group", "rule-type": "Rule type", schedule: "Schedule",
  time: "Time", "install-on": "Install on", vpn: "VPN", "last-modified": "Last modified", "last-modifier": "Last modifier",
  negated: "Negated",
};
const PROFILE_PREFIX = "profile-setting/profiles/";

export function fieldLabel(field: string): string {
  return FIELD_LABELS[field] ?? field;
}
export function keyLabel(key: string): string {
  if (KEY_LABELS[key]) return KEY_LABELS[key];
  if (key.startsWith(PROFILE_PREFIX) && key.length > PROFILE_PREFIX.length) return `Profile ${key.slice(PROFILE_PREFIX.length)}`;
  return key;
}

const LINE = /^([A-Za-z][\w.\-/]*): ([\s\S]+)$/;
const LOG = /^start=(\S+), end=(\S+)$/;
const ABSENT = Symbol("absent");
type Parsed = { readonly raw: string; readonly value: unknown; readonly ok: boolean };

function parseValue(raw: string): Parsed {
  try {
    return { raw, value: JSON.parse(raw) as unknown, ok: true };
  } catch {
    return { raw, value: undefined, ok: false };
  }
}

/**
 * Pairs the `-` and `+` lines by field, in the order the fields first appear. Returns `null` -- show the raw lines
 * alone -- when a line is not `field: value`, when a context line is present, or when one field has two lines on the
 * same side: none of those can be paired without guessing.
 */
export function pairDiffLines(lines: readonly DiffLine[]): FieldChange[] | null {
  if (lines.length === 0) return null;
  const order: string[] = [];
  const sides = new Map<string, { before?: Parsed; after?: Parsed }>();
  for (const line of lines) {
    if (line.op !== "-" && line.op !== "+") return null;
    const match = LINE.exec(line.text);
    if (!match) return null;
    const field = match[1];
    let entry = sides.get(field);
    if (!entry) { entry = {}; sides.set(field, entry); order.push(field); }
    const slot = line.op === "-" ? "before" : "after";
    if (entry[slot]) return null;
    entry[slot] = parseValue(match[2]);
  }
  return order.map((field) => describe(field, sides.get(field)?.before, sides.get(field)?.after));
}

function describe(field: string, before: Parsed | undefined, after: Parsed | undefined): FieldChange {
  const base: Base = { field, label: fieldLabel(field), side: before && after ? "both" : after ? "after" : "before" };
  const raw: RawChange = { ...base, kind: "raw", before: before?.raw ?? null, after: after?.raw ?? null };
  if ((before && !before.ok) || (after && !after.ok)) return raw;
  const b: unknown = before ? before.value : ABSENT, a: unknown = after ? after.value : ABSENT;
  const present = [b, a].filter((v) => v !== ABSENT);

  if (present.every(isRefsCell)) return refsChange(base, b === ABSENT ? null : (b as RefsCell), a === ABSENT ? null : (a as RefsCell));
  if (field === "log" && present.every((v) => typeof v === "string" && LOG.test(v))) {
    const [bm, am] = [b, a].map((v) => (typeof v === "string" ? LOG.exec(v) : null));
    return { ...base, kind: "log", parts: [
      { when: "start", before: bm ? bm[1] : null, after: am ? am[1] : null },
      { when: "end", before: bm ? bm[2] : null, after: am ? am[2] : null },
    ] };
  }
  if (present.every(isScalar)) {
    const bt = scalarText(before), at = scalarText(after);
    const [beforeSegments, afterSegments] = diffText(bt ?? "", at ?? "");
    return { ...base, kind: "text", before: bt, after: at,
      beforeSegments: at === null ? [{ text: bt ?? "", changed: false }] : beforeSegments,
      afterSegments: bt === null ? [{ text: at ?? "", changed: false }] : afterSegments };
  }
  if (present.every(isPlainObject)) {
    return { ...base, kind: "keys", keys: diffKeys(b === ABSENT ? null : (b as Record<string, unknown>), a === ABSENT ? null : (a as Record<string, unknown>)) };
  }
  return raw;
}

// ---------------------------------------------------------------- object lists

type RefsCell = Record<string, unknown> & { readonly refs: readonly unknown[] };

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
function isScalar(value: unknown): value is string | number | boolean {
  return typeof value === "string" || typeof value === "number" || typeof value === "boolean";
}
/** Keep numeric scalars as their exact raw JSON text so identifiers above 2^53 are never rounded. */
function scalarText(parsed: Parsed | undefined): string | null {
  if (!parsed) return null;
  return typeof parsed.value === "number" ? parsed.raw.trim() : String(parsed.value);
}
/** A reference is a bare name, or an object with a `name`; its `id` counts as an ID only when it is a text. */
function toRef(value: unknown): RefItem | null {
  if (typeof value === "string") return { name: value, id: null, stored: canonical(value) };
  if (!isPlainObject(value) || typeof value.name !== "string") return null;
  return { name: value.name, id: typeof value.id === "string" ? value.id : null, stored: canonical(value) };
}
function isRefsCell(value: unknown): value is RefsCell {
  return isPlainObject(value) && Array.isArray(value.refs) && value.refs.every((ref) => toRef(ref) !== null);
}
function refsOf(cell: RefsCell | null): RefItem[] {
  return cell ? cell.refs.map((ref) => toRef(ref) as RefItem) : [];
}
function restOf(cell: RefsCell | null): Record<string, unknown> | null {
  if (!cell) return null;
  const rest: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(cell)) if (key !== "refs") rest[key] = value;
  return rest;
}

function refsChange(base: Base, before: RefsCell | null, after: RefsCell | null): RefsChange {
  const was = refsOf(before), now = refsOf(after);
  if (!before || !after) {
    return { ...base, kind: "refs", added: now, removed: was, unchanged: [], reordered: false, keys: diffKeys(restOf(before), restOf(after)) };
  }
  const usedWas = new Array<boolean>(was.length).fill(false);
  const matchedWas = new Array<number>(now.length).fill(-1);
  const byStored = new Map<string, number[]>();
  for (let i = 0; i < was.length; i++) {
    const queue = byStored.get(was[i].stored);
    if (queue) queue.push(i);
    else byStored.set(was[i].stored, [i]);
  }
  for (let j = 0; j < now.length; j++) {
    const i = byStored.get(now[j].stored)?.shift();
    if (i !== undefined) { usedWas[i] = true; matchedWas[j] = i; }
  }
  const byName = new Map<string, number[]>();
  for (let i = 0; i < was.length; i++) {
    if (usedWas[i]) continue;
    const queue = byName.get(was[i].name);
    if (queue) queue.push(i);
    else byName.set(was[i].name, [i]);
  }
  for (let j = 0; j < now.length; j++) {
    if (matchedWas[j] !== -1) continue;
    const i = byName.get(now[j].name)?.shift();
    if (i !== undefined) { usedWas[i] = true; matchedWas[j] = i; }
  }
  const added: RefItem[] = [], unchanged: RefItem[] = [];
  for (let j = 0; j < now.length; j++) {
    const ref = now[j], i = matchedWas[j];
    if (i === -1) added.push(ref);
    else {
      const earlier = was[i];
      unchanged.push(earlier.stored !== ref.stored ? { ...ref, previous: { id: earlier.id, stored: earlier.stored } } : ref);
    }
  }
  const removed = was.filter((_, i) => !usedWas[i]);
  const reordered = added.length === 0 && removed.length === 0
    && unchanged.every((ref) => ref.previous === undefined)
    && was.map((ref) => ref.stored).join("\u0000") !== now.map((ref) => ref.stored).join("\u0000");
  return { ...base, kind: "refs", added, removed, unchanged, reordered, keys: diffKeys(restOf(before), restOf(after)) };
}

// ---------------------------------------------------------------- JSON objects

/** Nested objects become dotted paths, so a change deep inside `extras` is still one "key: before -> after" line. */
function flatten(value: Record<string, unknown>, prefix: string, out: Map<string, unknown>): Map<string, unknown> {
  for (const [key, child] of Object.entries(value)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (isPlainObject(child) && Object.keys(child).length > 0) flatten(child, path, out);
    else out.set(path, child);
  }
  return out;
}
/** Key order inside an object carries no meaning, so two values are compared with their keys sorted. */
function canonical(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
  if (isPlainObject(value)) return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonical(value[key])}`).join(",")}}`;
  return JSON.stringify(value) ?? "null";
}
/** A value as a person reads it: a text as itself, a list of plain values joined by commas, anything else as its JSON. */
export function formatValue(value: unknown): string {
  if (typeof value === "string") return value === "" ? "(empty)" : value;
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  if (Array.isArray(value) && value.every(isScalar)) return value.length === 0 ? "(none)" : value.map(String).join(", ");
  return JSON.stringify(value) ?? "null";
}
function diffKeys(before: Record<string, unknown> | null, after: Record<string, unknown> | null): KeyChange[] {
  const was = flatten(before ?? {}, "", new Map()), now = flatten(after ?? {}, "", new Map());
  const out: KeyChange[] = [];
  for (const key of new Set([...was.keys(), ...now.keys()])) {
    const inWas = was.has(key), inNow = now.has(key);
    if (inWas && inNow && canonical(was.get(key)) === canonical(now.get(key))) continue;
    out.push({ key, label: keyLabel(key), state: inWas && inNow ? "changed" : inNow ? "added" : "removed",
      before: inWas ? formatValue(was.get(key)) : null, after: inNow ? formatValue(now.get(key)) : null });
  }
  return out;
}

// ---------------------------------------------------------------- text

/**
 * Marks what differs between two texts, by whole words: the words both share at the start and at the end stay plain,
 * the middle is the changed part. An appended word reads as one marked tail; a different text is marked whole.
 */
export function diffText(before: string, after: string): [TextSegment[], TextSegment[]] {
  const tokens = (text: string) => text.match(/\s+|\S+/g) ?? [];
  const b = tokens(before), a = tokens(after);
  let head = 0;
  while (head < b.length && head < a.length && b[head] === a[head]) head++;
  let tail = 0;
  while (tail < b.length - head && tail < a.length - head && b[b.length - 1 - tail] === a[a.length - 1 - tail]) tail++;
  const segments = (t: string[]): TextSegment[] => [
    { text: t.slice(0, head).join(""), changed: false },
    { text: t.slice(head, t.length - tail).join(""), changed: true },
    { text: t.slice(t.length - tail).join(""), changed: false },
  ].filter((segment) => segment.text !== "");
  return [segments(b), segments(a)];
}

// ---------------------------------------------------------------- sentences

const plural = (count: number, word: string) => `${count} ${word}${count === 1 ? "" : "s"}`;

/** "Log at session start: UNKNOWN -> no; at session end: UNKNOWN -> yes", or the one side an added or removed rule has. */
export function logSentence(change: LogChange): string {
  const part = (p: LogPart) => {
    if (change.side === "after") return p.after ?? "UNKNOWN";
    if (change.side === "before") return p.before ?? "UNKNOWN";
    return p.before === p.after ? `${p.after} (unchanged)` : `${p.before} ${ARROW} ${p.after}`;
  };
  const [start, end] = change.parts;
  return `Log at session start: ${part(start)}; at session end: ${part(end)}`;
}

const SUMMARY_PARTS = 5;
const SHORT_VALUE = 24;

function summarise(change: FieldChange): string[] {
  if (change.side !== "both") return [`${change.label} ${change.side === "after" ? "added" : "removed"}`];
  switch (change.kind) {
    case "refs": {
      const counts = [change.added.length > 0 ? `+${change.added.length}` : "", change.removed.length > 0 ? `-${change.removed.length}` : ""].filter(Boolean);
      if (counts.length > 0) return [`${change.label} ${counts.join(" ")}`];
      return [`${change.label} ${change.reordered && change.keys.length === 0 ? "reordered" : "changed"}`];
    }
    case "log": {
      const turns = change.parts.flatMap((p) => {
        if (p.before === p.after) return [];
        if (p.before === "no" && p.after === "yes") return [`Logging at ${p.when} turned on`];
        if (p.before === "yes" && p.after === "no") return [`Logging at ${p.when} turned off`];
        return [`Logging at ${p.when}: ${p.before ?? "UNKNOWN"} ${ARROW} ${p.after ?? "UNKNOWN"}`];
      });
      return turns.length > 0 ? turns : ["Logging changed"];
    }
    case "keys":
      if (change.keys.length === 0) return [`${change.label} changed`];
      return change.keys.length <= 2 ? change.keys.map((k) => `${k.label} ${k.state}`) : [`${change.label}: ${plural(change.keys.length, "key")} changed`];
    case "text": {
      const short = change.field !== "comment" && (change.before ?? "").length <= SHORT_VALUE && (change.after ?? "").length <= SHORT_VALUE
        && change.before !== "" && change.after !== "";
      return [short ? `${change.label}: ${change.before} ${ARROW} ${change.after}` : `${change.label} changed`];
    }
    default:
      return [`${change.label} changed`];
  }
}

/**
 * The one line above the rows, e.g. "Destination +1 -1 · Logging at end turned on · Comment changed". A diff that is
 * one-sided throughout (an added or a removed rule) with many fields is counted instead of listed.
 */
export function summariseChanges(changes: readonly FieldChange[]): string {
  if (changes.length > 3 && changes.every((c) => c.side === "after")) return `${plural(changes.length, "field")} added`;
  if (changes.length > 3 && changes.every((c) => c.side === "before")) return `${plural(changes.length, "field")} removed`;
  const parts = changes.flatMap(summarise);
  const shown = parts.slice(0, SUMMARY_PARTS);
  if (parts.length > shown.length) shown.push(`+${parts.length - shown.length} more`);
  return shown.join(" · ");
}

export { plural };
