import { useEffect } from "react";
import { askHref, backupsHref, briefHref, changesHref, complianceHref, dossierHref, failoverHref, rewindHref, wallHref } from "./estateRouting";
import { ESTATE_LENSES, LENS_META, type EstateCluster, type EstateUnit } from "./estateModel";

/** Find palette entries: a unit (opens its dossier), a cluster (opens the wall filtered to it) or an Estate screen. */
export type EstateFindKind = "unit" | "cluster" | "screen";

export interface EstateFindEntry {
  readonly kind: EstateFindKind;
  readonly key: string;
  /** Display label; masked unit names are passed through exactly as the Estate model holds them. */
  readonly label: string;
  /** Kind-specific sub line, e.g. "Unit · cluster CLS-ROMEO-01". Says what it is, never a raw identifier. */
  readonly sub: string;
  readonly href: string;
  /** Lower-cased haystack used for matching only; never rendered. */
  readonly haystack: string;
  /** Lower-cased name used for ranking only. */
  readonly nameKey: string;
}

export interface EstateFindResult {
  readonly hits: readonly EstateFindEntry[];
  /** Total matches before the cap; the UI prints "+N more" when this exceeds `hits.length`. */
  readonly total: number;
}

export const ESTATE_FIND_LIMIT = 20;

const KIND_WORD: Readonly<Record<EstateFindKind, string>> = {
  unit: "Unit",
  cluster: "HA cluster",
  screen: "Estate screen",
};

function normalise(value: string): string {
  return value.replace(/\s+/g, " ").trim().toLowerCase();
}

function clusterHref(ref: string): string {
  return `?screen=inventory&cluster_ref=${encodeURIComponent(ref)}`;
}

/** The fixed Estate screens and lenses, matched by name. */
export function estateScreenEntries(): readonly EstateFindEntry[] {
  const screens: ReadonlyArray<readonly [string, string, string]> = [
    ["Brief", "Shift brief", briefHref()],
    ["Estate wall", "Estate · wall and list", wallHref()],
    ["Failover", "Estate · failover lens", failoverHref()],
    ["Changes", "Estate · changes river", changesHref()],
    ["Ask", "Estate · ask workbench", askHref()],
    ["Compliance", "Estate · controls lens", complianceHref()],
    ["Backups", "Estate · backups lens", backupsHref()],
    ["Rewind", "Estate · evidence clock", rewindHref()],
  ];
  const lensEntries = ESTATE_LENSES.filter((lens) => lens !== "worst").map((lens) => ({
    label: `${LENS_META[lens].label} lens`,
    href: wallHref(lens),
  }));
  const base = screens.map(([label, sub, href]) => ({ key: `screen:${label}`, label, sub, href }));
  const lenses = lensEntries.map((lens) => ({ key: `lens:${lens.label}`, label: lens.label, sub: "Estate · wall lens", href: lens.href }));
  return [...base, ...lenses].map((entry) => ({
    kind: "screen" as const,
    key: entry.key,
    label: entry.label,
    sub: `${KIND_WORD.screen} · ${entry.sub}`,
    href: entry.href,
    haystack: normalise(`${entry.label} ${entry.sub}`),
    nameKey: normalise(entry.label),
  }));
}

/** Builds the searchable index from the units and clusters the Estate view already loaded. */
export function buildEstateFindIndex(
  units: readonly EstateUnit[],
  clusters: readonly EstateCluster[],
): readonly EstateFindEntry[] {
  const clusterTitle = new Map<string, string>();
  for (const cluster of clusters) clusterTitle.set(cluster.ref, cluster.title);
  const unitEntries: EstateFindEntry[] = units.map((unit) => {
    const clusterName = unit.clusterRef ? (clusterTitle.get(unit.clusterRef) ?? unit.clusterRef) : null;
    const sub = clusterName ? `${KIND_WORD.unit} · cluster ${clusterName}` : KIND_WORD.unit;
    return {
      kind: "unit",
      key: `unit:${unit.deviceId}`,
      label: unit.name,
      sub,
      href: dossierHref(unit.deviceId),
      // Address is matched but never rendered: the palette shows names and clusters only.
      haystack: normalise([unit.name, clusterName ?? "", unit.managementIp ?? "", unit.vendorName].join(" ")),
      nameKey: normalise(unit.name),
    };
  });
  const clusterEntries: EstateFindEntry[] = clusters.map((cluster) => ({
    kind: "cluster",
    key: `cluster:${cluster.ref}`,
    label: cluster.title,
    sub: `${KIND_WORD.cluster} · ${cluster.members.length} unit${cluster.members.length === 1 ? "" : "s"}`,
    href: clusterHref(cluster.ref),
    haystack: normalise([cluster.title, cluster.ref].join(" ")),
    nameKey: normalise(cluster.title),
  }));
  return [...unitEntries, ...clusterEntries, ...estateScreenEntries()];
}

/**
 * Matches a query against the index. Every whitespace-separated term must appear in the haystack. Empty query returns
 * the Estate screens (so the palette is useful before typing). Ranking: name starts with the query, name contains it,
 * then other fields; ties keep index order.
 */
export function findEstateEntries(
  index: readonly EstateFindEntry[],
  query: string,
  limit: number = ESTATE_FIND_LIMIT,
): EstateFindResult {
  const terms = normalise(query).split(" ").filter(Boolean);
  if (terms.length === 0) {
    const screens = index.filter((entry) => entry.kind === "screen");
    return { hits: screens.slice(0, limit), total: screens.length };
  }
  const ranked: Array<{ entry: EstateFindEntry; rank: number; order: number }> = [];
  index.forEach((entry, order) => {
    if (!terms.every((term) => entry.haystack.includes(term))) return;
    const first = terms[0];
    const rank = entry.nameKey.startsWith(first) ? 0 : entry.nameKey.includes(first) ? 1 : 2;
    ranked.push({ entry, rank, order });
  });
  ranked.sort((a, b) => a.rank - b.rank || a.order - b.order);
  return { hits: ranked.slice(0, limit).map((r) => r.entry), total: ranked.length };
}

/** True for Cmd+K (macOS) or Ctrl+K (other platforms), without Alt or Shift. */
export function isFindShortcut(event: Pick<KeyboardEvent, "key" | "metaKey" | "ctrlKey" | "altKey" | "shiftKey">): boolean {
  return event.key.toLowerCase() === "k" && (event.metaKey || event.ctrlKey) && !event.altKey && !event.shiftKey;
}

/** Calls `onToggle` for Cmd+K / Ctrl+K anywhere in the Estate shell. */
export function useEstateFindShortcut(onToggle: () => void): void {
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (!isFindShortcut(event)) return;
      event.preventDefault();
      onToggle();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onToggle]);
}
