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
import {
  getDevice,
  getDeviceConfiguration,
  getDeviceConfigurationText,
  requestConfigurationCollect,
  type ApiError,
  type ConfigurationIndexEntry,
  type ConfigurationOverride,
  type DeviceConfiguration,
} from "../auth/adminApi";
import { useFetchOnMount } from "../shell/useFetchOnMount";

const POLL_INTERVAL_MS = 1750;

function describeApiError(err: unknown): string {
  const apiErr = err as Partial<ApiError>;
  const serverError = typeof apiErr.body?.error === "string" ? (apiErr.body.error as string) : undefined;
  if (serverError) return serverError;
  return `request failed${apiErr.status ? ` (status ${apiErr.status})` : ""}`;
}

function changeStateTone(state: string | null): "ok" | "warn" | "neutral" {
  if (state === "changed") return "warn";
  if (state === "unchanged") return "ok";
  return "neutral";
}

function changeStateLabel(state: string | null): string {
  if (state === null) return "Not collected";
  if (state === "first_run") return "First run";
  if (state === "changed") return "Changed";
  return "Unchanged";
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
    <Table size="small">
      <TableHead>
        <TableRow>
          <TableCell>Context</TableCell>
          <TableCell>Section</TableCell>
          <TableCell>Source</TableCell>
          <TableCell align="right">Entries</TableCell>
          <TableCell />
        </TableRow>
      </TableHead>
      <TableBody>
        {index.map((entry, i) => (
          <TableRow key={`${entry.context}-${entry.section}-${entry.source ?? "n/a"}-${i}`}>
            <TableCell>{entry.context}</TableCell>
            <TableCell>{entry.section}</TableCell>
            <TableCell>{entry.source ?? "—"}</TableCell>
            <TableCell align="right">{entry.entry_count}</TableCell>
            <TableCell>
              {entry.has_override && <StatusChip tone="warn" label="override" dense />}
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
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
    <Stack spacing={0.5}>
      {overrides.map((o, i) => (
        <Typography key={`${o.context}-${o.element_path}-${i}`} variant="body2">
          {o.context} · {o.element_path}{o.panorama_source ? ` — defined by ${o.panorama_source}` : ""}
        </Typography>
      ))}
    </Stack>
  );
}

/** Monospace, scrollable viewer for the Check Point sanitized configuration text (withheld lines already removed server-side). */
function SanitizedTextViewer({ text }: { readonly text: string }) {
  return (
    <Box
      component="pre"
      sx={{
        m: 0,
        p: 1.5,
        maxHeight: 480,
        overflow: "auto",
        fontFamily: "monospace",
        fontSize: 13,
        bgcolor: "action.hover",
        borderRadius: 1,
        whiteSpace: "pre-wrap",
        wordBreak: "break-word",
      }}
    >
      {text}
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
      <M3Button emphasis="outlined" icon="download" onClick={handleClick} disabled={phase === "submitting" || phase === "polling"}>
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
 * The detail panels for one selected device's configuration (WORKER.md
 * "Frontend"): fetches `GET /devices/{id}/configuration`, and the Check
 * Point sanitized text separately (only when available) so a large text
 * body is never pulled for a Palo Alto device. Remounted by its caller
 * (`key={deviceId}`) on every selection change, mirroring {@code
 * InventoryPanels.DeviceInventoryPanels}.
 */
export function DeviceConfigurationPanels({ deviceId }: { readonly deviceId: string }) {
  const configurationFetch = useFetchOnMount<DeviceConfiguration>(
    () => getDeviceConfiguration(deviceId),
    describeApiError,
  );
  const configuration = configurationFetch.data;
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

  return (
    <Stack spacing={1.5}>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 1.5, flexWrap: "wrap" }}>
        <Stack direction="row" spacing={1} alignItems="center">
          <StatusChip
            tone={changeStateTone(configuration?.change_state ?? null)}
            label={changeStateLabel(configuration?.change_state ?? null)}
            dense
          />
          <Typography variant="caption" color="text.secondary">
            {hashPrefix(configuration?.canonical_hash ?? null)}
          </Typography>
        </Stack>
        <ConfigurationCollectNowButton deviceId={deviceId} onCollected={configurationFetch.refresh} />
      </Box>
      <M3Tabs
        ariaLabel="Configuration detail"
        tabs={[
          {
            label: "Index",
            panel: (
              <Stack spacing={1}>
                <Typography variant="body2" color="text.secondary">
                  {configuration?.withheld_line_count ?? 0} secret-bearing line(s) withheld from the sanitized view.
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
            label: "Sanitized text",
            panel: configuration?.sanitized_text_available
              ? (
                textError
                  ? <EmptyPanel title="Sanitized text unavailable" body={textError} />
                  : sanitizedText === null
                    ? <EmptyPanel title="Sanitized text" body="Loading…" />
                    : <SanitizedTextViewer text={sanitizedText} />
              )
              : (
                <EmptyPanel
                  title="No sanitized text"
                  body="The sanitized text view exists only for Check Point's show configuration read."
                />
              ),
          },
          {
            label: "Overrides",
            panel: <OverridesNote overrides={overrides} />,
          },
        ]}
      />
    </Stack>
  );
}
