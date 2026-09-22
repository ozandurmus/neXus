import { useEffect, useState } from "react";
import Box from "@mui/material/Box";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Typography from "@mui/material/Typography";

import { EmptyPanel } from "../shell/ScreenLayout";
import { M3Button, M3Tabs, StatusChip } from "../shell/M3Widgets";
import { jobPhaseLabel, isTerminalJobState } from "../shell/deviceCopy";
import { m3 } from "../theme/m3Theme";
import {
  getDevice,
  getDeviceConfiguration,
  getDeviceConfigurationText,
  requestConfigurationCollect,
  type ApiError,
  type ConfigurationIndexEntry,
  type ConfigurationOverride,
  type DeviceConfiguration,
  type DeviceDetail,
  ConfigurationDeviationSummary,
} from "../auth/adminApi";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import { InventoryEntityHeader } from "./InventoryPanels";

const POLL_INTERVAL_MS = 1750;

function describeApiError(err: unknown): string {
  const apiErr = err as Partial<ApiError>;
  const serverError = typeof apiErr.body?.error === "string" ? (apiErr.body.error as string) : undefined;
  if (serverError) return serverError;
  return `request failed${apiErr.status ? ` (status ${apiErr.status})` : ""}`;
}

function changeStateTone(state: string | null): "ok" | "warn" | "neutral" | "bad" {
  if (state === "changed") return "bad";
  if (state === "unchanged") return "ok";
  return "neutral";
}

function changeStateLabel(state: string | null): string {
  if (state === null) return "Not collected";
  if (state === "first_run") return "First run";
  if (state === "changed") return "Changed";
  return "Aligned";
}

/** WORKER.md "Configuration screen": "hash prefix" -- the first 12 hex characters, never the whole hash inline. */
function hashPrefix(hash: string | null): string {
  return hash ? hash.slice(0, 12) : "—";
}

function IndexTable({
  index,
  emptyTitle,
  emptyBody,
}: {
  readonly index: readonly ConfigurationIndexEntry[];
  readonly emptyTitle: string;
  readonly emptyBody: string;
}) {
  if (index.length === 0) {
    return <EmptyPanel title={emptyTitle} body={emptyBody} />;
  }
  return (
    <Box sx={{ bgcolor: m3.scLowest, border: "1px solid", borderColor: m3.outlineVar, borderRadius: "12px", overflow: "hidden" }}>
      <Table size="small">
        <TableHead sx={{ bgcolor: m3.scLow }}>
          <TableRow>
            <TableCell sx={{ fontWeight: 600, fontSize: 12, color: m3.onSurfaceVar }}>Context</TableCell>
            <TableCell sx={{ fontWeight: 600, fontSize: 12, color: m3.onSurfaceVar }}>Section</TableCell>
            <TableCell sx={{ fontWeight: 600, fontSize: 12, color: m3.onSurfaceVar }}>Source</TableCell>
            <TableCell align="right" sx={{ fontWeight: 600, fontSize: 12, color: m3.onSurfaceVar }}>Entries</TableCell>
            <TableCell />
          </TableRow>
        </TableHead>
        <TableBody>
          {index.map((entry, i) => (
            <TableRow key={`${entry.context}-${entry.section}-${entry.source ?? "n/a"}-${i}`}>
              <TableCell sx={{ fontSize: 13 }}>{entry.context}</TableCell>
              <TableCell sx={{ fontSize: 13, fontWeight: 500 }}>{entry.section}</TableCell>
              <TableCell sx={{ fontSize: 13, color: "text.secondary" }}>{entry.source ?? "—"}</TableCell>
              <TableCell align="right" sx={{ fontSize: 13, fontFamily: "monospace" }}>{entry.entry_count}</TableCell>
              <TableCell>
                {entry.has_override && <StatusChip tone="warn" label="override" dense />}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </Box>
  );
}

function OverridesNote({ overrides }: { readonly overrides: readonly ConfigurationOverride[] }) {
  if (overrides.length === 0) {
    return (
      <EmptyPanel
        title="No local overrides"
        body="No src=local element was found in this device's own configuration read (no_local_override)."
      />
    );
  }
  return (
    <Box sx={{ p: 2, bgcolor: m3.scLowest, border: "1px solid", borderColor: m3.outlineVar, borderRadius: "12px" }}>
      <Stack spacing={1}>
        <Typography variant="body2" sx={{ fontWeight: 600 }}>Local overrides identified:</Typography>
        {overrides.map((o, i) => (
          <Box key={`${o.context}-${o.element_path}-${i}`} sx={{ p: 1, bgcolor: m3.scLow, borderRadius: "8px", border: `1px solid ${m3.outlineVar}` }}>
            <Typography variant="body2" sx={{ fontFamily: "monospace", fontSize: 13 }}>
              {o.context} · {o.element_path}
            </Typography>
            {o.panorama_source ? (
              <Typography variant="caption" color="text.secondary">
                Defined by {o.panorama_source}
              </Typography>
            ) : null}
          </Box>
        ))}
      </Stack>
    </Box>
  );
}

/** Monospace, scrollable viewer for the Check Point sanitized configuration text (withheld lines already removed server-side). */
function SanitizedTextViewer({
  text,
  withheldCount,
  canonicalHash,
}: {
  readonly text: string;
  readonly withheldCount?: number;
  readonly canonicalHash?: string | null;
}) {
  return (
    <Box sx={{ bgcolor: m3.scLowest, border: "1px solid", borderColor: m3.outlineVar, borderRadius: "12px", overflow: "hidden" }}>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", p: 1.5, bgcolor: m3.scLow, borderBottom: `1px solid ${m3.outlineVar}`, flexWrap: "wrap", gap: 1 }}>
        <Stack direction="row" spacing={1} alignItems="center">
          <Typography variant="body2" sx={{ fontWeight: 600 }}>Sanitized Configuration (Gaia CLI)</Typography>
          <StatusChip tone="neutral" label={`Hash: ${hashPrefix(canonicalHash ?? null)}`} dense />
        </Stack>
        {withheldCount !== undefined && withheldCount > 0 && (
          <StatusChip tone="warn" label={`${withheldCount} secret-bearing line(s) withheld`} dense />
        )}
      </Box>
      <Box
        component="pre"
        sx={{
          m: 0,
          p: 2,
          maxHeight: 520,
          overflow: "auto",
          fontFamily: "monospace",
          fontSize: 13,
          lineHeight: 1.5,
          color: m3.onSurface,
          bgcolor: m3.scLowest,
          whiteSpace: "pre-wrap",
          wordBreak: "break-word",
        }}
      >
        {text}
      </Box>
    </Box>
  );
}

/**
 * "Collect now" for configuration (WORKER.md "Frontend"): submits {@code
 * POST /devices/{id}/configuration/collect}, then polls {@code GET
 * /devices/{id}} until its job reaches a terminal state -- the same
 * pattern {@code InventoryPanels.CollectNowButton} uses.
 */
export function ConfigurationCollectNowButton({
  deviceId,
  onCollected,
}: {
  readonly deviceId: string;
  readonly onCollected: () => void;
}) {
  const [phase, setPhase] = useState<"idle" | "submitting" | "polling" | "error">("idle");
  const [jobState, setJobState] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (phase !== "polling") return undefined;
    let cancelled = false;

    const tick = () => {
      getDevice(deviceId)
        .then((result) => {
          if (cancelled) return;
          setJobState(result.job?.state ?? null);
          if (result.job !== null && isTerminalJobState(result.job.state)) {
            setPhase("idle");
            onCollected();
          }
        })
        .catch(() => {
          // Transient poll failure: keep polling rather than abandoning the flow.
        });
    };

    tick();
    const intervalId = setInterval(tick, POLL_INTERVAL_MS);
    return () => {
      cancelled = true;
      clearInterval(intervalId);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [phase, deviceId]);

  const handleClick = async () => {
    setError(null);
    setPhase("submitting");
    try {
      await requestConfigurationCollect(deviceId);
      setPhase("polling");
    } catch (err) {
      setError(describeApiError(err));
      setPhase("error");
    }
  };

  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 0.5, alignItems: "flex-start" }}>
      <M3Button emphasis="filled" icon="download" onClick={handleClick} disabled={phase === "submitting" || phase === "polling"}>
        {phase === "polling" ? jobPhaseLabel(jobState ?? "REQUESTED") : "Collect now"}
      </M3Button>
      {error && (
        <Typography variant="body2" color="error">
          {error}
        </Typography>
      )}
    </Box>
  );
}

/**
 * The detail panels for one selected device's configuration:
 * Renders the full 7-tab design specification matching PDF Page 3 and Page 7.
 */
export function DeviceConfigurationPanels({
  deviceId,
  hostname,
  vendorHint,
}: {
  readonly deviceId: string;
  /** The same name the list shows, so list and detail never name one device two ways. */
  readonly hostname?: string | null;
  readonly vendorHint?: string | null;
}) {
  const configurationFetch = useFetchOnMount<DeviceConfiguration>(
    () => getDeviceConfiguration(deviceId),
    describeApiError,
  );
  const deviceFetch = useFetchOnMount<DeviceDetail>(
    () => getDevice(deviceId),
    describeApiError,
  );

  const configuration = configurationFetch.data;
  const device = deviceFetch.data;
  const [sanitizedText, setSanitizedText] = useState<string | null>(null);
  const [textError, setTextError] = useState<string | null>(null);

  useEffect(() => {
    setSanitizedText(null);
    setTextError(null);
    if (configuration?.sanitized_text_available) {
      getDeviceConfigurationText(deviceId)
        .then(setSanitizedText)
        .catch((err) => setTextError(describeApiError(err)));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deviceId, configuration?.sanitized_text_available]);

  if (configurationFetch.error) {
    return (
      <EmptyPanel title="Configuration unavailable" body={configurationFetch.error}>
        <Box sx={{ display: "flex", justifyContent: "flex-end" }}>
          <M3Button emphasis="outlined" onClick={configurationFetch.refresh}>Retry</M3Button>
        </Box>
      </EmptyPanel>
    );
  }

  const index = configuration?.index ?? [];
  const overrides = configuration?.overrides ?? [];
  const deviation = configuration?.change_deviation_summary ?? null;
  const vendor = configuration?.vendor ?? device?.vendor_hint ?? vendorHint ?? "check_point";
  const isPaloAlto = vendor === "palo_alto";
  const displayName = hostname ?? device?.facts?.hostname ?? deviceId;
  const collected = configuration?.collected_at ? new Date(configuration.collected_at) : null;
  const collectedLabel = collected ? collected.toISOString().slice(0, 16).replace("T", " ") + " UTC" : null;
  const withheld = configuration?.withheld_line_count ?? 0;

  return (
    <Stack spacing={2}>
      <InventoryEntityHeader
        vendorHint={vendor}
        model={device?.facts?.model ?? null}
        title={displayName}
        reference={deviceId}
        referenceTitle={`Device ID: ${deviceId}`}
        chips={
          <>
            <StatusChip tone={changeStateTone(configuration?.change_state ?? null)} label={changeStateLabel(configuration?.change_state ?? null)} dense />
            <StatusChip tone="neutral" label={isPaloAlto ? "Palo Alto" : "Check Point"} dense />
            {device?.facts?.software_version && (
              <StatusChip tone="neutral" label={`${device.facts.software_version} · ${isPaloAlto ? "PAN-OS" : "Gaia"}`} dense />
            )}
            {device?.facts?.model && <StatusChip tone="neutral" label={device.facts.model} dense />}
            {device?.facts?.ha_role && <StatusChip tone="neutral" label={`HA: ${device.facts.ha_role}`} dense />}
          </>
        }
        action={<ConfigurationCollectNowButton deviceId={deviceId} onCollected={configurationFetch.refresh} />}
      >
        {/* Provenance and honest withholding (design language §7): where the value came from, and what was withheld. */}
        <Stack spacing={0.5}>
          <Typography variant="body2" color="text.secondary">
            {collectedLabel
              ? `Collected ${collectedLabel} · Primary source: ${configuration?.read_kind ?? "unknown"} · Current actual`
              : "Not collected yet -- nothing below is device evidence until the first configuration run lands."}
          </Typography>
          {collected && (
            <Typography variant="body2" color="text.secondary">
              {withheld > 0
                ? `${withheld} secret-bearing line(s) withheld from the sanitized view.`
                : "No secret-bearing lines were withheld."}
              {configuration?.canonical_hash ? ` · Canonical hash ${hashPrefix(configuration.canonical_hash)}` : ""}
            </Typography>
          )}
        </Stack>
      </InventoryEntityHeader>

      <M3Tabs
        ariaLabel="Configuration detail"
        tabs={[
          {
            label: "Sections",
            panel: (
              <Stack spacing={1}>
                <Typography variant="body2" color="text.secondary">
                  {index.length === 0
                    ? "No sections indexed."
                    : `${index.length} section${index.length === 1 ? "" : "s"} indexed from the ${isPaloAlto ? "PAN-OS effective-running tree" : "Check Point show configuration read"}.`}
                </Typography>
                <IndexTable
                  index={index}
                  emptyTitle="No configuration index"
                  emptyBody="This device has not been collected yet. Use Collect now to read its configuration."
                />
              </Stack>
            ),
          },
          {
            label: "Changes",
            panel: <DeviationPanel changeState={configuration?.change_state ?? null} deviation={deviation} />,
          },
          {
            label: "Overrides",
            panel: <OverridesNote overrides={overrides} />,
          },
          {
            label: isPaloAlto ? "XML Configuration" : "Sanitized text",
            panel: isPaloAlto ? (
              <EmptyPanel
                title="PAN-OS XML Configuration Artefact"
                body={
                  configuration?.canonical_hash
                    ? `The effective-running configuration is held as an encrypted artefact (canonical hash ${hashPrefix(configuration.canonical_hash)}). Its categories are indexed under Sections; local override markers under Overrides.`
                    : "No artefact recorded yet."
                }
              />
            ) : configuration?.sanitized_text_available ? (
              textError ? (
                <EmptyPanel title="Sanitized text unavailable" body={textError} />
              ) : sanitizedText === null ? (
                <EmptyPanel title="Sanitized text" body="Loading…" />
              ) : (
                <SanitizedTextViewer
                  text={sanitizedText}
                  withheldCount={configuration.withheld_line_count}
                  canonicalHash={configuration.canonical_hash}
                />
              )
            ) : (
              <EmptyPanel
                title="No sanitized text"
                body="The sanitized text view exists only for Check Point's show configuration read."
              />
            ),
          },
          {
            label: "Alignment",
            panel: (
              <EmptyPanel
                title="Expected-versus-actual alignment is not collected in this build"
                body={
                  isPaloAlto
                    ? "Panorama intent is not read yet, so no setting can be compared against an expected value. Only device-side evidence (sections, changes between runs, local overrides) is shown."
                    : "Management-server intent is not read yet, so no setting can be compared against an expected value. Only device-side evidence (sections, changes between runs, sanitized text) is shown."
                }
              />
            ),
          },
        ]}
      />
    </Stack>
  );
}

/** V22 deviation summary between this run and the previous one -- real recounts by section, or an
 * honest statement of why there is nothing to compare. */
function DeviationPanel({
  changeState,
  deviation,
}: {
  readonly changeState: DeviceConfiguration["change_state"];
  readonly deviation: ConfigurationDeviationSummary | null;
}) {
  if (changeState === null) {
    return <EmptyPanel title="No changes to show" body="This device has not been collected yet." />;
  }
  if (changeState === "first_run") {
    return <EmptyPanel title="First run" body="There is no previous run to compare against yet." />;
  }
  if (changeState === "unchanged") {
    return <EmptyPanel title="Unchanged" body="The canonical hash matches the previous run; nothing changed." />;
  }
  if (!deviation || deviation.entries.length === 0) {
    return (
      <EmptyPanel
        title="Changed"
        body={`The canonical hash differs from the previous run${deviation ? ` (summary status: ${deviation.status})` : ""}, but no per-section recount was recorded.`}
      />
    );
  }
  return (
    <Stack spacing={1}>
      <Typography variant="body2" color="text.secondary">
        {deviation.entries.length} section{deviation.entries.length === 1 ? "" : "s"} differ from the previous run (summary status: {deviation.status}).
      </Typography>
      <Box sx={{ bgcolor: m3.scLowest, border: "1px solid", borderColor: m3.outlineVar, borderRadius: "12px", overflow: "hidden" }}>
        <Table size="small">
          <TableHead sx={{ bgcolor: m3.scLow }}>
            <TableRow>
              <TableCell sx={{ fontWeight: 600 }}>Context</TableCell>
              <TableCell sx={{ fontWeight: 600 }}>Section</TableCell>
              <TableCell sx={{ fontWeight: 600 }}>Change</TableCell>
              <TableCell sx={{ fontWeight: 600 }}>Previous</TableCell>
              <TableCell sx={{ fontWeight: 600 }}>Now</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {deviation.entries.map((e, i) => (
              <TableRow key={`${e.context}-${e.section}-${i}`} hover>
                <TableCell><StatusChip tone="neutral" label={e.context === "physical" ? "Physical" : e.context} dense /></TableCell>
                <TableCell sx={{ fontFamily: "monospace" }}>{e.section}</TableCell>
                <TableCell><StatusChip tone="warn" label={e.kind} dense /></TableCell>
                <TableCell>{e.old_count ?? "—"}</TableCell>
                <TableCell>{e.new_count ?? "—"}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Box>
    </Stack>
  );
}
