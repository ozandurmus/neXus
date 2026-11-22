import type { MouseEvent, ReactNode } from "react";
import Box from "@mui/material/Box";
import Tooltip from "@mui/material/Tooltip";
import { formatStamp, relativeAge } from "../shell/time";
import {
  ageBucketOf,
  BADGE_WORD_DEFAULT,
  type BadgeState,
  type EstateCluster,
  type EstateUnit,
  type TileState,
} from "./estateModel";
import { dossierHref, type EstateLens } from "./estateRouting";
import {
  ESTATE_BADGE_UNKNOWN_HATCH,
  ESTATE_MONO,
  ESTATE_SANS,
  ESTATE_UNKNOWN_HATCH,
  ev,
} from "./estateTokens";

/**
 * State badge: geometric shape + explicit state word (never colour alone).
 * - `act`: solid square
 * - `watch`: solid triangle
 * - `ok`: solid circle
 * - `unk`: hatched fill + dashed square outline + "UNKNOWN"
 * - `na`: hollow circle + "Not applicable"
 */
export function StateBadge({
  state,
  word,
  dense = false,
}: {
  readonly state: BadgeState;
  readonly word?: string;
  readonly dense?: boolean;
}) {
  const label = word ?? BADGE_WORD_DEFAULT[state];
  const palette: Record<BadgeState, { fg: string; bg: string; hatched?: boolean }> = {
    act: { fg: ev.act, bg: ev.actBg },
    watch: { fg: ev.watch, bg: ev.watchBg },
    ok: { fg: ev.ok, bg: ev.okBg },
    unk: { fg: ev.unk, bg: ev.unkBg, hatched: true },
    na: { fg: ev.ink3, bg: ev.surface2 },
  };
  const p = palette[state];

  const shape = (() => {
    if (state === "act") {
      return <Box component="i" aria-hidden sx={{ width: 9, height: 9, bgcolor: p.fg, display: "inline-block", flexShrink: 0 }} />;
    }
    if (state === "watch") {
      return (
        <Box
          component="i"
          aria-hidden
          sx={{
            width: 9,
            height: 9,
            bgcolor: p.fg,
            clipPath: "polygon(50% 0, 100% 100%, 0 100%)",
            display: "inline-block",
            flexShrink: 0,
          }}
        />
      );
    }
    if (state === "ok") {
      return (
        <Box
          component="i"
          aria-hidden
          sx={{ width: 9, height: 9, bgcolor: p.fg, borderRadius: "50%", display: "inline-block", flexShrink: 0 }}
        />
      );
    }
    if (state === "unk") {
      return (
        <Box
          component="i"
          aria-hidden
          sx={{
            width: 10,
            height: 10,
            border: `2px dashed ${p.fg}`,
            borderRadius: "2px",
            display: "inline-block",
            flexShrink: 0,
          }}
        />
      );
    }
    return (
      <Box
        component="i"
        aria-hidden
        sx={{
          width: 9,
          height: 9,
          border: `1.5px solid ${p.fg}`,
          borderRadius: "50%",
          display: "inline-block",
          flexShrink: 0,
        }}
      />
    );
  })();

  return (
    <Box
      component="span"
      data-state-badge={state}
      data-hatched={p.hatched ? "true" : undefined}
      sx={{
        display: "inline-flex",
        alignItems: "center",
        gap: "6px",
        fontFamily: ESTATE_SANS,
        fontWeight: 620,
        fontSize: dense ? "11.5px" : "12.5px",
        lineHeight: 1.45,
        px: dense ? "6px" : "8px",
        py: dense ? "1px" : "2px",
        borderRadius: "6px",
        whiteSpace: "nowrap",
        color: p.fg,
        bgcolor: p.bg,
        backgroundImage: p.hatched ? ESTATE_BADGE_UNKNOWN_HATCH : "none",
        border: state === "unk" ? `1px dashed ${ev.unk}` : "1px solid transparent",
      }}
    >
      {shape}
      <span>{label}</span>
    </Box>
  );
}

/**
 * Evidence age stamp: four-bar stepped age meter + relative age + optional source word.
 * Every figure in the Estate view carries one; absent timestamps render as `a0` (dashed hollow bars + `missingLabel`).
 */
export function EvidenceStamp({
  at,
  source,
  now = new Date(),
  missingLabel = "UNKNOWN",
}: {
  readonly at: string | null | undefined;
  readonly source?: string;
  readonly now?: Date;
  readonly missingLabel?: string;
}) {
  const bucket = ageBucketOf(at, now);
  const stampClass =
    bucket === "never"
      ? "a0"
      : bucket === "fresh"
        ? "a1"
        : bucket === "day"
          ? "a2"
          : bucket === "old"
            ? "a3"
            : "a4";
  const litBars =
    stampClass === "a1" ? 4 : stampClass === "a2" ? 3 : stampClass === "a3" ? 2 : stampClass === "a4" ? 1 : 0;
  const barFill =
    stampClass === "a1"
      ? ev.age1
      : stampClass === "a2"
        ? ev.age2
        : stampClass === "a3"
          ? ev.age3
          : ev.age4;
  const text = stampClass === "a0" || !at ? missingLabel : relativeAge(at, now).replace(/\s+ago$/, "");
  const titleText =
    stampClass === "a0" || !at
      ? `Evidence age: ${missingLabel}${source ? ` (${source})` : ""}`
      : `Evidence age: ${relativeAge(at, now)} · ${formatStamp(at)}${source ? ` · source: ${source}` : ""}`;

  const heights = [5, 7, 9, 11];
  return (
    <Tooltip title={titleText} describeChild>
      <Box
        component="span"
        data-evidence-stamp={stampClass}
        sx={{
          display: "inline-flex",
          alignItems: "center",
          gap: "6px",
          fontFamily: ESTATE_MONO,
          fontSize: "12px",
          color: stampClass === "a0" ? ev.unk : ev.ink2,
          whiteSpace: "nowrap",
        }}
      >
        <Box component="span" aria-hidden sx={{ display: "inline-flex", gap: "1.5px", alignItems: "flex-end" }}>
          {heights.map((h, idx) => {
            const lit = idx < litBars;
            return (
              <Box
                component="b"
                key={h}
                sx={{
                  width: "3px",
                  height: `${h}px`,
                  borderRadius: "1px",
                  display: "inline-block",
                  bgcolor: stampClass === "a0" ? "transparent" : lit ? barFill : ev.lineStrong,
                  border: stampClass === "a0" ? `1px dashed ${ev.unk}` : "none",
                }}
              />
            );
          })}
        </Box>
        <span>{text}</span>
        {source ? (
          <Box component="span" sx={{ fontFamily: ESTATE_SANS, fontSize: "11.5px", color: ev.ink3 }}>
            {source}
          </Box>
        ) : null}
      </Box>
    </Tooltip>
  );
}

function tileStyle(state: TileState, size: number) {
  const base = {
    width: size,
    height: size,
    borderRadius: size <= 16 ? "3px" : "4px",
    display: "inline-grid",
    placeItems: "center",
    fontFamily: ESTATE_MONO,
    fontWeight: 700,
    fontSize: size <= 16 ? "9.5px" : "11.5px",
    lineHeight: 1,
    color: ev.tGlyph,
    position: "relative" as const,
    flexShrink: 0,
    boxSizing: "border-box" as const,
    padding: 0,
    border: "none",
  };
  if (state === "unk") {
    return {
      ...base,
      backgroundImage: ESTATE_UNKNOWN_HATCH,
      border: `1px solid ${ev.tUnk}`,
      color: ev.surface,
    };
  }
  if (state === "na") {
    return {
      ...base,
      bgcolor: "transparent",
      border: `1.5px dashed ${ev.tNa}`,
      color: ev.ink3,
    };
  }
  const bgMap: Record<Exclude<TileState, "unk" | "na">, string> = {
    act: ev.tAct,
    watch: ev.tWatch,
    ok: ev.tOk,
    fresh: ev.age1,
    day: ev.age2,
    old: ev.age3,
    stale: ev.age4,
  };
  return {
    ...base,
    bgcolor: bgMap[state],
  };
}

function tileGlyph(state: TileState): string | null {
  if (state === "act" || state === "stale") return "!";
  if (state === "watch") return "~";
  if (state === "old") return "·";
  if (state === "unk") return "?";
  return null;
}

/** Non-interactive swatch tile for legends and inline summaries. */
export function TileSwatch({ state, size = 16, label }: { readonly state: TileState; readonly size?: number; readonly label?: string }) {
  const glyph = tileGlyph(state);
  return (
    <Box
      component="span"
      role={label ? "img" : undefined}
      aria-label={label}
      data-tile-state={state}
      data-hatched={state === "unk" ? "true" : undefined}
      sx={tileStyle(state, size)}
    >
      {glyph ? (
        <Box
          component="span"
          aria-hidden
          sx={
            state === "unk"
              ? {
                  bgcolor: ev.tUnk,
                  color: ev.surface,
                  borderRadius: "2px",
                  px: "2px",
                  fontSize: size <= 16 ? "9px" : "10px",
                  fontWeight: 800,
                  lineHeight: 1.15,
                }
              : undefined
          }
        >
          {glyph}
        </Box>
      ) : null}
    </Box>
  );
}

/** Interactive unit tile on the Estate wall or Brief scope row. */
export function UnitTile({
  unit,
  lens,
  size = 22,
  selected = false,
  dimmed = false,
  now = new Date(),
  onSelect,
  onHover,
}: {
  readonly unit: EstateUnit;
  readonly lens: EstateLens;
  readonly size?: number;
  readonly selected?: boolean;
  readonly dimmed?: boolean;
  readonly now?: Date;
  readonly onSelect?: (unit: EstateUnit, e: MouseEvent<HTMLElement>) => void;
  readonly onHover?: (unit: EstateUnit) => void;
}) {
  const answer = unit.lenses[lens];
  const glyph = tileGlyph(answer.tileState);
  // Age of THIS lens's evidence (e.g. the stored archive on Backups), never the inventory read age.
  // "never read" is an inventory statement, so it appears only under the inventory (age) lens; any other lens without
  // its own evidence time says unknown and never borrows the inventory read age or its absence.
  const ageText = answer.evidenceAt
    ? `${answer.evidenceSource} evidence ${relativeAge(answer.evidenceAt, now)}`
    : lens === "age"
      ? "never read"
      : `${answer.evidenceSource} evidence time unknown`;
  const roleDesc = unit.clusterRef
    ? `${unit.clusterRef}${unit.haRole ? ` · ${unit.haRole.toLowerCase()}` : ""}`
    : unit.role.replace("_", " ");
  const ariaLabel = `${unit.name}: ${answer.summary} (${ageText})`;

  const tooltip = (
    <Box sx={{ fontSize: 12, lineHeight: 1.4 }}>
      <Box sx={{ fontFamily: ESTATE_MONO, fontWeight: 700 }}>{unit.name}</Box>
      <Box>
        {unit.vendorName} · {roleDesc}
      </Box>
      <Box>{answer.summary}</Box>
      <Box sx={{ opacity: 0.8 }}>{ageText}</Box>
    </Box>
  );

  const sx = {
    ...tileStyle(answer.tileState, size),
    cursor: "pointer",
    textDecoration: "none",
    opacity: dimmed ? 0.25 : 1,
    outline: selected ? `2px solid ${ev.ink}` : "none",
    outlineOffset: selected ? "1px" : 0,
    zIndex: selected ? 2 : "auto",
    "&:hover": {
      outline: `2px solid ${ev.ink}`,
      outlineOffset: "1px",
      zIndex: 2,
      textDecoration: "none",
    },
    "&:focus-visible": {
      outline: `2px solid ${ev.accent}`,
      outlineOffset: "2px",
      zIndex: 2,
    },
  } as const;

  const inner = glyph ? (
    <Box
      component="span"
      aria-hidden
      sx={
        answer.tileState === "unk"
          ? {
              bgcolor: ev.tUnk,
              color: ev.surface,
              borderRadius: "2px",
              px: "2px",
              fontSize: size <= 16 ? "9px" : "10px",
              fontWeight: 800,
              lineHeight: 1.15,
            }
          : undefined
      }
    >
      {glyph}
    </Box>
  ) : null;

  return (
    <Tooltip title={tooltip} disableInteractive>
      <Box
        component="a"
        href={dossierHref(unit.deviceId)}
        aria-label={ariaLabel}
        data-unit-tile={unit.name}
        data-unit-id={unit.deviceId}
        data-tile-state={answer.tileState}
        data-dimmed={dimmed ? "true" : "false"}
        data-hatched={answer.tileState === "unk" ? "true" : undefined}
        onMouseEnter={onHover ? () => onHover(unit) : undefined}
        onFocus={onHover ? () => onHover(unit) : undefined}
        onClick={
          onSelect
            ? (e: MouseEvent<HTMLElement>) => {
                if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey || e.button !== 0) return;
                onSelect(unit, e);
              }
            : undefined
        }
        sx={sx}
      >
        {inner}
      </Box>
    </Tooltip>
  );
}

/**
 * Joined HA pair / cluster tiles: members sit side-by-side in one `.pair` pill connected by a link bar.
 * When failover readiness is NOT_READY (`act`), the link is drawn as a dashed red cut line; when UNKNOWN (`unk`),
 * a dashed purple line. Single-endpoint clusters (e.g. a single-address FortiGate pair) show their enrolled unit
 * joined to a dashed peer marker so the pair topology stays visible without fabricating a device ID.
 */
export function HaPairTiles({
  cluster,
  lens,
  size = 22,
  selectedId = null,
  dimmedIds,
  now = new Date(),
  onSelect,
  onHover,
}: {
  readonly cluster: EstateCluster;
  readonly lens: EstateLens;
  readonly size?: number;
  readonly selectedId?: string | null;
  readonly dimmedIds?: ReadonlySet<string>;
  readonly now?: Date;
  readonly onSelect?: (unit: EstateUnit, e: MouseEvent<HTMLElement>) => void;
  readonly onHover?: (unit: EstateUnit) => void;
}) {
  const linkKind = cluster.pairState === "act" ? "cut" : cluster.pairState === "unk" ? "unknown" : "intact";
  const linkBg =
    linkKind === "cut"
      ? `repeating-linear-gradient(90deg, ${ev.tAct} 0 2px, transparent 2px 4px)`
      : linkKind === "unknown"
        ? `repeating-linear-gradient(90deg, ${ev.tUnk} 0 2px, transparent 2px 4px)`
        : ev.lineStrong;

  return (
    <Box
      component="span"
      data-ha-pair={cluster.ref}
      data-pair-state={cluster.pairState}
      title={`${cluster.title} (${cluster.vendorName})`}
      sx={{
        display: "inline-flex",
        alignItems: "center",
        p: "2px",
        borderRadius: "6px",
        bgcolor: ev.surface2,
        border: `1px solid ${cluster.pairState === "act" ? ev.act : ev.line}`,
        gap: 0,
      }}
    >
      {cluster.members.map((member, index) => (
        <Box component="span" key={member.deviceId} sx={{ display: "inline-flex", alignItems: "center" }}>
          {index > 0 ? (
            <Box
              component="span"
              aria-hidden
              data-pair-link={linkKind}
              sx={{ width: "6px", height: "3px", background: linkBg, display: "inline-block" }}
            />
          ) : null}
          <UnitTile
            unit={member}
            lens={lens}
            size={size}
            selected={selectedId === member.deviceId}
            dimmed={dimmedIds?.has(member.deviceId) ?? false}
            now={now}
            onSelect={onSelect}
            onHover={onHover}
          />
        </Box>
      ))}
      {cluster.members.length === 1 ? (
        <>
          <Box
            component="span"
            aria-hidden
            data-pair-link={linkKind}
            sx={{ width: "6px", height: "3px", background: linkBg, display: "inline-block" }}
          />
          <Tooltip title={`${cluster.title}: HA peer observed via ${cluster.members[0].name}`}>
            <Box
              component="span"
              role="img"
              aria-label={`${cluster.title}: HA peer`}
              sx={{
                width: size,
                height: size,
                borderRadius: size <= 16 ? "3px" : "4px",
                border: `1.5px dashed ${ev.lineStrong}`,
                display: "inline-grid",
                placeItems: "center",
                fontFamily: ESTATE_MONO,
                fontSize: "9px",
                color: ev.ink3,
              }}
            >
              P
            </Box>
          </Tooltip>
        </>
      ) : null}
    </Box>
  );
}

export interface HonestBarSegment {
  readonly key: string;
  readonly label: string;
  readonly count: number;
  readonly state: TileState;
}

/** Stacked proportional bar where UNKNOWN segments are hatched and never folded into Fine or Act. */
export function HonestBar({
  segments,
  height = 12,
  ariaLabel,
}: {
  readonly segments: readonly HonestBarSegment[];
  readonly height?: number;
  readonly ariaLabel: string;
}) {
  const total = segments.reduce((sum, s) => sum + s.count, 0);
  if (total <= 0) return null;
  const fillFor = (state: TileState) => {
    if (state === "unk") return { backgroundImage: ESTATE_UNKNOWN_HATCH, border: `1px solid ${ev.tUnk}` };
    if (state === "act" || state === "stale") return { bgcolor: ev.tAct };
    if (state === "watch" || state === "old") return { bgcolor: ev.tWatch };
    if (state === "day") return { bgcolor: ev.age2 };
    if (state === "na") return { bgcolor: ev.tNa };
    return { bgcolor: ev.tOk };
  };

  return (
    <Box
      role="img"
      aria-label={`${ariaLabel}: ${segments
        .filter((s) => s.count > 0)
        .map((s) => `${s.label} ${s.count}`)
        .join(", ")}`}
      sx={{
        display: "flex",
        height,
        borderRadius: "4px",
        overflow: "hidden",
        bgcolor: ev.surface2,
        gap: "2px",
      }}
    >
      {segments
        .filter((s) => s.count > 0)
        .map((s) => (
          <Tooltip key={s.key} title={`${s.label}: ${s.count}`}>
            <Box
              component="span"
              data-bar-segment={s.key}
              data-hatched={s.state === "unk" ? "true" : undefined}
              sx={{
                display: "block",
                height: "100%",
                width: `${(s.count / total) * 100}%`,
                minWidth: "4px",
                ...fillFor(s.state),
              }}
            />
          </Tooltip>
        ))}
    </Box>
  );
}

export function VendorTag({ label }: { readonly label: string }) {
  return (
    <Box
      component="span"
      sx={{
        fontFamily: ESTATE_MONO,
        fontWeight: 650,
        fontSize: "10.5px",
        letterSpacing: "0.03em",
        px: "5px",
        py: "1px",
        borderRadius: "4px",
        border: `1px solid ${ev.lineStrong}`,
        color: ev.ink2,
        whiteSpace: "nowrap",
      }}
    >
      {label}
    </Box>
  );
}

export function EstateCard({
  children,
  sx,
}: {
  readonly children: ReactNode;
  readonly sx?: Record<string, unknown>;
}) {
  return (
    <Box
      component="section"
      sx={{
        bgcolor: ev.surface,
        border: `1px solid ${ev.line}`,
        borderRadius: "12px",
        boxShadow: ev.shadow,
        color: ev.ink,
        ...sx,
      }}
    >
      {children}
    </Box>
  );
}

/**
 * "+N more" footer for a capped list. `total` is the true count (not the page
 * size); renders nothing when nothing is hidden.
 */
export function MoreFooter({
  shown,
  total,
  noun,
  testId,
}: {
  readonly shown: number;
  readonly total: number;
  readonly noun: string;
  readonly testId?: string;
}) {
  if (total <= shown) return null;
  return (
    <Box component="span" data-testid={testId} sx={{ fontSize: "12px", color: ev.ink3, ml: "4px" }}>
      {`+${total - shown} more ${noun}`}
    </Box>
  );
}
