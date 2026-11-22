import { useEffect, useMemo, useState, type KeyboardEvent } from "react";
import Box from "@mui/material/Box";
import Dialog from "@mui/material/Dialog";
import InputBase from "@mui/material/InputBase";
import Typography from "@mui/material/Typography";
import { navigateTo } from "../shell/urlParams";
import { ESTATE_MONO, ESTATE_SANS, ev } from "./estateTokens";
import type { EstateCluster, EstateUnit } from "./estateModel";
import { buildEstateFindIndex, ESTATE_FIND_LIMIT, findEstateEntries } from "./estateFind";

const LISTBOX_ID = "estate-find-listbox";
const optionId = (index: number) => `estate-find-option-${index}`;

export interface EstateFindPaletteProps {
  readonly open: boolean;
  readonly onClose: () => void;
  readonly units: readonly EstateUnit[];
  readonly clusters: readonly EstateCluster[];
}

/**
 * Cmd/Ctrl+K find for the Estate shell. Searches the units and HA clusters already loaded for the Estate view plus the
 * Estate screens by name. Enter opens the highlighted result; Escape closes (MUI Dialog). Names are shown as the
 * Estate model holds them, so masked names stay masked.
 */
export function EstateFindPalette({ open, onClose, units, clusters }: EstateFindPaletteProps) {
  const [query, setQuery] = useState("");
  const [active, setActive] = useState(0);

  const index = useMemo(() => buildEstateFindIndex(units, clusters), [units, clusters]);
  const result = useMemo(() => findEstateEntries(index, query, ESTATE_FIND_LIMIT), [index, query]);
  const hits = result.hits;
  const extra = result.total - hits.length;

  useEffect(() => {
    if (open) {
      setQuery("");
      setActive(0);
    }
  }, [open]);

  useEffect(() => {
    setActive(0);
  }, [query]);

  const choose = (position: number) => {
    const hit = hits[position];
    if (!hit) return;
    onClose();
    navigateTo(hit.href, "push");
  };

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === "ArrowDown") {
      event.preventDefault();
      if (hits.length > 0) setActive((i) => (i + 1) % hits.length);
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      if (hits.length > 0) setActive((i) => (i - 1 + hits.length) % hits.length);
    } else if (event.key === "Enter") {
      event.preventDefault();
      choose(active);
    }
  };

  const status =
    result.total === 0
      ? query.trim()
        ? "No matches. Try a unit name, a cluster or a screen."
        : units.length === 0 && clusters.length === 0
          ? "Estate data is still loading."
          : ""
      : `${result.total} result${result.total === 1 ? "" : "s"}`;

  const activeId = hits.length > 0 ? optionId(active) : undefined;

  return (
    <Dialog
      open={open}
      onClose={onClose}
      fullWidth
      maxWidth="sm"
      aria-labelledby="estate-find-title"
      data-testid="estate-find-palette"
      PaperProps={{
        sx: { bgcolor: ev.surface, color: ev.ink, fontFamily: ESTATE_SANS, borderRadius: "12px", backgroundImage: "none" },
      }}
    >
      <Typography id="estate-find-title" sx={{ position: "absolute", width: 1, height: 1, overflow: "hidden", clip: "rect(0 0 0 0)" }}>
        Find in the estate
      </Typography>
      <Box sx={{ p: 2, display: "flex", flexDirection: "column", gap: 1 }} onKeyDown={onKeyDown}>
        <InputBase
          autoFocus
          fullWidth
          placeholder="Find a unit, cluster or screen"
          inputProps={{
            role: "combobox",
            "aria-label": "Find a unit, cluster or screen",
            "aria-expanded": hits.length > 0,
            "aria-controls": LISTBOX_ID,
            "aria-autocomplete": "list",
            "aria-activedescendant": activeId,
            "data-testid": "estate-find-input",
            autoComplete: "off",
            spellCheck: false,
          }}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          sx={{
            border: `1px solid ${ev.line}`,
            borderRadius: "8px",
            px: "12px",
            py: "6px",
            fontSize: "15px",
            color: ev.ink,
          }}
        />
        <Box
          id={LISTBOX_ID}
          role="listbox"
          aria-label="Estate find results"
          data-testid="estate-find-listbox"
          sx={{ maxHeight: "50vh", overflowY: "auto", display: hits.length > 0 ? "block" : "none" }}
        >
          {hits.map((hit, i) => {
            const selected = i === active;
            return (
              <Box
                key={hit.key}
                id={optionId(i)}
                role="option"
                aria-selected={selected}
                data-testid="estate-find-option"
                onMouseEnter={() => setActive(i)}
                onClick={() => choose(i)}
                sx={{
                  display: "flex",
                  flexDirection: "column",
                  gap: "2px",
                  px: "12px",
                  py: "8px",
                  borderRadius: "8px",
                  cursor: "pointer",
                  bgcolor: selected ? ev.surface2 : "transparent",
                  outline: selected ? `2px solid ${ev.accent}` : "none",
                  outlineOffset: "-2px",
                }}
              >
                <Typography component="span" sx={{ fontWeight: 600, fontSize: "14.5px", color: ev.ink }}>
                  {hit.label}
                </Typography>
                <Typography component="span" sx={{ fontFamily: ESTATE_MONO, fontSize: "12px", color: ev.ink3 }}>
                  {hit.sub}
                </Typography>
              </Box>
            );
          })}
        </Box>
        {extra > 0 ? (
          <Typography sx={{ fontSize: "12.5px", color: ev.ink3 }} data-testid="estate-find-more">
            +{extra} more · refine the search
          </Typography>
        ) : null}
        <Typography role="status" aria-live="polite" sx={{ fontSize: "12.5px", color: ev.ink3, minHeight: "1.2em" }}>
          {status}
        </Typography>
        <Typography sx={{ fontSize: "11.5px", color: ev.ink3 }}>
          ↑ ↓ move · Enter open · Esc close
        </Typography>
      </Box>
    </Dialog>
  );
}
