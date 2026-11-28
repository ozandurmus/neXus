import { useCallback, useEffect, useRef, useState } from "react";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import CardContent from "@mui/material/CardContent";
import FormControlLabel from "@mui/material/FormControlLabel";
import MenuItem from "@mui/material/MenuItem";
import Stack from "@mui/material/Stack";
import Switch from "@mui/material/Switch";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import { useSession } from "../../auth/SessionContext";
import {
  freezeLogCatalog,
  getLogCatalogDetail,
  getLogDetectionSettings,
  listLogCatalogs,
  saveLogDetectionSettings,
  startLogCatalogBuild,
  type ApiError,
  type LogCatalogBuildState,
  type LogCatalogDetailAnswer,
  type LogCatalogDiffPayload,
  type LogCatalogListAnswer,
  type LogCatalogSummary,
  type LogDetectionSettings,
  type LogDetectionSettingsPatch,
} from "../../auth/adminApi";
import { sourceLabel } from "../../screens/DeviceLogsPanel";
import { M3Button, StatusChip } from "../../shell/M3Widgets";
import { PLANE_ADMIN, planeGranted } from "../../shell/actionAccess";
import { RestrictedPanel, StatePanel, isRestricted } from "../../shell/States";
import { Timestamp } from "../../shell/Timestamp";
import { MONO, m3 } from "../../theme/m3Theme";

function problemsOf(err: unknown): { readonly message: string; readonly code: string | null } {
  const e = err as ApiError | undefined;
  const body = (e?.body ?? {}) as { problems?: string[]; message?: string; code?: string; error?: string };
  if (body.problems?.length) return { message: body.problems.join("; "), code: body.code ?? null };
  if (typeof body.message === "string") return { message: body.message, code: body.code ?? body.error ?? null };
  return { message: `request failed${e?.status ? ` (status ${e.status})` : ""}`, code: body.code ?? body.error ?? null };
}

function defaultWindow(): { readonly from: string; readonly to: string } {
  const end = new Date(Date.now() - 86_400_000);
  const start = new Date(end.getTime() - 27 * 86_400_000);
  return { from: start.toISOString().slice(0, 10), to: end.toISOString().slice(0, 10) };
}

function formatShare(share: number): string {
  return `${(share * 100).toFixed(2)}%`;
}

function DiffSection({ diff }: { readonly diff: LogCatalogDiffPayload | null }) {
  if (!diff || !diff.counts) {
    return (
      <Typography variant="body2" color="text.secondary" data-testid="catalog-diff-empty">
        No stored version diff yet (a diff against the active version is recorded when the catalog is frozen, or compare two versions above).
      </Typography>
    );
  }
  const c = diff.counts;
  return (
    <Stack spacing={1} data-testid="catalog-diff-view">
      <Typography variant="body2" sx={{ fontWeight: 600 }}>
        {`Diff: ${diff.from_version ?? "none (initial)"} → ${diff.to_version ?? "selected"} · templates ${c.templates_from} → ${c.templates_to}`}
      </Typography>
      <Stack direction="row" spacing={0.75} flexWrap="wrap" useFlexGap>
        <StatusChip tone="neutral" dense label={`added ${c.added}`} />
        <StatusChip tone="neutral" dense label={`removed ${c.removed}`} />
        <StatusChip tone="neutral" dense label={`merged ${c.merged}`} />
        <StatusChip tone={c.split > 0 ? "warn" : "neutral"} dense label={`split ${c.split}`} />
        <StatusChip tone="neutral" dense label={`class changes ${c.class_changes}`} />
      </Stack>
      {(diff.normaliser || diff.marker_list || diff.masking_rule_set) && (
        <Typography variant="caption" sx={{ color: m3.onSurfaceVar, fontFamily: MONO }}>
          {[
            diff.normaliser && `normaliser ${diff.normaliser.from || "—"} → ${diff.normaliser.to}`,
            diff.marker_list && `markers ${diff.marker_list.from || "—"} → ${diff.marker_list.to}`,
            diff.masking_rule_set && `masking ${diff.masking_rule_set.from || "—"} → ${diff.masking_rule_set.to}`,
          ].filter(Boolean).join(" · ")}
        </Typography>
      )}
      {diff.split && diff.split.length > 0 && (
        <Box>
          <Typography variant="caption" sx={{ fontWeight: 600 }}>Splits (earlier counts marked UNKNOWN_BEFORE):</Typography>
          {diff.split.slice(0, 10).map((s) => (
            <Typography key={s.template_id} variant="caption" sx={{ display: "block", fontFamily: MONO }}>
              {`${s.template_id} (${sourceLabel(s.source_key)}, ${formatShare(s.line_share)}) → ${s.into.join(", ")} [${s.history}]`}
            </Typography>
          ))}
        </Box>
      )}
      {diff.merged && diff.merged.length > 0 && (
        <Box>
          <Typography variant="caption" sx={{ fontWeight: 600 }}>Merges:</Typography>
          {diff.merged.slice(0, 10).map((m) => (
            <Typography key={m.template_id} variant="caption" sx={{ display: "block", fontFamily: MONO }}>
              {`${m.from.join(", ")} → ${m.template_id} (${sourceLabel(m.source_key)}, ${formatShare(m.line_share)})`}
            </Typography>
          ))}
        </Box>
      )}
      {diff.class_changes && diff.class_changes.length > 0 && (
        <Box>
          <Typography variant="caption" sx={{ fontWeight: 600 }}>Class and severity weight changes:</Typography>
          {diff.class_changes.slice(0, 10).map((ch) => (
            <Typography key={ch.template_id} variant="caption" sx={{ display: "block", fontFamily: MONO }}>
              {`${ch.template_id}: ${ch.class_from} (S ${ch.s_from}) → ${ch.class_to} (S ${ch.s_to})`}
            </Typography>
          ))}
        </Box>
      )}
    </Stack>
  );
}

/**
 * Administration > Notifications and logging > Template catalog (LOG_DETECTION_ENGINE_DESIGN_2026_11_24.md sections 6.5, 9 and 10;
 * `GET/PUT /api/v2/config/log-detection`, `GET/POST /api/v2/config/log-detection/catalogs*`; CLI `log-detection*` / `log-catalog*`).
 *
 * Governs the P1 switch (`enabled`), the shadow window (`shadow_days`), the P2 Layer-B switch (`p2_enabled`), offline Drain
 * DRAFT catalog builds over masked chunks, version diffs, and freezing a DRAFT catalog. Holds counts, digests and closed-vocabulary
 * class summaries only, never a log line or unmasked identity.
 */
export function LogDetectionCatalogPanel() {
  const session = useSession();
  const [settings, setSettings] = useState<LogDetectionSettings | null>(null);
  const [catalogsView, setCatalogsView] = useState<LogCatalogListAnswer | null>(null);
  const [draftEnabled, setDraftEnabled] = useState(false);
  const [draftP2Enabled, setDraftP2Enabled] = useState(false);
  const [draftShadowDays, setDraftShadowDays] = useState<string>("");
  const [restricted, setRestricted] = useState(false);
  const [loadError, setLoadError] = useState<{ message: string; code: string | null } | null>(null);

  const [saveBusy, setSaveBusy] = useState(false);
  const [saveNotice, setSaveNotice] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<{ message: string; code: string | null } | null>(null);

  const initialWindow = defaultWindow();
  const [fromDate, setFromDate] = useState(initialWindow.from);
  const [toDate, setToDate] = useState(initialWindow.to);
  const [buildBusy, setBuildBusy] = useState(false);
  const [buildState, setBuildState] = useState<LogCatalogBuildState | null>(null);
  const [catalogNotice, setCatalogNotice] = useState<string | null>(null);
  const [catalogError, setCatalogError] = useState<{ message: string; code: string | null } | null>(null);
  const [freezingVersion, setFreezingVersion] = useState<string | null>(null);

  const [selectedVersion, setSelectedVersion] = useState<string | null>(null);
  const [compareFrom, setCompareFrom] = useState<string>("");
  const [detail, setDetail] = useState<LogCatalogDetailAnswer | null>(null);
  const [detailBusy, setDetailBusy] = useState(false);
  const [detailError, setDetailError] = useState<{ message: string; code: string | null } | null>(null);
  const detailSeqRef = useRef(0);

  const applySettings = (s: LogDetectionSettings) => {
    setSettings(s);
    setDraftEnabled(s.enabled);
    setDraftP2Enabled(s.p2_enabled);
    setDraftShadowDays("");
  };

  const load = useCallback(() => {
    setLoadError(null);
    Promise.all([getLogDetectionSettings(), listLogCatalogs()])
      .then(([s, cats]) => {
        applySettings(s);
        setCatalogsView(cats);
        setBuildState(cats.build);
      })
      .catch((err) => {
        if (isRestricted(err)) {
          setRestricted(true);
          return;
        }
        setLoadError(problemsOf(err));
      });
  }, []);

  useEffect(() => { load(); }, [load]);
  useEffect(() => () => { detailSeqRef.current += 1; }, []);

  const inspectCatalog = useCallback((version: string, fromVer = "") => {
    const seq = ++detailSeqRef.current;
    setSelectedVersion(version);
    setCompareFrom(fromVer);
    setDetail(null);
    setDetailBusy(true);
    setDetailError(null);
    getLogCatalogDetail(version, fromVer || undefined)
      .then((res) => {
        if (detailSeqRef.current !== seq) return;
        setDetail(res);
      })
      .catch((err) => {
        if (detailSeqRef.current !== seq) return;
        setDetailError(problemsOf(err));
      })
      .finally(() => {
        if (detailSeqRef.current === seq) setDetailBusy(false);
      });
  }, []);

  if (restricted) {
    return (
      <RestrictedPanel
        area="Template catalog"
        role="Security Admin"
        nextStep="A Security Admin manages the log detection switches and freezes template catalogs here or with the CLI (log-detection-set, log-catalog)."
      />
    );
  }
  if (loadError) {
    return (
      <StatePanel
        variant="error"
        title="Log detection settings could not be read"
        body={loadError.message}
        code={loadError.code}
        action={<M3Button emphasis="outlined" onClick={load}>Retry</M3Button>}
      />
    );
  }
  if (!settings || !catalogsView) {
    return <Typography sx={{ p: 3 }} role="status">Loading log detection and template catalog…</Typography>;
  }

  const sessionAllowsWrite = (session === null || session.canReadAdministration !== false)
    && planeGranted(session?.permissions, PLANE_ADMIN);
  const canWrite = (settings.can_write ?? true) && sessionAllowsWrite;

  const parsedShadow = draftShadowDays.trim() === "" ? undefined : Number(draftShadowDays);
  const hasShadowChange = parsedShadow !== undefined && Number.isFinite(parsedShadow);
  // PUT /api/v2/config/log-detection requires at least one of `enabled` or `p2_enabled`; when the admin only adjusts
  // `shadow_days` (e.g. 0 to end shadow immediately), include the current `enabled` flag so the server accepts the write.
  const patch: LogDetectionSettingsPatch = {
    ...(draftEnabled !== settings.enabled || (hasShadowChange && draftP2Enabled === settings.p2_enabled)
      ? { enabled: draftEnabled }
      : {}),
    ...(draftP2Enabled !== settings.p2_enabled ? { p2_enabled: draftP2Enabled } : {}),
    ...(hasShadowChange ? { shadow_days: Math.round(parsedShadow) } : {}),
  };
  const changed = Object.keys(patch).length > 0;

  const saveSettings = async () => {
    if (!changed) return;
    setSaveBusy(true);
    setSaveNotice(null);
    setSaveError(null);
    try {
      const next = await saveLogDetectionSettings(patch);
      applySettings(next);
      setSaveNotice("Log detection settings saved.");
    } catch (err) {
      setSaveError(problemsOf(err));
    } finally {
      setSaveBusy(false);
    }
  };

  const startBuild = async () => {
    setBuildBusy(true);
    setCatalogNotice(null);
    setCatalogError(null);
    try {
      const started = await startLogCatalogBuild({ from: fromDate.trim(), to: toDate.trim() });
      setBuildState(started);
      const refreshed = await listLogCatalogs();
      setCatalogsView(refreshed);
      setBuildState(refreshed.build ?? started);
      setCatalogNotice(`Catalog build started for ${fromDate.trim()} to ${toDate.trim()}.`);
    } catch (err) {
      setCatalogError(problemsOf(err));
    } finally {
      setBuildBusy(false);
    }
  };

  const handleFreeze = async (row: LogCatalogSummary) => {
    setFreezingVersion(row.catalog_version);
    setCatalogNotice(null);
    setCatalogError(null);
    try {
      const frozen = await freezeLogCatalog(row.catalog_version);
      const refreshed = await listLogCatalogs();
      setCatalogsView(refreshed);
      setBuildState(refreshed.build);
      detailSeqRef.current += 1;
      setDetailBusy(false);
      setDetailError(null);
      setSelectedVersion(frozen.catalog_version);
      setCompareFrom("");
      setDetail({
        catalog_version: frozen.catalog_version,
        state: frozen.state,
        previous_version: frozen.previous_version,
        diff: frozen.diff,
      });
      setCatalogNotice(`Catalog ${frozen.catalog_version} frozen and audited.`);
    } catch (err) {
      setCatalogError(problemsOf(err));
    } finally {
      setFreezingVersion(null);
    }
  };

  const currentBuild = buildState ?? catalogsView.build;
  const hasFrozenCatalog = Boolean(catalogsView.active);
  const phaseChip: { readonly tone: "ok" | "warn" | "neutral"; readonly label: string } = !settings.enabled
    ? { tone: "neutral", label: "Off" }
    : settings.p2_runs && !hasFrozenCatalog
      ? {
          tone: "warn",
          label: settings.shadow ? "P2 waiting (no frozen catalog · shadow)" : "P2 waiting (no frozen catalog)",
        }
      : settings.p2_runs && settings.shadow
        ? { tone: "warn", label: "P2 active (shadow)" }
        : settings.p2_runs
          ? { tone: "ok", label: "P2 active" }
          : settings.shadow
            ? { tone: "warn", label: "P1 shadow" }
            : { tone: "warn", label: "P1 active" };
  const p2BlockedHint = !draftEnabled
    ? "P2 requires P1 to be enabled."
    : settings.shadow && parsedShadow !== 0
      ? `P1 shadow period runs until ${settings.shadow_until?.slice(0, 10) ?? "its end"}; P2 can start after the shadow period ends.`
      : !hasFrozenCatalog
        ? "No frozen template catalog yet; P2 passes skip with NO_FROZEN_CATALOG until a DRAFT catalog is frozen."
        : null;

  return (
    <Stack spacing={2} sx={{ maxWidth: 960 }} data-testid="log-detection-catalog-panel">
      <Card variant="outlined">
        <CardContent>
          <Stack spacing={2}>
            <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
              <Typography variant="h6" component="h3">Log detection phases</Typography>
              <StatusChip
                tone={phaseChip.tone}
                label={phaseChip.label}
                dense
              />
              <StatusChip
                tone={catalogsView.active ? "ok" : "unknown"}
                label={catalogsView.active ? `Active catalog ${catalogsView.active}` : "No frozen catalog"}
                dense
              />
            </Stack>
            <Typography variant="body2" color="text.secondary">
              {`Layer A matches vendor severity and kernel hardware markers; Layer B matches normalized masked lines against the active FROZEN template catalog (${catalogsView.note}).`}
            </Typography>
            <Typography variant="caption" color="text.secondary" data-testid="log-detection-last-changed">
              Last changed <Timestamp at={settings.updated_at} /> by {settings.updated_by ?? "UNKNOWN"}
            </Typography>
            {!canWrite && (
              <Typography role="status" variant="body2" data-testid="log-detection-readonly">
                Read-only: changing log detection settings or freezing a catalog needs the Security Admin role.
              </Typography>
            )}

            <Stack spacing={0.75} sx={{ pt: 1.5, borderTop: 1, borderColor: "divider" }} data-testid="log-detection-p1-row">
              <FormControlLabel
                control={
                  <Switch
                    checked={draftEnabled}
                    disabled={!canWrite}
                    onChange={(e) => { setSaveNotice(null); setSaveError(null); setDraftEnabled(e.target.checked); }}
                  />
                }
                label="Enable log detection (P1 — Layer A severity & hardware markers)"
              />
              <Typography variant="body2" color="text.secondary">
                {`${draftEnabled ? "On" : "Off"}.${settings.shadow ? ` Shadow period active${settings.shadow_until ? ` until ${settings.shadow_until.slice(0, 10)}` : ""} (Devices list chips stay hidden during shadow).` : " Outside shadow, open findings appear on the Devices list chip."}`}
              </Typography>
              {canWrite && (
                <TextField
                  label="Reset shadow days (0 to 90, optional)"
                  type="number"
                  size="small"
                  value={draftShadowDays}
                  onChange={(e) => { setSaveNotice(null); setSaveError(null); setDraftShadowDays(e.target.value); }}
                  inputProps={{ min: 0, max: 90, step: 1 }}
                  helperText="Leave blank to keep the current shadow window; 0 ends shadow immediately."
                  sx={{ width: { sm: 280 }, mt: 0.5 }}
                />
              )}
            </Stack>

            <Stack spacing={0.75} sx={{ pt: 1.5, borderTop: 1, borderColor: "divider" }} data-testid="log-detection-p2-row">
              <FormControlLabel
                control={
                  <Switch
                    checked={draftP2Enabled}
                    disabled={!canWrite}
                    onChange={(e) => { setSaveNotice(null); setSaveError(null); setDraftP2Enabled(e.target.checked); }}
                  />
                }
                label="Enable statistical detection (P2 — Layer B catalog, rarity & rate)"
              />
              <Typography variant="body2" color="text.secondary">
                {`${draftP2Enabled ? "On" : "Off"}. Evaluates NEW_ON_DEVICE, FLEET_RARE, RATE_SPIKE, SILENCE and the 100 x S x R x C ordering score against the active frozen catalog.`}
                {p2BlockedHint ? ` ${p2BlockedHint}` : ""}
              </Typography>
            </Stack>

            {saveNotice && <Typography role="status" variant="body2">{saveNotice}</Typography>}
            {saveError && <StatePanel variant="error" announce="status" title="Settings not saved" body={saveError.message} code={saveError.code} />}

            {canWrite && (
              <Stack direction="row" spacing={1.5}>
                <M3Button emphasis="filled" disabled={saveBusy || !changed} onClick={() => void saveSettings()}>
                  {saveBusy ? "Saving…" : "Save settings"}
                </M3Button>
                <M3Button
                  emphasis="outlined"
                  disabled={saveBusy || !changed}
                  onClick={() => { applySettings(settings); setSaveNotice(null); setSaveError(null); }}
                >
                  Discard changes
                </M3Button>
              </Stack>
            )}
          </Stack>
        </CardContent>
      </Card>

      <Card variant="outlined">
        <CardContent>
          <Stack spacing={2}>
            <Stack direction="row" spacing={1} alignItems="center" justifyContent="space-between" flexWrap="wrap" useFlexGap>
              <Box>
                <Typography variant="h6" component="h3">Template catalog</Typography>
                <Typography variant="body2" color="text.secondary">
                  Offline Drain catalog versions mined from masked, normalized chunks. Only a FROZEN version is matched online; freezing is audited and irreversible.
                </Typography>
              </Box>
              <M3Button emphasis="outlined" onClick={load}>Refresh</M3Button>
            </Stack>

            <Box data-testid="catalog-build-status" sx={{ bgcolor: m3.scLow, p: 1.5, borderRadius: 2, border: `1px solid ${m3.outlineVar}` }}>
              <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                <Typography variant="body2" sx={{ fontWeight: 600 }}>Offline build:</Typography>
                <StatusChip
                  tone={currentBuild.state === "DONE" ? "ok" : currentBuild.state === "FAILED" || currentBuild.state === "REFUSED" ? "bad" : currentBuild.state === "RUNNING" ? "warn" : "neutral"}
                  label={currentBuild.state}
                  dense
                />
                {currentBuild.catalog_version && (
                  <Typography variant="body2" sx={{ fontFamily: MONO }}>{currentBuild.catalog_version}</Typography>
                )}
                {currentBuild.templates !== undefined && (
                  <Typography variant="caption" color="text.secondary">
                    {`${currentBuild.templates} templates · ${currentBuild.chunks_read ?? 0} chunks read${currentBuild.chunks_skipped ? ` · ${currentBuild.chunks_skipped} skipped` : ""}`}
                  </Typography>
                )}
                {currentBuild.reason && currentBuild.reason !== "OK" && (
                  <Typography variant="caption" sx={{ fontFamily: MONO, color: m3.onSurfaceVar }}>{currentBuild.reason}</Typography>
                )}
              </Stack>
              {canWrite && (
                <Stack direction={{ xs: "column", sm: "row" }} spacing={1.5} alignItems={{ sm: "center" }} sx={{ mt: 1.5 }}>
                  <TextField
                    label="Training from"
                    size="small"
                    value={fromDate}
                    onChange={(e) => setFromDate(e.target.value)}
                    placeholder="YYYY-MM-DD"
                    sx={{ width: { sm: 170 } }}
                  />
                  <TextField
                    label="Training to"
                    size="small"
                    value={toDate}
                    onChange={(e) => setToDate(e.target.value)}
                    placeholder="YYYY-MM-DD"
                    sx={{ width: { sm: 170 } }}
                  />
                  <M3Button
                    emphasis="tonal"
                    disabled={buildBusy || currentBuild.state === "RUNNING" || !fromDate.trim() || !toDate.trim()}
                    onClick={() => void startBuild()}
                  >
                    {buildBusy ? "Starting…" : "Build draft catalog"}
                  </M3Button>
                </Stack>
              )}
            </Box>

            {catalogNotice && <Typography role="status" variant="body2">{catalogNotice}</Typography>}
            {catalogError && <StatePanel variant="error" announce="status" title="Catalog action refused" body={catalogError.message} code={catalogError.code} />}

            {catalogsView.catalogs.length === 0 ? (
              <Typography variant="body2" color="text.secondary" data-testid="catalog-empty">
                No template catalog has been built yet. Build a DRAFT over 7 to 92 days of masked system logs, review its counts, then freeze it.
              </Typography>
            ) : (
              <Stack spacing={1} data-testid="catalog-list">
                {catalogsView.catalogs.map((row) => {
                  const isActive = catalogsView.active === row.catalog_version;
                  const isSelected = selectedVersion === row.catalog_version;
                  return (
                    <Box
                      key={row.catalog_version}
                      data-testid={`catalog-row-${row.catalog_version}`}
                      sx={{
                        border: `1px solid ${isSelected ? m3.primary : m3.outlineVar}`,
                        borderRadius: 2,
                        p: 1.5,
                        bgcolor: isSelected ? m3.primaryContainer : m3.scLowest,
                      }}
                    >
                      <Stack direction="row" spacing={1} alignItems="center" justifyContent="space-between" flexWrap="wrap" useFlexGap>
                        <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                          <Typography sx={{ fontFamily: MONO, fontWeight: 700 }}>{row.catalog_version}</Typography>
                          <StatusChip tone={row.state === "FROZEN" ? "ok" : "warn"} label={row.state} dense />
                          {isActive && <StatusChip tone="ok" label="ACTIVE" dense />}
                          <Typography variant="body2">{`${row.template_count} templates`}</Typography>
                          {row.previous_version && (
                            <Typography variant="caption" color="text.secondary">{`prev ${row.previous_version}`}</Typography>
                          )}
                          <Typography variant="caption" sx={{ fontFamily: MONO, color: m3.onSurfaceVar }} title={row.digest}>
                            {`sha256:${row.digest.slice(0, 12)}`}
                          </Typography>
                        </Stack>
                        <Stack direction="row" spacing={1}>
                          <M3Button
                            emphasis="outlined"
                            onClick={() => inspectCatalog(row.catalog_version)}
                          >
                            {isSelected ? "Inspecting" : "Inspect"}
                          </M3Button>
                          {canWrite && row.state === "DRAFT" && (
                            <M3Button
                              emphasis="filled"
                              disabled={freezingVersion !== null}
                              onClick={() => void handleFreeze(row)}
                            >
                              {freezingVersion === row.catalog_version ? "Freezing…" : `Freeze ${row.catalog_version}`}
                            </M3Button>
                          )}
                        </Stack>
                      </Stack>
                    </Box>
                  );
                })}
              </Stack>
            )}

            {selectedVersion && (
              <Box data-testid="catalog-detail-panel" sx={{ pt: 1.5, borderTop: 1, borderColor: "divider" }}>
                <Stack direction={{ xs: "column", sm: "row" }} spacing={1.5} alignItems={{ sm: "center" }} justifyContent="space-between" sx={{ mb: 1.5 }}>
                  <Typography variant="subtitle1" sx={{ fontWeight: 650, fontFamily: MONO }}>
                    {`Catalog ${selectedVersion}`}
                  </Typography>
                  {catalogsView.catalogs.length > 1 && (
                    <TextField
                      select
                      size="small"
                      label="Compare from"
                      value={compareFrom}
                      onChange={(e) => inspectCatalog(selectedVersion, e.target.value)}
                      sx={{ minWidth: 190 }}
                    >
                      <MenuItem value="">Stored freeze diff</MenuItem>
                      {catalogsView.catalogs
                        .filter((c) => c.catalog_version !== selectedVersion)
                        .map((c) => (
                          <MenuItem key={c.catalog_version} value={c.catalog_version}>
                            {c.catalog_version}
                          </MenuItem>
                        ))}
                    </TextField>
                  )}
                </Stack>
                {detailBusy && <Typography variant="body2" color="text.secondary">Reading catalog detail…</Typography>}
                {detailError && <StatePanel variant="error" announce="status" title="Catalog detail unavailable" body={detailError.message} code={detailError.code} />}
                {!detailBusy && !detailError && detail && (detail.catalog_version ?? detail.diff?.to_version) === selectedVersion && (
                  <Stack spacing={1.5}>
                    {detail.manifest && (
                      <Box data-testid="catalog-manifest">
                        <Typography variant="body2">
                          {[
                            detail.manifest.training_window && `Window ${detail.manifest.training_window.from} to ${detail.manifest.training_window.to} (${detail.manifest.training_window.time_basis})`,
                            detail.manifest.lines !== undefined && `${detail.manifest.lines} lines across ${detail.manifest.chunks ?? 0} chunks`,
                            detail.manifest.drain && `Drain depth ${detail.manifest.drain.depth}, similarity ${detail.manifest.drain.similarity}`,
                          ].filter(Boolean).join(" · ")}
                        </Typography>
                        <Typography variant="caption" sx={{ display: "block", fontFamily: MONO, color: m3.onSurfaceVar, mt: 0.25 }}>
                          {[
                            detail.manifest.normaliser_version && `normaliser ${detail.manifest.normaliser_version}`,
                            detail.manifest.vocabulary_version && `vocab ${detail.manifest.vocabulary_version}`,
                            detail.manifest.masking_rule_set && `masking ${detail.manifest.masking_rule_set}`,
                            detail.manifest.marker_list_version && `markers ${detail.manifest.marker_list_version}`,
                          ].filter(Boolean).join(" · ")}
                        </Typography>
                        {detail.manifest.truncated && (
                          <Typography variant="caption" sx={{ display: "block", color: m3.error }}>
                            {`Build reached bound (${detail.manifest.truncated_reason ?? "TRUNCATED"}).`}
                          </Typography>
                        )}
                      </Box>
                    )}
                    {detail.class_counts && Object.keys(detail.class_counts).length > 0 && (
                      <Stack direction="row" spacing={0.75} flexWrap="wrap" useFlexGap data-testid="catalog-class-counts">
                        {Object.entries(detail.class_counts).map(([src, classes]) => (
                          <StatusChip
                            key={src}
                            tone="neutral"
                            dense
                            label={`${sourceLabel(src)}: ${Object.entries(classes).map(([cls, n]) => `${n} ${cls}`).join(", ")}`}
                          />
                        ))}
                      </Stack>
                    )}
                    <DiffSection diff={detail.diff} />
                  </Stack>
                )}
              </Box>
            )}
          </Stack>
        </CardContent>
      </Card>
    </Stack>
  );
}
