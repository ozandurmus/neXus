import { useEffect, useMemo, useState } from "react";
import Box from "@mui/material/Box";
import MenuItem from "@mui/material/MenuItem";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import TextField from "@mui/material/TextField";
import ToggleButton from "@mui/material/ToggleButton";
import ToggleButtonGroup from "@mui/material/ToggleButtonGroup";
import Typography from "@mui/material/Typography";

import { DataTable, type DataColumn } from "../../shell/DataTable";
import { M3Button } from "../../shell/M3Widgets";
import { StatePanel } from "../../shell/States";
import { m3 } from "../../theme/m3Theme";
import { RADIUS } from "../../theme/tokens";
import {
  CellDetailPanel, StatementShape, TemplateDialog, cellCountsCapNote, openViolationsLine, type OpenViolationsLine,
  type SelectedPair,
} from "./MatrixCellDetail";
import type { ZoneTabProps } from "./ZoneMatrixScreen";
import type { ZoneSummaryHandle } from "./zoneMatrixSummaryUi";
import { Hash, Hint, SectionTitle, isKnownStatement, knownSeverityWord, knownStatementWord, mono, panelSx } from "./zoneMatrixUi";
import {
  DEFAULT_STATEMENTS, MATRIX_SEVERITIES, ZONE_ROLES, deleteServiceSet, getZoneVersion, matrixOf,
  referenceStatusWord, saveServiceSet, setMatrixDefault, zoneErrorCode,
  zoneErrorLines, zoneRoleLabel, type DefaultStatement, type MatrixCellView, type MatrixSeverity,
  type MatrixStatement, type ServiceSetView, type ZoneVersionDetail,
} from "./zoneMatrixApi";

interface Endpoint {
  readonly id: string;
  readonly name: string;
  readonly role: string | null;
}

const GRID_AXIS_PAGE = 25;

const trimLines = (text: string): string[] =>
  text.split("\n").map((line) => line.trim()).filter((line) => line !== "");

export function cellSummaryLabel(statement: MatrixStatement | string | null | undefined, serviceCount: number): string {
  if (statement === "DENY_ALL") return "Deny all";
  if (statement === "ALLOW_ANY") return "Allow any";
  if (statement === "ALLOW_LISTED") return serviceCount > 0 ? `Allow listed (${serviceCount})` : "Allow listed (empty list)";
  return "Statement UNKNOWN";
}

export interface MatrixTabProps extends ZoneTabProps {
  /** The stored summary shared with the Violations and Coverage tabs; read here only while the active version is shown. */
  readonly summary: ZoneSummaryHandle;
  /** Opens the Violations tab filtered to one source and destination zone. */
  readonly onOpenCellViolations: (sourceZoneId: string, destinationZoneId: string) => void;
}

/** The open-violation line, in the colour its tone says (a positive count is the only red). */
function OpenViolations({ line, testId }: { readonly line: OpenViolationsLine; readonly testId: string }) {
  return (
    <Typography variant="caption" data-testid={testId} sx={{ color: line.tone === "bad" ? m3.error : m3.onSurfaceVar, fontWeight: line.tone === "bad" ? 600 : 400 }}>
      {line.text}
    </Typography>
  );
}

export function MatrixTab({ data, refresh, notify, summary, onOpenCellViolations }: MatrixTabProps) {
  const { can, draft: rawDraft } = data.draft;
  // Contract 7.1: on the Matrix tab the draft is shown only to editors, labelled "draft, not evaluated".
  const draft = can.edit ? rawDraft : null;
  const active = data.active;
  const [selectedVersion, setSelectedVersion] = useState<string>(() => (active ? active.version_id : (draft ? "draft" : "active")));
  const [retiredCache, setRetiredCache] = useState<Record<string, ZoneVersionDetail>>({});
  const [retiredErrors, setRetiredErrors] = useState<Record<string, string>>({});
  const [retiredRetrySeq, setRetiredRetrySeq] = useState(0);
  const [loadingVersion, setLoadingVersion] = useState(false);
  const [filterText, setFilterText] = useState("");
  const [filterRole, setFilterRole] = useState("");
  const [layout, setLayout] = useState<"grid" | "list">("grid");
  const [axisOffset, setAxisOffset] = useState(0);
  const [selectedPair, setSelectedPair] = useState<{ readonly sourceId: string; readonly destinationId: string } | null>(null);
  const [templateOpen, setTemplateOpen] = useState(false);

  const publishedVersions = useMemo(
    () => data.versions.filter((v) => v.state === "ACTIVE" || v.state === "RETIRED"),
    [data.versions],
  );

  useEffect(() => {
    if (selectedVersion === "draft" && !draft && active) setSelectedVersion(active.version_id);
    else if (selectedVersion === "draft" && !draft && !active) setSelectedVersion("active");
    else if (selectedVersion === "active" && active) setSelectedVersion(active.version_id);
    else if (!active && draft && selectedVersion === "active") setSelectedVersion("draft");
  }, [active, draft, selectedVersion]);

  const onDraft = selectedVersion === "draft" && draft !== null;
  const onActive = active !== null && (selectedVersion === active.version_id || selectedVersion === "active");
  const wantingRetired = !onDraft && !onActive && selectedVersion !== "draft" && selectedVersion !== "active";

  useEffect(() => {
    if (!wantingRetired) return undefined;
    if (retiredCache[selectedVersion] || retiredErrors[selectedVersion]) return undefined;
    let cancelled = false;
    setLoadingVersion(true);
    getZoneVersion(selectedVersion, false)
      .then((detail) => {
        if (cancelled) return;
        setRetiredCache((prev) => ({ ...prev, [selectedVersion]: detail }));
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        const lines = zoneErrorLines(err);
        setRetiredErrors((prev) => ({ ...prev, [selectedVersion]: lines.join(" ") }));
        notify({ kind: "refused", lines });
      })
      .finally(() => { if (!cancelled) setLoadingVersion(false); });
    return () => { cancelled = true; };
  }, [wantingRetired, selectedVersion, retiredCache, retiredErrors, retiredRetrySeq, notify]);

  const viewed: ZoneVersionDetail | null = onDraft
    ? draft
    : (onActive ? active : (wantingRetired ? (retiredCache[selectedVersion] ?? null) : (active ?? draft)));

  // Open-violation counts belong to the active version only: never to a draft or a retired version.
  const viewingActive = viewed !== null && viewed.state === "ACTIVE";
  const { ensure: ensureSummary } = summary;
  useEffect(() => { if (viewingActive) ensureSummary(); }, [viewingActive, ensureSummary]);

  const isRetired = Boolean(viewed && viewed.state === "RETIRED");
  const editable = can.edit && !isRetired;
  const canConfirm = Boolean(can.confirm_reference) && !isRetired;
  const revision = draft ? draft.revision : null;

  const afterWrite = () => {
    setSelectedVersion("draft");
    refresh();
  };

  const versionSelectControl = (publishedVersions.length > 0 || draft !== null) ? (
    <TextField select size="small" label="Version shown" value={onDraft ? "draft" : (viewed ? viewed.version_id : selectedVersion)}
      onChange={(e) => { setAxisOffset(0); setSelectedVersion(e.target.value); }} sx={{ minWidth: 250 }}>
      {publishedVersions.map((v) => (
        <MenuItem key={v.version_id} value={v.version_id}>
          Version {v.number ?? "UNKNOWN"} ({v.state === "ACTIVE" ? "active" : "retired"})
        </MenuItem>
      ))}
      {viewed && !publishedVersions.some((v) => v.version_id === viewed.version_id) && viewed.state !== "DRAFT" ? (
        <MenuItem value={viewed.version_id}>Version {viewed.number ?? "UNKNOWN"} (active)</MenuItem>
      ) : null}
      {draft ? (
        <MenuItem value="draft">Draft r{draft.revision} (draft, not evaluated)</MenuItem>
      ) : null}
    </TextField>
  ) : null;

  if (wantingRetired && viewed === null) {
    const retiredError = retiredErrors[selectedVersion];
    return (
      <Stack gap={2}>
        <Stack direction="row" gap={1.5} alignItems="center" flexWrap="wrap">
          {versionSelectControl}
        </Stack>
        {retiredError ? (
          <StatePanel variant="unknown" announce="none" title="Selected published version could not be read"
            body={`Statements for this published version are UNKNOWN until it can be read: ${retiredError}`}
            action={(
              <M3Button emphasis="outlined" onClick={() => {
                setRetiredErrors((prev) => { const next = { ...prev }; delete next[selectedVersion]; return next; });
                setRetiredRetrySeq((n) => n + 1);
              }}>
                Retry version read
              </M3Button>
            )} />
        ) : (
          <StatePanel variant="empty" title="Reading selected version…" announce="status" />
        )}
      </Stack>
    );
  }

  if (viewed === null) {
    return (
      <StatePanel variant="empty" title="No version has been published"
        body={can.edit ? "Add zones on the Zones tab or import the segmentation template to start a draft." : "The matrix appears here once a version is published."} />
    );
  }

  const matrix = matrixOf(viewed);
  const endpoints: readonly Endpoint[] = [
    ...viewed.zones.map((z) => ({ id: z.zone_id, name: z.name, role: z.role })),
    { id: "UNASSIGNED", name: "UNASSIGNED", role: null },
  ];

  const isMasked = viewed.zones.some((z) => /^ZONE-\d+$/.test(z.name))
    || matrix.service_sets.some((s) => /^SET-\d+$/.test(s.name))
    || matrix.cells.some((c) => c.regulation_reference === null);

  const filteredEndpoints = endpoints.filter((ep) => {
    if (filterRole && ep.id !== "UNASSIGNED" && ep.role !== filterRole) return false;
    if (filterText.trim() !== "" && !ep.name.toLowerCase().includes(filterText.trim().toLowerCase())) return false;
    return true;
  });

  const visibleEndpoints = filteredEndpoints.slice(axisOffset, axisOffset + GRID_AXIS_PAGE);

  const cellsByPair = new Map<string, MatrixCellView>();
  for (const c of matrix.cells) cellsByPair.set(`${c.source}->${c.destination}`, c);

  const activePair: SelectedPair | null = (() => {
    const target = selectedPair ?? (endpoints.length > 0 ? { sourceId: endpoints[0].id, destinationId: endpoints[Math.min(1, endpoints.length - 1)].id } : null);
    if (!target) return null;
    const src = endpoints.find((e) => e.id === target.sourceId) ?? endpoints[0];
    const dst = endpoints.find((e) => e.id === target.destinationId) ?? endpoints[0];
    return src && dst ? { sourceId: src.id, sourceName: src.name, destinationId: dst.id, destinationName: dst.name } : null;
  })();

  const selectedExplicit = activePair ? (cellsByPair.get(`${activePair.sourceId}->${activePair.destinationId}`) ?? null) : null;

  const viewedId = viewed.version_id;
  const lineOf = (sourceId: string, destinationId: string): OpenViolationsLine =>
    openViolationsLine(summary.data, viewedId, sourceId, destinationId);
  const capNote = viewingActive ? cellCountsCapNote(summary.data) : null;

  const listColumns: DataColumn<MatrixCellView>[] = [
    { key: "pair", header: "Pair", wrap: true, render: (c) => `${c.source_name} -> ${c.destination_name}` },
    { key: "statement", header: "Statement", width: 200, render: (c) => <><StatementShape statement={c.statement} />{cellSummaryLabel(c.statement, c.services.length)}</> },
    { key: "severity", header: "Severity", width: 120, render: (c) => knownSeverityWord(c.severity) },
    { key: "ref", header: "Reference status", width: 150, render: (c) => referenceStatusWord(c.reference_status) },
    ...(viewingActive ? [{
      key: "open-violations", header: "Open violations", width: 230, wrap: true,
      render: (c: MatrixCellView) => <OpenViolations line={lineOf(c.source, c.destination)} testId={`open-violations-${c.source}-${c.destination}`} />,
    } satisfies DataColumn<MatrixCellView>] : []),
    { key: "open", header: "Detail", width: 110, render: (c) => (
      <M3Button emphasis="text" onClick={() => setSelectedPair({ sourceId: c.source, destinationId: c.destination })}>Inspect</M3Button>
    ) },
  ];

  return (
    <Stack gap={2}>
      <Box data-testid="matrix-header" sx={{ ...panelSx, flexDirection: "row", flexWrap: "wrap", alignItems: "center", gap: 2 }}>
        <Typography variant="body2" sx={{ fontWeight: 600 }}>
          {matrix.cells_confirmed} of {matrix.cells_total} cells confirmed by compliance
          {matrix.cells_with_reference > 0 ? ` (${matrix.cells_with_reference} with reference)` : ""}
        </Typography>
        <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
          Version default: <StatementShape statement={matrix.default_statement} />
          <strong>{knownStatementWord(matrix.default_statement)}</strong> ({isKnownStatement(matrix.default_statement) ? matrix.default_statement : "UNKNOWN"}), severity <strong>{knownSeverityWord(matrix.default_severity)}</strong>
        </Typography>
        <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
          UNASSIGNED {"->"} UNASSIGNED: {matrix.unassigned_to_unassigned}
        </Typography>
        {matrix.template_origin ? (
          <Typography variant="body2" data-testid="template-origin" sx={{ color: m3.onSurfaceVar }}>
            Template origin: {matrix.template_origin.name} ({matrix.template_origin.revision})
          </Typography>
        ) : null}
      </Box>

      <Stack direction="row" gap={1.5} alignItems="center" flexWrap="wrap">
        {versionSelectControl}

        {draft && active ? (
          <M3Button emphasis={onDraft ? "tonal" : "outlined"} onClick={() => setSelectedVersion(onDraft ? active.version_id : "draft")}>
            {onDraft ? `Viewing draft r${draft.revision} (draft, not evaluated)` : `Switch to draft r${draft.revision} (draft, not evaluated)`}
          </M3Button>
        ) : null}

        <TextField size="small" label="Filter zone" value={filterText}
          onChange={(e) => { setAxisOffset(0); setFilterText(e.target.value); }} sx={{ minWidth: 160 }} />
        <TextField select size="small" label="Filter role" value={filterRole}
          onChange={(e) => { setAxisOffset(0); setFilterRole(e.target.value); }} sx={{ minWidth: 160 }}>
          <MenuItem value="">All roles</MenuItem>
          {ZONE_ROLES.map((r) => <MenuItem key={r.value} value={r.value}>{r.label}</MenuItem>)}
        </TextField>

        <ToggleButtonGroup exclusive size="small" value={layout} aria-label="Matrix layout"
          onChange={(_e, next: "grid" | "list" | null) => { if (next) setLayout(next); }}>
          <ToggleButton value="grid">Grid</ToggleButton>
          <ToggleButton value="list">Explicit cells</ToggleButton>
        </ToggleButtonGroup>

        <Box sx={{ flex: 1 }} />
        <M3Button emphasis="outlined" onClick={() => setTemplateOpen(true)}>Segmentation template</M3Button>
      </Stack>

      {loadingVersion ? <Hint>Reading selected version…</Hint> : null}

      <SectionTitle note={viewed.state === "DRAFT"
        ? "Draft, not evaluated. Changes apply after another person publishes the draft."
        : <>Published version {viewed.number ?? "UNKNOWN"} (<Hash value={viewed.content_sha256} />).</>}>
        {viewed.state === "DRAFT" ? `Draft r${viewed.revision} (draft, not evaluated)` : `Version ${viewed.number ?? "UNKNOWN"}`}
      </SectionTitle>

      {filteredEndpoints.length > GRID_AXIS_PAGE && layout === "grid" ? (
        <Stack direction="row" gap={1} alignItems="center">
          <Hint>Showing endpoints {axisOffset + 1} to {Math.min(filteredEndpoints.length, axisOffset + GRID_AXIS_PAGE)} of {filteredEndpoints.length}</Hint>
          <M3Button emphasis="text" disabled={axisOffset === 0} onClick={() => setAxisOffset(Math.max(0, axisOffset - GRID_AXIS_PAGE))}>Previous</M3Button>
          <M3Button emphasis="text" disabled={axisOffset + GRID_AXIS_PAGE >= filteredEndpoints.length}
            onClick={() => setAxisOffset(axisOffset + GRID_AXIS_PAGE)}>Next</M3Button>
        </Stack>
      ) : null}

      {capNote ? <Typography variant="body2" data-testid="cell-counts-cap-note" sx={{ color: m3.onSurfaceVar }}>{capNote}</Typography> : null}

      {layout === "list" ? (
        <DataTable ariaLabel="Explicit matrix cells" columns={listColumns} rows={matrix.cells}
          rowKey={(c) => `${c.source}->${c.destination}`} emptyText="No explicit cells in this version; every pair follows the version default." />
      ) : (
        <Box sx={{ overflow: "auto", maxHeight: 540, border: `1px solid ${m3.outlineVar}`, borderRadius: `${RADIUS.card}px`, bgcolor: m3.scLowest }}>
          <Table size="small" stickyHeader aria-label="Zone matrix grid">
            <TableHead>
              <TableRow>
                <TableCell sx={{ position: "sticky", left: 0, zIndex: 3, bgcolor: m3.scLow }}>Source \ Destination</TableCell>
                {visibleEndpoints.map((col) => (
                  <TableCell key={col.id} sx={{ bgcolor: m3.scLow, minWidth: 145 }}>{col.name}</TableCell>
                ))}
              </TableRow>
            </TableHead>
            <TableBody>
              {visibleEndpoints.map((row) => (
                <TableRow key={row.id}>
                  <TableCell component="th" scope="row" sx={{ position: "sticky", left: 0, zIndex: 1, bgcolor: m3.scLow, fontWeight: 600 }}>
                    {row.name}
                    {row.role ? <Typography variant="caption" sx={{ display: "block", color: m3.onSurfaceVar }}>{zoneRoleLabel(row.role)}</Typography> : null}
                  </TableCell>
                  {visibleEndpoints.map((col) => {
                    const explicit = cellsByPair.get(`${row.id}->${col.id}`) ?? null;
                    const unassignedSelf = row.id === "UNASSIGNED" && col.id === "UNASSIGNED";
                    const st: MatrixStatement = explicit ? explicit.statement : (unassignedSelf ? "DENY_ALL" : matrix.default_statement);
                    const sev: MatrixSeverity = explicit ? explicit.severity : matrix.default_severity;
                    const count = explicit ? explicit.services.length : 0;
                    const isSelected = activePair?.sourceId === row.id && activePair?.destinationId === col.id;
                    const label = cellSummaryLabel(st, count);
                    return (
                      <TableCell key={col.id} data-testid={`matrix-cell-${row.id}-${col.id}`}
                        data-statement={st} data-explicit={explicit ? "true" : "false"}
                        onClick={() => setSelectedPair({ sourceId: row.id, destinationId: col.id })}
                        sx={{
                          cursor: "pointer",
                          bgcolor: isSelected ? m3.primaryContainer : (explicit ? m3.scLowest : m3.scLow),
                          border: isSelected ? `2px solid ${m3.primary}` : `1px solid ${m3.outlineVar}`,
                          verticalAlign: "top",
                        }}>
                        <Box sx={{ display: "flex", flexDirection: "column", gap: 0.25 }}>
                          <Typography variant="body2" sx={{ fontWeight: 600 }}>
                            <StatementShape statement={st} />{label}
                          </Typography>
                          <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
                            {knownSeverityWord(sev)} · {explicit ? `explicit (${referenceStatusWord(explicit.reference_status).toLowerCase()})` : "default"}
                          </Typography>
                          {viewingActive ? <OpenViolations line={lineOf(row.id, col.id)} testId={`open-violations-${row.id}-${col.id}`} /> : null}
                        </Box>
                      </TableCell>
                    );
                  })}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Box>
      )}

      {activePair ? (
        <CellDetailPanel pair={activePair} explicitCell={selectedExplicit} matrix={matrix} isMasked={isMasked}
          editable={editable} canConfirm={canConfirm} revision={revision} onRefresh={afterWrite} onChanged={refresh} notify={notify}
          openViolations={viewingActive ? lineOf(activePair.sourceId, activePair.destinationId) : null}
          onOpenViolations={viewingActive ? () => onOpenCellViolations(activePair.sourceId, activePair.destinationId) : null} />
      ) : null}

      <ServiceSetsSection sets={matrix.service_sets} editable={editable} revision={revision}
        onRefresh={afterWrite} onChanged={refresh} notify={notify} />

      {editable ? (
        <VersionDefaultSection defaultStatement={matrix.default_statement} defaultSeverity={matrix.default_severity}
          revision={revision} onRefresh={afterWrite} onChanged={refresh} notify={notify} />
      ) : null}

      <TemplateDialog open={templateOpen} editable={editable} revision={revision}
        cellsTotal={(draft ? matrixOf(draft) : matrix).cells_total}
        onClose={() => setTemplateOpen(false)}
        onImported={() => {
          setTemplateOpen(false);
          notify({ kind: "done", lines: ["Segmentation template imported into the draft. Every reference field is empty."] });
          afterWrite();
        }}
        onChanged={refresh} />
    </Stack>
  );
}

function VersionDefaultSection({
  defaultStatement, defaultSeverity, revision, onRefresh, onChanged, notify,
}: {
  readonly defaultStatement: DefaultStatement;
  readonly defaultSeverity: MatrixSeverity;
  readonly revision: number | null;
  readonly onRefresh: () => void;
  readonly onChanged: () => void;
  readonly notify: (notice: { readonly kind: "done" | "refused"; readonly lines: readonly string[] } | null) => void;
}) {
  const [statement, setStatement] = useState<DefaultStatement>(defaultStatement);
  const [severity, setSeverity] = useState<MatrixSeverity>(defaultSeverity);
  const [busy, setBusy] = useState(false);

  useEffect(() => { setStatement(defaultStatement); setSeverity(defaultSeverity); }, [defaultStatement, defaultSeverity]);

  const save = async () => {
    setBusy(true); notify(null);
    try {
      await setMatrixDefault(revision, statement, severity);
      notify({ kind: "done", lines: [`Version default saved as ${knownStatementWord(statement)} (${knownSeverityWord(severity)}).`] });
      onRefresh();
    } catch (err) {
      notify({ kind: "refused", lines: zoneErrorLines(err) });
      if (zoneErrorCode(err) === "REQUEST_CHANGED") onChanged();
    } finally {
      setBusy(false);
    }
  };

  return (
    <Box component="section" aria-label="Version default" sx={panelSx}>
      <SectionTitle note="Pairs without an explicit cell follow the version default. Allow any is never offered as the version default.">
        Version default
      </SectionTitle>
      <Stack direction="row" gap={1.5} alignItems="center" flexWrap="wrap">
        <TextField select size="small" label="Default statement" value={statement}
          onChange={(e) => setStatement(e.target.value as DefaultStatement)} sx={{ minWidth: 200 }}>
          {DEFAULT_STATEMENTS.map((s) => <MenuItem key={s.value} value={s.value}>{s.label} ({s.value})</MenuItem>)}
        </TextField>
        <TextField select size="small" label="Default severity" value={severity}
          onChange={(e) => setSeverity(e.target.value as MatrixSeverity)} sx={{ minWidth: 160 }}>
          {MATRIX_SEVERITIES.map((s) => <MenuItem key={s.value} value={s.value}>{s.label} ({s.value})</MenuItem>)}
        </TextField>
        <M3Button emphasis="tonal" disabled={busy} onClick={() => { void save(); }}>Save default</M3Button>
      </Stack>
    </Box>
  );
}

function ServiceSetsSection({
  sets, editable, revision, onRefresh, onChanged, notify,
}: {
  readonly sets: readonly ServiceSetView[];
  readonly editable: boolean;
  readonly revision: number | null;
  readonly onRefresh: () => void;
  readonly onChanged: () => void;
  readonly notify: (notice: { readonly kind: "done" | "refused"; readonly lines: readonly string[] } | null) => void;
}) {
  const [name, setName] = useState("");
  const [entriesText, setEntriesText] = useState("");
  const [busy, setBusy] = useState(false);
  const [errors, setErrors] = useState<readonly string[]>([]);

  if (!editable && sets.length === 0) return null;

  const saveSet = async () => {
    const sent = trimLines(entriesText);
    setBusy(true); setErrors([]); notify(null);
    try {
      await saveServiceSet(revision, name.trim(), sent);
      setName(""); setEntriesText("");
      notify({ kind: "done", lines: [`Service set ${name.trim()} saved to the draft.`] });
      onRefresh();
    } catch (err) {
      setErrors(zoneErrorLines(err, sent));
      if (zoneErrorCode(err) === "REQUEST_CHANGED") onChanged();
    } finally {
      setBusy(false);
    }
  };

  const removeSet = async (targetName: string) => {
    setBusy(true); setErrors([]); notify(null);
    try {
      await deleteServiceSet(revision, targetName);
      notify({ kind: "done", lines: [`Service set ${targetName} removed from the draft.`] });
      onRefresh();
    } catch (err) {
      setErrors(zoneErrorLines(err));
      if (zoneErrorCode(err) === "REQUEST_CHANGED") onChanged();
    } finally {
      setBusy(false);
    }
  };

  return (
    <Box component="section" aria-label="Service sets" sx={panelSx}>
      <SectionTitle note="Named lists of services referenced as set:<name>. Saving a set changes every cell that uses it.">
        Service sets
      </SectionTitle>
      {sets.length > 0 ? (
        <Box component="ul" aria-label="Defined service sets" sx={{ m: 0, pl: 2.5 }}>
          {sets.map((s) => (
            <li key={s.name}>
              <Stack direction="row" gap={1.5} alignItems="center" flexWrap="wrap">
                <Typography variant="body2">
                  <Box component="span" sx={{ ...mono, fontWeight: 600 }}>{s.name}</Box>
                  {": "}
                  {s.entries.map((e) => (
                    <Box key={e.entry} component="span" sx={{ mr: 1 }}>
                      <Box component="span" sx={mono}>{e.entry}</Box>
                      {e.evaluable ? "" : ` (${e.not_evaluable_reason})`}
                    </Box>
                  ))}
                </Typography>
                {editable ? (
                  <>
                    <M3Button emphasis="text" disabled={busy}
                      onClick={() => { setName(s.name); setEntriesText(s.entries.map((e) => e.entry).join("\n")); }}>
                      Edit set {s.name}
                    </M3Button>
                    <M3Button emphasis="text" disabled={busy} onClick={() => { void removeSet(s.name); }}>
                      Delete set {s.name}
                    </M3Button>
                  </>
                ) : null}
              </Stack>
            </li>
          ))}
        </Box>
      ) : (
        <Hint>No service sets defined in this version.</Hint>
      )}
      {editable ? (
        <Stack gap={1.25} sx={{ pt: 1 }}>
          <TextField size="small" label="Service set name" value={name} onChange={(e) => setName(e.target.value)}
            inputProps={{ maxLength: 64 }} helperText="Letters, digits, _, . or -, up to 64 characters" sx={{ maxWidth: 320 }} />
          <TextField label="Service set entries" multiline minRows={2} maxRows={6} value={entriesText}
            onChange={(e) => setEntriesText(e.target.value)} placeholder={"tcp/443\ntcp/8443"} InputProps={{ sx: mono }} />
          <Box>
            <M3Button emphasis="tonal" disabled={busy || name.trim() === ""} onClick={() => { void saveSet(); }}>
              Save service set
            </M3Button>
          </Box>
        </Stack>
      ) : null}
      {errors.length > 0 ? (
        <Box role="alert" sx={{ color: m3.error }}>
          {errors.map((line, idx) => <Typography key={idx} variant="body2">{line}</Typography>)}
        </Box>
      ) : null}
    </Box>
  );
}
