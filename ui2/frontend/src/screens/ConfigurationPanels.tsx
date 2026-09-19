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
} from "../auth/adminApi";
import { useFetchOnMount } from "../shell/useFetchOnMount";

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

interface SettingAlignmentRow {
  readonly setting: string;
  readonly expected: string;
  readonly effective01: string;
  readonly effective02: string;
  readonly state: "Aligned" | "Member-specific" | "Local override" | "Difference observed" | "Effective drift";
  readonly subtext?: string;
}

const DEFAULT_ALIGNMENT_SETTINGS: readonly SettingAlignmentRow[] = [
  {
    setting: "Cluster VIP · eth0",
    expected: "192.0.2.1/24",
    effective01: "192.0.2.1/24",
    effective02: "192.0.2.1/24",
    state: "Aligned",
  },
  {
    setting: "Hostname",
    expected: "fw-ist-core-{member}",
    effective01: "fw-ist-core-01",
    effective02: "fw-ist-core-02",
    state: "Member-specific",
  },
  {
    setting: "Member IP · eth0",
    expected: "192.0.2.{2,3}/24",
    effective01: "192.0.2.2/24",
    effective02: "192.0.2.3/24",
    state: "Member-specific",
  },
  {
    setting: "NTP · server 1",
    expected: "192.0.2.12",
    effective01: "192.0.2.12",
    effective02: "192.0.2.12",
    state: "Aligned",
  },
  {
    setting: "NTP · server 2",
    expected: "192.0.2.13",
    effective01: "192.0.2.13",
    effective02: "192.0.2.99",
    state: "Effective drift",
    subtext: "Unexplained since 2026-09-06 · no local-override record",
  },
  {
    setting: "DNS · primary",
    expected: "198.51.100.53",
    effective01: "198.51.100.53",
    effective02: "198.51.100.53",
    state: "Aligned",
  },
  {
    setting: "SNMP · trap receiver",
    expected: "198.51.100.10",
    effective01: "198.51.100.11",
    effective02: "198.51.100.11",
    state: "Local override",
    subtext: "Recorded CHG-4471 · monitoring migration",
  },
  {
    setting: "Syslog · target",
    expected: "203.0.113.20:514",
    effective01: "203.0.113.20:514",
    effective02: "203.0.113.20:514",
    state: "Aligned",
  },
  {
    setting: "Login banner",
    expected: "— not in intent",
    effective01: "Authorized use only…",
    effective02: "Authorized use only…",
    state: "Difference observed",
    subtext: "Unclassified · not yet mapped to intent",
  },
  {
    setting: "SSH · ciphers",
    expected: "aes256-gcm, aes128-gcm",
    effective01: "aes256-gcm, aes128-gcm",
    effective02: "aes256-gcm, aes128-gcm",
    state: "Aligned",
  },
];

function alignmentTone(state: SettingAlignmentRow["state"]): "ok" | "mem" | "warn" | "attn" | "bad" {
  switch (state) {
    case "Aligned":
      return "ok";
    case "Member-specific":
      return "mem";
    case "Local override":
      return "warn";
    case "Difference observed":
      return "attn";
    case "Effective drift":
      return "bad";
  }
}

function AlignmentTable({
  settings,
  onOpenEvidence,
}: {
  readonly settings: readonly SettingAlignmentRow[];
  readonly onOpenEvidence?: () => void;
}) {
  return (
    <Box sx={{ bgcolor: m3.scLowest, border: "1px solid", borderColor: m3.outlineVar, borderRadius: "12px", overflow: "hidden" }}>
      <Table size="small">
        <TableHead sx={{ bgcolor: m3.scLow }}>
          <TableRow>
            <TableCell sx={{ fontWeight: 600, fontSize: 12, color: m3.onSurfaceVar, py: 1.25 }}>Setting</TableCell>
            <TableCell sx={{ fontWeight: 600, fontSize: 12, color: m3.onSurfaceVar, py: 1.25 }}>Expected · CMA intent</TableCell>
            <TableCell sx={{ fontWeight: 600, fontSize: 12, color: m3.onSurfaceVar, py: 1.25 }}>Effective · member 01</TableCell>
            <TableCell sx={{ fontWeight: 600, fontSize: 12, color: m3.onSurfaceVar, py: 1.25 }}>Effective · member 02</TableCell>
            <TableCell sx={{ fontWeight: 600, fontSize: 12, color: m3.onSurfaceVar, py: 1.25 }}>State</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {settings.map((row, idx) => (
            <TableRow key={`${row.setting}-${idx}`} sx={{ "&:hover": { bgcolor: m3.scLow } }}>
              <TableCell sx={{ fontWeight: 600, fontSize: 13, color: m3.onSurface, py: 1 }}>{row.setting}</TableCell>
              <TableCell sx={{ fontFamily: "monospace", fontSize: 12, color: m3.onSurfaceVar, py: 1 }}>{row.expected}</TableCell>
              <TableCell sx={{ fontFamily: "monospace", fontSize: 12, color: m3.onSurfaceVar, py: 1 }}>{row.effective01}</TableCell>
              <TableCell sx={{ fontFamily: "monospace", fontSize: 12, color: m3.onSurfaceVar, py: 1 }}>{row.effective02}</TableCell>
              <TableCell sx={{ py: 1 }}>
                <StatusChip tone={alignmentTone(row.state)} label={row.state} dense />
                {row.subtext && (
                  <Typography variant="caption" sx={{ display: "block", mt: 0.5, fontSize: 11, color: "text.secondary", fontStyle: "italic" }}>
                    {row.subtext}
                  </Typography>
                )}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", p: 1.5, bgcolor: m3.scLow, borderTop: `1px solid ${m3.outlineVar}` }}>
        <Typography variant="caption" color="text.secondary">
          Showing 10 of 26 settings. Red is reserved for unexplained drift and failure; expected member differences carry no warning.
        </Typography>
        {onOpenEvidence && (
          <M3Button emphasis="text" onClick={onOpenEvidence}>
            Open evidence
          </M3Button>
        )}
      </Box>
    </Box>
  );
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
export function DeviceConfigurationPanels({ deviceId }: { readonly deviceId: string }) {
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
  const [activeTabIndex, setActiveTabIndex] = useState<number>(0);

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
  const isChanged = configuration?.change_state === "changed";
  const deviceName = device?.facts?.hostname ?? configuration?.device_id ?? deviceId;
  const vendorLabel = configuration?.vendor === "palo_alto" ? "Palo Alto Networks" : "Check Point ClusterXL";
  const collectedDate = configuration?.collected_at ? configuration.collected_at.slice(0, 10) : "2026-09-05";
  const collectedTime = configuration?.collected_at ? configuration.collected_at.slice(11, 16) : "06:41";

  return (
    <Stack spacing={2}>
      {/* Top Detail Header matching PDF Page 3 */}
      <Box sx={{ p: 2.5, bgcolor: m3.scLowest, border: "1px solid", borderColor: m3.outlineVar, borderRadius: "16px" }}>
        <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", gap: 2, flexWrap: "wrap" }}>
          <Box sx={{ display: "flex", flexDirection: "column", gap: 0.5 }}>
            <Typography variant="caption" sx={{ color: "text.secondary", fontSize: 12, fontWeight: 500 }}>
              Configuration · {vendorLabel}
            </Typography>
            <Typography variant="h2" sx={{ fontSize: 24, fontWeight: 700, color: m3.onSurface, letterSpacing: "-0.5px" }}>
              {deviceName}
            </Typography>
            <Typography variant="body2" sx={{ color: "text.secondary", fontSize: 12 }}>
              Policy IST-Core-Standard installed {collectedDate} · intent snapshot cma-ist 06:40 · effective evidence {collectedTime}
            </Typography>
          </Box>
          <Box sx={{ display: "flex", alignItems: "center", gap: 1.5, flexWrap: "wrap" }}>
            {isChanged ? (
              <Box
                sx={{
                  display: "inline-flex",
                  alignItems: "center",
                  gap: 0.75,
                  px: 1.5,
                  py: 0.75,
                  borderRadius: "16px",
                  bgcolor: m3.errorContainer,
                  color: m3.onErrorContainer,
                  fontSize: 13,
                  fontWeight: 600,
                }}
              >
                <span>⚠</span> 1 effective drift · needs review
              </Box>
            ) : (
              <Box
                sx={{
                  display: "inline-flex",
                  alignItems: "center",
                  gap: 0.75,
                  px: 1.5,
                  py: 0.75,
                  borderRadius: "16px",
                  bgcolor: m3.successContainer,
                  color: m3.onSuccessContainer,
                  fontSize: 13,
                  fontWeight: 600,
                }}
              >
                <span>✓</span> Aligned · 0 drift
              </Box>
            )}
            <M3Button emphasis="outlined" icon="download">Export evidence</M3Button>
            <ConfigurationCollectNowButton deviceId={deviceId} onCollected={configurationFetch.refresh} />
          </Box>
        </Box>
      </Box>

      {/* Tabs matching PDF Page 3 */}
      <M3Tabs
        ariaLabel="Configuration detail"
        value={activeTabIndex}
        onChange={setActiveTabIndex}
        tabs={[
          {
            label: "Alignment",
            panel: (
              <Stack spacing={2}>
                {/* Summary State Pills Bar matching PDF Page 3 */}
                <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
                  <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", gap: 1 }}>
                    <StatusChip tone="ok" label="Aligned 21" />
                    <StatusChip tone="mem" label="Member-specific 2" />
                    <StatusChip tone="warn" label="Local override 1" />
                    <StatusChip tone="attn" label="Difference observed 1" />
                    <StatusChip tone="bad" label="Effective drift 1" />
                  </Stack>
                  <M3Button emphasis="text">≡ All classifications</M3Button>
                </Box>

                {/* Main Settings Alignment Table matching PDF Page 3 */}
                <AlignmentTable
                  settings={DEFAULT_ALIGNMENT_SETTINGS}
                  onOpenEvidence={() => setActiveTabIndex(2)}
                />

                {/* Section Index Summary Breakdown */}
                <Box sx={{ mt: 1 }}>
                  <Typography variant="caption" color="text.secondary" sx={{ display: "block", mb: 1 }}>
                    {configuration?.withheld_line_count ?? 0} secret-bearing line(s) withheld from the sanitized view.
                  </Typography>
                  <IndexTable
                    index={index}
                    emptyTitle="No configuration index"
                    emptyBody="This device has not been collected yet. Use Collect now to read its configuration."
                  />
                </Box>
              </Stack>
            ),
          },
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
                    : (
                      <SanitizedTextViewer
                        text={sanitizedText}
                        withheldCount={configuration.withheld_line_count}
                        canonicalHash={configuration.canonical_hash}
                      />
                    )
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
          {
            label: "Overview",
            panel: (
              <Stack spacing={2}>
                <Box sx={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(220px, 1fr))", gap: 2 }}>
                  <Box sx={{ p: 2, bgcolor: m3.scLowest, border: "1px solid", borderColor: m3.outlineVar, borderRadius: "12px" }}>
                    <Typography variant="caption" color="text.secondary">Configuration State</Typography>
                    <Typography variant="h3" sx={{ mt: 0.5 }}>{changeStateLabel(configuration?.change_state ?? null)}</Typography>
                    <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
                      Hash: {hashPrefix(configuration?.canonical_hash ?? null)}
                    </Typography>
                  </Box>
                  <Box sx={{ p: 2, bgcolor: m3.scLowest, border: "1px solid", borderColor: m3.outlineVar, borderRadius: "12px" }}>
                    <Typography variant="caption" color="text.secondary">Withheld Secrets</Typography>
                    <Typography variant="h3" sx={{ mt: 0.5 }}>{configuration?.withheld_line_count ?? 0}</Typography>
                    <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>Keywords masked server-side</Typography>
                  </Box>
                  <Box sx={{ p: 2, bgcolor: m3.scLowest, border: "1px solid", borderColor: m3.outlineVar, borderRadius: "12px" }}>
                    <Typography variant="caption" color="text.secondary">Index Sections</Typography>
                    <Typography variant="h3" sx={{ mt: 0.5 }}>{index.length}</Typography>
                    <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>Governed Gaia AST groups</Typography>
                  </Box>
                </Box>
              </Stack>
            ),
          },
          {
            label: "History",
            panel: (
              <EmptyPanel
                title="Configuration history"
                body="Previous revisions collected across active verification cycles. Revisions are immutable."
              />
            ),
          },
          {
            label: "Backup",
            panel: (
              <EmptyPanel
                title="Recovery artifacts"
                body="Class 1 controlled backup snapshot references for this target."
              />
            ),
          },
        ]}
      />
    </Stack>
  );
}

