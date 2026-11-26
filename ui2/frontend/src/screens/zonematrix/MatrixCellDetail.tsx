import { useEffect, useState } from "react";
import Box from "@mui/material/Box";
import Dialog from "@mui/material/Dialog";
import DialogContent from "@mui/material/DialogContent";
import MenuItem from "@mui/material/MenuItem";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import { DataTable, type DataColumn } from "../../shell/DataTable";
import { M3Button } from "../../shell/M3Widgets";
import { Ts } from "../../shell/States";
import { actorLabel } from "../../shell/presenters";
import { m3 } from "../../theme/m3Theme";
import { TYPE } from "../../theme/tokens";
import type { ZoneNotice } from "./ZoneMatrixScreen";
import { headlineProven } from "./ViolationsTab";
import { Hash, Hint, SectionTitle, isKnownStatement, knownSeverityWord, knownStatementWord, mono, panelSx } from "./zoneMatrixUi";
import {
  MATRIX_SEVERITIES, MATRIX_STATEMENTS, clearMatrixCell, confirmCellReference, getZoneMatrixTemplate,
  importMatrixTemplate, referenceStatusWord, setMatrixCell, zoneErrorCode,
  zoneErrorLines, type MatrixCellView, type MatrixSeverity, type MatrixStatement, type TemplateRow,
  type ZoneMatrixSummaryResponse, type ZoneMatrixTemplateResponse, type ZoneMatrixView,
} from "./zoneMatrixApi";

export const RESET_CONFIRMATION_NOTE =
  "Changing the statement, services, severity or reference resets a confirmed reference to proposed.";
export const CONFIRM_PARTICIPANT_NOTE =
  "Confirming makes you an editor of this draft; another person must publish it.";
export const HIDDEN_MASKED_TEXT = "hidden in the masked view";

export function StatementShape({ statement }: { readonly statement: MatrixStatement | string | null | undefined }) {
  const shape = statement === "DENY_ALL" ? "closed-bar"
    : statement === "ALLOW_LISTED" ? "list-mark"
    : statement === "ALLOW_ANY" ? "open-mark"
    : "unknown-mark";
  const symbol = statement === "DENY_ALL" ? "\u25AC"
    : statement === "ALLOW_LISTED" ? "\u2261"
    : statement === "ALLOW_ANY" ? "\u25C7"
    : "?";
  return (
    <Box component="span" aria-hidden="true" data-shape={shape} sx={{ fontWeight: 700, mr: 0.5 }}>
      {symbol}
    </Box>
  );
}

const trimLines = (text: string): string[] =>
  text.split("\n").map((line) => line.trim()).filter((line) => line !== "");

/** One cell's open-violation line for the active version: what it says and whether it is a count that is bad. */
export interface OpenViolationsLine {
  readonly text: string;
  /** `bad` only for a positive count; every unproven or unknown statement is `neutral`, never green. */
  readonly tone: "bad" | "neutral";
}

/**
 * The open-violation count of one pair of the version being viewed, from the stored summary (contract 7.1, 7.2). A count
 * is a lower bound unless the server says it is exact and proven; zero is printed only when the headline is proven and the
 * list is not capped; a pair missing from a capped list is "not in top N cells", never 0.
 */
export function openViolationsLine(
  data: ZoneMatrixSummaryResponse | null, viewedVersionId: string, sourceId: string, destinationId: string,
): OpenViolationsLine {
  if (data === null || data.state !== "READY" || data.active_version?.version_id !== viewedVersionId) {
    return { text: "Open violations: UNKNOWN (stored summary not available for this version)", tone: "neutral" };
  }
  const counts = data.cell_counts;
  if (!counts) return { text: "Open violations: UNKNOWN (no per-cell counts stored)", tone: "neutral" };
  const exact = data.cell_counts_exact === true;
  const proven = exact && data.headline !== null && headlineProven(data, data.headline);
  const entry = counts.find((c) => c.source_zone_id === sourceId && c.destination_zone_id === destinationId);
  if (entry && (entry.headline_rules > 0 || entry.outside_headline_rules > 0)) {
    const n = entry.headline_rules;
    const base = `${proven ? `${n}` : `at least ${n}`} open violation${n === 1 ? "" : "s"}`;
    const outside = entry.outside_headline_rules > 0 ? ` +${entry.outside_headline_rules} outside headline` : "";
    return { text: `${base}${outside}`, tone: "bad" };
  }
  if (data.cell_counts_capped === true) {
    return { text: `not in top ${data.cell_counts_returned ?? counts.length} cells`, tone: "neutral" };
  }
  if (proven) return { text: "0 open violations", tone: "neutral" };
  return { text: "at least 0 open violations (coverage incomplete)", tone: "neutral" };
}

/** Said above the active grid when the per-cell list is capped. */
export function cellCountsCapNote(data: ZoneMatrixSummaryResponse | null): string | null {
  if (data === null || data.cell_counts_capped !== true) return null;
  return `Per-cell counts capped at ${data.cell_counts_returned ?? 0} of ${data.cell_counts_total ?? 0} violating cells (+${data.cell_counts_more ?? 0} more).`;
}

export interface SelectedPair {
  readonly sourceId: string;
  readonly sourceName: string;
  readonly destinationId: string;
  readonly destinationName: string;
}

export function CellDetailPanel({
  pair, explicitCell, matrix, isMasked, editable, canConfirm, revision, onRefresh, onChanged, notify,
  openViolations = null, onOpenViolations = null,
}: {
  readonly pair: SelectedPair;
  readonly explicitCell: MatrixCellView | null;
  readonly matrix: ZoneMatrixView;
  readonly isMasked: boolean;
  readonly editable: boolean;
  readonly canConfirm: boolean;
  readonly revision: number | null;
  readonly onRefresh: () => void;
  readonly onChanged: () => void;
  readonly notify: (notice: ZoneNotice | null) => void;
  /** The pair's open-violation line; `null` unless the active version is shown. */
  readonly openViolations?: OpenViolationsLine | null;
  /** Opens the Violations tab filtered to this pair; `null` unless the active version is shown. */
  readonly onOpenViolations?: (() => void) | null;
}) {
  const isUnassignedSelf = pair.sourceId === "UNASSIGNED" && pair.destinationId === "UNASSIGNED";
  const effectiveStatement: MatrixStatement = explicitCell
    ? explicitCell.statement
    : (isUnassignedSelf ? "DENY_ALL" : matrix.default_statement);
  const effectiveSeverity: MatrixSeverity = explicitCell ? explicitCell.severity : matrix.default_severity;

  const [statement, setStatement] = useState<MatrixStatement>(isKnownStatement(effectiveStatement) ? effectiveStatement : "DENY_ALL");
  const [severity, setSeverity] = useState<MatrixSeverity>(MATRIX_SEVERITIES.some((s) => s.value === effectiveSeverity) ? effectiveSeverity : "MEDIUM");
  const [servicesText, setServicesText] = useState((explicitCell?.services ?? []).map((s) => s.entry).join("\n"));
  const [rationale, setRationale] = useState(explicitCell?.rationale ?? "");
  const [reference, setReference] = useState(explicitCell?.regulation_reference ?? "");
  const [busy, setBusy] = useState(false);
  const [errors, setErrors] = useState<readonly string[]>([]);

  useEffect(() => {
    setStatement(isKnownStatement(effectiveStatement) ? effectiveStatement : "DENY_ALL");
    setSeverity(MATRIX_SEVERITIES.some((s) => s.value === effectiveSeverity) ? effectiveSeverity : "MEDIUM");
    setServicesText((explicitCell?.services ?? []).map((s) => s.entry).join("\n"));
    setRationale(explicitCell?.rationale ?? "");
    setReference(explicitCell?.regulation_reference ?? "");
    setErrors([]);
  }, [pair.sourceId, pair.destinationId, explicitCell, effectiveStatement, effectiveSeverity]);

  const maskedCell = isMasked || explicitCell?.regulation_reference === null;
  const rationaleDisplay = explicitCell
    ? (explicitCell.rationale !== null ? explicitCell.rationale : (maskedCell ? HIDDEN_MASKED_TEXT : "None"))
    : "None";
  const referenceDisplay = explicitCell
    ? (explicitCell.regulation_reference === null
      ? HIDDEN_MASKED_TEXT
      : (explicitCell.regulation_reference || "None"))
    : "None";
  const statusDisplay = explicitCell ? explicitCell.reference_status : "EMPTY";

  const saveCell = async () => {
    const sent = statement === "ALLOW_LISTED" ? trimLines(servicesText) : [];
    setBusy(true); setErrors([]); notify(null);
    try {
      await setMatrixCell(revision, {
        source: pair.sourceId,
        destination: pair.destinationId,
        statement,
        services: sent,
        severity,
        rationale: rationale.trim() === "" ? null : rationale.trim(),
        regulation_reference: reference.trim(),
      });
      notify({ kind: "done", lines: [`Cell ${pair.sourceName} -> ${pair.destinationName} saved to the draft.`] });
      onRefresh();
    } catch (err) {
      setErrors(zoneErrorLines(err, sent));
      if (zoneErrorCode(err) === "REQUEST_CHANGED") onChanged();
    } finally {
      setBusy(false);
    }
  };

  const clearCell = async () => {
    setBusy(true); setErrors([]); notify(null);
    try {
      await clearMatrixCell(revision, pair.sourceId, pair.destinationId);
      notify({ kind: "done", lines: [`Cell ${pair.sourceName} -> ${pair.destinationName} now uses the version default.`] });
      onRefresh();
    } catch (err) {
      setErrors(zoneErrorLines(err));
      if (zoneErrorCode(err) === "REQUEST_CHANGED") onChanged();
    } finally {
      setBusy(false);
    }
  };

  const confirmRef = async () => {
    if (!explicitCell) return;
    setBusy(true); setErrors([]); notify(null);
    try {
      await confirmCellReference(revision, pair.sourceId, pair.destinationId, explicitCell.cell_sha256);
      notify({ kind: "done", lines: [`Reference confirmed for ${pair.sourceName} -> ${pair.destinationName}.`] });
      onRefresh();
    } catch (err) {
      setErrors(zoneErrorLines(err));
      if (zoneErrorCode(err) === "REQUEST_CHANGED") onChanged();
    } finally {
      setBusy(false);
    }
  };

  return (
    <Box role="region" aria-label="Cell detail" data-testid="cell-detail" sx={panelSx}>
      <SectionTitle note={explicitCell ? "Explicit cell" : (isUnassignedSelf ? `Default cell (${matrix.unassigned_to_unassigned})` : "Default cell (follows the version default)")}>
        {pair.sourceName} {"->"} {pair.destinationName}
      </SectionTitle>
      <Stack direction="row" gap={2} flexWrap="wrap">
        <Typography variant="body2">
          Statement: <StatementShape statement={effectiveStatement} />
          <strong>{knownStatementWord(effectiveStatement)}</strong> ({isKnownStatement(effectiveStatement) ? effectiveStatement : "UNKNOWN"})
        </Typography>
        <Typography variant="body2">
          Severity: <strong>{knownSeverityWord(effectiveSeverity)}</strong> ({MATRIX_SEVERITIES.some((s) => s.value === effectiveSeverity) ? effectiveSeverity : "UNKNOWN"})
        </Typography>
        <Typography variant="body2">Cell mode: <strong>{explicitCell ? "Explicit" : "Default"}</strong></Typography>
      </Stack>

      {openViolations ? (
        <Stack direction="row" gap={1.5} alignItems="center" flexWrap="wrap" data-testid="cell-open-violations">
          <Typography variant="body2" sx={{ color: openViolations.tone === "bad" ? m3.error : m3.onSurfaceVar, fontWeight: 600 }}>
            {openViolations.text}
          </Typography>
          {onOpenViolations ? <M3Button emphasis="outlined" onClick={onOpenViolations}>Open violations for this pair</M3Button> : null}
        </Stack>
      ) : null}

      <Box>
        <Typography variant="body2" sx={{ fontWeight: 600 }}>Services</Typography>
        {explicitCell && explicitCell.services.length > 0 ? (
          <Box component="ul" aria-label="Cell services" sx={{ m: 0, pl: 2.5 }}>
            {explicitCell.services.map((srv) => (
              <li key={srv.entry}>
                <Typography variant="body2">
                  <Box component="span" sx={mono}>{srv.entry}</Box>
                  {" \u2014 "}
                  {srv.evaluable ? "Evaluable" : `Not evaluable: ${srv.not_evaluable_reason ?? "UNKNOWN"}`}
                </Typography>
              </li>
            ))}
          </Box>
        ) : (
          <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
            {effectiveStatement === "ALLOW_LISTED" ? "No services listed (empty list)." : "No service list for this statement."}
          </Typography>
        )}
      </Box>

      <Stack direction="row" gap={3} flexWrap="wrap">
        <Typography variant="body2">Rationale: <strong>{rationaleDisplay}</strong></Typography>
        <Typography variant="body2">Regulation reference: <strong>{referenceDisplay}</strong></Typography>
        <Typography variant="body2">
          Reference status: <strong>{referenceStatusWord(statusDisplay)}</strong> ({statusDisplay})
        </Typography>
        <Typography variant="body2">
          Confirmed: {explicitCell?.reference_status === "CONFIRMED"
            ? (explicitCell.confirmed_by
              ? <><strong>{actorLabel(explicitCell.confirmed_by).label}</strong> {explicitCell.confirmed_at ? <Ts at={explicitCell.confirmed_at} /> : "time UNKNOWN"}</>
              : "UNKNOWN")
            : "Not confirmed"}
        </Typography>
      </Stack>

      {canConfirm && explicitCell && explicitCell.reference_status === "PROPOSED" ? (
        <Box data-testid="confirm-reference-box" sx={{ borderTop: `1px solid ${m3.outlineVar}`, pt: 1.25, display: "flex", flexDirection: "column", gap: 1 }}>
          <Typography variant="body2">{CONFIRM_PARTICIPANT_NOTE}</Typography>
          <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
            Cell hash <Hash value={explicitCell.cell_sha256} />
          </Typography>
          <Box>
            <M3Button emphasis="tonal" disabled={busy} onClick={() => { void confirmRef(); }}>Confirm reference</M3Button>
          </Box>
        </Box>
      ) : null}

      {editable ? (
        <Box sx={{ borderTop: `1px solid ${m3.outlineVar}`, pt: 1.5, display: "flex", flexDirection: "column", gap: 1.5 }}>
          <Typography variant="body2" sx={{ fontWeight: 600 }}>Edit cell in draft</Typography>
          <Stack direction="row" gap={1.5} flexWrap="wrap">
            <TextField select size="small" label="Cell statement" value={statement}
              onChange={(e) => setStatement(e.target.value as MatrixStatement)} sx={{ minWidth: 180 }}>
              {MATRIX_STATEMENTS.map((s) => <MenuItem key={s.value} value={s.value}>{s.label} ({s.value})</MenuItem>)}
            </TextField>
            <TextField select size="small" label="Cell severity" value={severity}
              onChange={(e) => setSeverity(e.target.value as MatrixSeverity)} sx={{ minWidth: 160 }}>
              {MATRIX_SEVERITIES.map((s) => <MenuItem key={s.value} value={s.value}>{s.label} ({s.value})</MenuItem>)}
            </TextField>
          </Stack>
          {statement === "ALLOW_LISTED" ? (
            <TextField label="Services" multiline minRows={3} maxRows={8} value={servicesText}
              onChange={(e) => setServicesText(e.target.value)}
              placeholder={"tcp/443\ntcp/8000-8099\nset:web"}
              helperText="One canonical entry per line: tcp/443, tcp/8000-8099, udp/53, icmp/8, icmp/8/0, icmp6/128, ipproto/50, app:pan:<name>, set:<name>."
              InputProps={{ sx: mono }} />
          ) : (
            <Hint>Services are listed only for Allow listed (ALLOW_LISTED).</Hint>
          )}
          <TextField size="small" label="Cell rationale" value={rationale} onChange={(e) => setRationale(e.target.value)}
            inputProps={{ maxLength: 2000 }} />
          <TextField size="small" label="Regulation reference" value={reference} onChange={(e) => setReference(e.target.value)}
            inputProps={{ maxLength: 2000 }} helperText={RESET_CONFIRMATION_NOTE} />
          <Stack direction="row" gap={1} flexWrap="wrap">
            <M3Button emphasis="filled" disabled={busy} onClick={() => { void saveCell(); }}>Save cell</M3Button>
            {explicitCell ? (
              <M3Button emphasis="outlined" disabled={busy} onClick={() => { void clearCell(); }}>Use the version default</M3Button>
            ) : null}
          </Stack>
        </Box>
      ) : null}

      {errors.length > 0 ? (
        <Box role="alert" sx={{ color: m3.error }}>
          {errors.map((line, idx) => <Typography key={idx} variant="body2" sx={{ fontWeight: idx === 0 ? 600 : 400 }}>{line}</Typography>)}
        </Box>
      ) : null}
    </Box>
  );
}

export function TemplateDialog({
  open, editable, revision, cellsTotal, onClose, onImported, onChanged,
}: {
  readonly open: boolean;
  readonly editable: boolean;
  readonly revision: number | null;
  readonly cellsTotal: number;
  readonly onClose: () => void;
  readonly onImported: () => void;
  readonly onChanged: () => void;
}) {
  const [template, setTemplate] = useState<ZoneMatrixTemplateResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [askReplace, setAskReplace] = useState(false);
  const [errors, setErrors] = useState<readonly string[]>([]);

  useEffect(() => {
    if (!open) return;
    setAskReplace(false); setErrors([]); setLoading(true);
    getZoneMatrixTemplate()
      .then((res) => setTemplate(res))
      .catch((err) => setErrors(zoneErrorLines(err)))
      .finally(() => setLoading(false));
  }, [open]);

  const runImport = async (replace: boolean) => {
    setBusy(true); setErrors([]);
    try {
      await importMatrixTemplate(revision, replace);
      onImported();
    } catch (err) {
      const code = zoneErrorCode(err);
      if (code === "TEMPLATE_REPLACES_CELLS") {
        setAskReplace(true);
      } else {
        setErrors(zoneErrorLines(err));
        if (code === "REQUEST_CHANGED") onChanged();
      }
    } finally {
      setBusy(false);
    }
  };

  const columns: DataColumn<TemplateRow>[] = [
    { key: "row", header: "Role pair", wrap: true, render: (r) => r.row },
    { key: "statement", header: "Statement", width: 170, render: (r) => <><StatementShape statement={r.statement} />{knownStatementWord(r.statement)} ({isKnownStatement(r.statement) ? r.statement : "UNKNOWN"})</> },
    { key: "severity", header: "Severity", width: 130, render: (r) => `${knownSeverityWord(r.severity)} (${MATRIX_SEVERITIES.some((s) => s.value === r.severity) ? r.severity : "UNKNOWN"})` },
  ];

  return (
    <Dialog open={open} onClose={busy ? undefined : onClose} aria-label="Segmentation template"
      PaperProps={{ sx: { borderRadius: "28px", width: 720, maxWidth: "95vw" } }}>
      <DialogContent sx={{ p: 3, display: "flex", flexDirection: "column", gap: 2 }}>
        <Typography component="h2" sx={{ ...TYPE.section, m: 0 }}>
          {template ? `${template.name} (${template.revision})` : "Segmentation template"}
        </Typography>
        {template ? (
          <>
            <Typography variant="body2" data-testid="template-basis">{template.basis}</Typography>
            <Hint>Importing seeds cells from the zones' roles; a zone without a role stays on the version default. Every reference field starts empty.</Hint>
            <DataTable ariaLabel="Template rows" columns={columns} rows={template.rows} rowKey={(r) => r.row} />
          </>
        ) : loading ? (
          <Typography variant="body2">Reading template…</Typography>
        ) : null}
        {askReplace ? (
          <Box role="alert" sx={{ display: "flex", flexDirection: "column", gap: 1 }}>
            <Typography variant="body2" sx={{ fontWeight: 600 }}>Replace all {cellsTotal} cells?</Typography>
            <Stack direction="row" gap={1}>
              <M3Button emphasis="filled" disabled={busy} onClick={() => { void runImport(true); }}>Replace all cells</M3Button>
              <M3Button emphasis="text" disabled={busy} onClick={() => setAskReplace(false)}>Cancel</M3Button>
            </Stack>
          </Box>
        ) : null}
        {errors.length > 0 ? (
          <Box role="alert" sx={{ color: m3.error }}>
            {errors.map((line, idx) => <Typography key={idx} variant="body2">{line}</Typography>)}
          </Box>
        ) : null}
        <Stack direction="row" gap={1} justifyContent="flex-end">
          <M3Button emphasis="text" disabled={busy} onClick={onClose}>Close</M3Button>
          {editable && !askReplace ? (
            <M3Button emphasis="filled" disabled={busy || loading} onClick={() => { void runImport(false); }}>Import template</M3Button>
          ) : null}
        </Stack>
      </DialogContent>
    </Dialog>
  );
}
