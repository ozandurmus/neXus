import Box from "@mui/material/Box";
import Drawer from "@mui/material/Drawer";
import Typography from "@mui/material/Typography";
import { failoverExecutionDisabled, failoverVendorLabel } from "../screens/HaReadinessList";
import { readinessReasonLabel } from "../shell/presenters";
import { EvidenceStamp, StateBadge } from "./EstatePrimitives";
import type { FailoverBoardUnit, FailoverMatrixCell } from "./estateSlice2Model";
import { ESTATE_MONO, ESTATE_SANS, ev } from "./estateTokens";

/** Shared wording: pre-checks belong to the physical pair for a VSX chassis; each Virtual System is evaluated on its own. */
export const PER_UNIT_PRECHECK_NOTE =
  "Chassis pre-checks apply to the physical pair; each Virtual System is evaluated separately.";

/**
 * Shared wording (PO 2026-11-23): how a failover is started in the standard view. Estate stays read-only; this only
 * tells the reader what the standard-view link leads to, in the words that view uses ("Work log note").
 */
export const FAILOVER_START_NOTE =
  "A failover starts in the standard view, from the unit's row: one confirmation with a work log note. A second administrator is asked only where the role requires one.";

export interface FailoverCellTarget {
  readonly unitLabel: string;
  readonly unitKind: FailoverBoardUnit["unitKind"];
  readonly checkTitle: string;
  readonly cell: FailoverMatrixCell;
  readonly standardHref: string;
  readonly unit?: {
    readonly vendor?: string;
    readonly failoverRefusal?: string | null;
  };
  readonly vendor?: string;
  readonly failoverRefusal?: string | null;
}

/**
 * Read-only detail for one pre-check matrix cell. No action buttons: the only control is the
 * standard-view link.
 */
export function EstateFailoverCellDrawer({
  target,
  onClose,
}: {
  readonly target: FailoverCellTarget | null;
  readonly onClose: () => void;
}) {
  const open = target !== null;
  const headingText = target ? `${target.checkTitle} · ${target.unitLabel}` : "";
  const refusal = target?.unit?.failoverRefusal ?? target?.failoverRefusal ?? null;
  const vendor = target?.unit?.vendor ?? target?.vendor;
  const execDisabled = failoverExecutionDisabled(vendor);
  return (
    <Drawer
      anchor="right"
      open={open}
      onClose={onClose}
      PaperProps={{
        role: "dialog",
        "aria-label": headingText || "Pre-check detail",
        sx: { width: { xs: "100%", sm: 420 }, p: "20px", bgcolor: ev.surface, fontFamily: ESTATE_SANS },
      }}
    >
      {target ? (
        <Box data-failover-cell-drawer="" sx={{ display: "flex", flexDirection: "column", gap: "12px" }}>
          <Box>
            <Typography component="h2" sx={{ fontSize: "16px", fontWeight: 650, color: ev.ink }}>
              {target.checkTitle}
            </Typography>
            <Typography sx={{ fontFamily: ESTATE_MONO, fontSize: "12.5px", color: ev.ink2, mt: "2px" }}>
              {target.unitLabel}
            </Typography>
          </Box>

          <Box>
            <StateBadge state={target.cell.state} word={target.cell.word} />
          </Box>

          {target.unitKind !== "CLUSTER" ? (
            <Typography sx={{ fontSize: "12px", color: ev.ink3 }}>{PER_UNIT_PRECHECK_NOTE}</Typography>
          ) : null}

          {refusal || execDisabled ? (
            <Box data-testid="failover-cell-drawer-gate">
              <Typography sx={{ fontSize: "11px", letterSpacing: "0.06em", textTransform: "uppercase", color: ev.ink3, fontWeight: 650 }}>
                Failover gate
              </Typography>
              {refusal ? (
                <Typography sx={{ fontSize: "12.5px", color: ev.ink2 }}>
                  {readinessReasonLabel(refusal)}
                </Typography>
              ) : null}
              {execDisabled ? (
                <Typography sx={{ fontSize: "12.5px", color: ev.ink2 }}>
                  {`Failover execution not enabled for ${failoverVendorLabel(vendor)}`}
                </Typography>
              ) : null}
            </Box>
          ) : null}

          <Box>
            <Typography sx={{ fontSize: "11px", letterSpacing: "0.06em", textTransform: "uppercase", color: ev.ink3, fontWeight: 650 }}>
              Summary
            </Typography>
            <Typography sx={{ fontSize: "13px", color: ev.ink, whiteSpace: "pre-wrap", wordBreak: "break-word" }}>
              {target.cell.summary || "UNKNOWN: no summary recorded"}
            </Typography>
          </Box>

          <Box>
            <Typography sx={{ fontSize: "11px", letterSpacing: "0.06em", textTransform: "uppercase", color: ev.ink3, fontWeight: 650 }}>
              Evaluated
            </Typography>
            <EvidenceStamp
              at={target.cell.observedAt ?? null}
              source="evaluated"
              missingLabel="no evaluation time recorded"
            />
          </Box>

          {!refusal && !execDisabled ? (
            <Typography data-testid="failover-cell-drawer-start-note" sx={{ mt: "auto", fontSize: "12px", color: ev.ink3 }}>
              {FAILOVER_START_NOTE}
            </Typography>
          ) : null}

          <Box
            component="a"
            href={target.standardHref}
            sx={{ mt: refusal || execDisabled ? "auto" : 0, color: ev.accent, fontWeight: 600, fontSize: "13px", textDecoration: "none", "&:hover": { textDecoration: "underline" } }}
          >
            Open in standard view →
          </Box>
        </Box>
      ) : null}
    </Drawer>
  );
}
