import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Typography from "@mui/material/Typography";

import { StatePanel } from "../../shell/States";
import { StatusPill } from "../../shell/StatusPill";
import { Timestamp } from "../../shell/Timestamp";
import { m3 } from "../../theme/m3Theme";
import { DENSITY, LABEL_UPPER, RADIUS, TYPE } from "../../theme/tokens";
import {
  describeKillSwitchBanner,
  describeLiveness,
  describeNoChangeEvents,
  describePendingDefer,
  eventTriggerVendorLabel,
  formatCoverageRatio,
  isCoverageKnown,
  isProjectionMasked,
  safeUnitDisplayName,
  unitKindLabel,
  type EventTriggerKillSwitchSummary,
  type EventTriggerProjection,
} from "./eventTriggerModel";

export interface EventTriggerStatusPanelProps {
  readonly projection: EventTriggerProjection | null | undefined;
  readonly killSwitch: EventTriggerKillSwitchSummary | null | undefined;
  readonly now?: Date;
}

/**
 * Pure prop-driven read-only status panel for policy-change event-trigger liveness,
 * per-vendor live fleet coverage, kill-switch visibility, pending defer states, and
 * strictly informational `NO_CHANGE_EVENTS_N_DAYS` indicators.
 */
export function EventTriggerStatusPanel({
  projection,
  killSwitch,
  now,
}: EventTriggerStatusPanelProps) {
  const banner = describeKillSwitchBanner(killSwitch);
  const coverageEntries = projection ? Object.values(projection.fleetCoverageByVendor ?? {}) : [];
  const units = projection?.units ?? [];
  const density = DENSITY.compact;

  return (
    <Stack spacing={2} data-testid="event-trigger-status-panel">
      <Card
        role="region"
        aria-label="Event trigger kill-switch status"
        data-testid="event-trigger-kill-switch-banner"
        data-kill-switch-state={banner.status}
        sx={{
          p: 2,
          bgcolor: banner.status === "GLOBAL_HALTED" ? m3.errorContainer : banner.status === "PARTIAL_HALTED" ? m3.warningContainer : m3.scLow,
          boxShadow: "none",
          border: `1px solid ${m3.outlineVar}`,
          borderRadius: `${RADIUS.card}px`,
        }}
      >
        <Stack direction="row" spacing={1.25} alignItems="flex-start" flexWrap="wrap" useFlexGap>
          <StatusPill tone={banner.tone} word={banner.pillLabel} dense />
          <Box sx={{ flex: 1, minWidth: 220 }}>
            <Typography sx={{ ...TYPE.section, color: m3.onSurface }}>
              {banner.title}
            </Typography>
            <Typography sx={{ ...TYPE.body, color: m3.onSurfaceVar, mt: 0.25 }}>
              {banner.detail}
            </Typography>
          </Box>
        </Stack>
      </Card>

      {projection && !isProjectionMasked(projection) ? (
        <StatePanel
          variant="unknown"
          title="Masking not confirmed"
          body="The operator projection is not marked as masked, so no unit rows are shown."
          announce="status"
        />
      ) : !projection ? (
        <StatePanel
          variant="unknown"
          title="Event-trigger liveness unavailable"
          body="No operator projection snapshot has been provided."
          announce="status"
        />
      ) : (
        <>
          <Card
            role="region"
            aria-label="Per-vendor live event coverage"
            data-testid="event-trigger-fleet-coverage"
            sx={{
              p: 2,
              bgcolor: m3.scLowest,
              boxShadow: "none",
              border: `1px solid ${m3.outlineVar}`,
              borderRadius: `${RADIUS.card}px`,
            }}
          >
            <Stack spacing={1.25}>
              <Box>
                <Typography sx={{ ...TYPE.section, color: m3.onSurface }}>
                  Event-trigger pipeline liveness &amp; fleet coverage (read-only)
                </Typography>
                <Typography sx={{ ...TYPE.body, color: m3.onSurfaceVar }}>
                  Healthy live event-covered units (CONNECTED or HEARTBEAT_OK) out of total tracked units per vendor.
                </Typography>
              </Box>

              {coverageEntries.length === 0 ? (
                <Typography sx={{ ...TYPE.body, color: m3.onSurfaceVar }} data-testid="fleet-coverage-empty">
                  No vendor fleet coverage reported.
                </Typography>
              ) : (
                <Stack direction="row" spacing={1} useFlexGap flexWrap="wrap" alignItems="center">
                  {coverageEntries.map((cov) => (
                    <Box
                      key={cov.vendor}
                      data-testid={`fleet-coverage-${cov.vendor}`}
                      sx={{
                        display: "inline-flex",
                        alignItems: "center",
                        gap: 1,
                        px: 1.25,
                        py: 0.75,
                        borderRadius: `${RADIUS.pill}px`,
                        bgcolor: m3.scLow,
                        border: `1px solid ${m3.outlineVar}`,
                      }}
                    >
                      <Typography sx={{ ...TYPE.label, fontWeight: 600, color: m3.onSurface }}>
                        {eventTriggerVendorLabel(cov.vendor)}
                      </Typography>
                      <StatusPill
                        tone={isCoverageKnown(cov) ? "neutral" : "unknown"}
                        word={formatCoverageRatio(cov)}
                        dense
                      />
                      <Typography sx={{ ...TYPE.label, color: m3.onSurfaceVar }}>
                        {isCoverageKnown(cov) ? "live event-covered units" : "coverage counts unreadable"}
                      </Typography>
                    </Box>
                  ))}
                </Stack>
              )}
            </Stack>
          </Card>

          {units.length === 0 ? (
            <StatePanel
              variant="empty"
              title="No event-trigger units tracked."
              body="Scheduled 6h policy collection and 00:00 daily configuration collection remain active."
              announce="status"
            />
          ) : (
            <Box
              data-testid="event-trigger-units-table"
              data-density="compact"
              sx={{
                border: `1px solid ${m3.outlineVar}`,
                borderRadius: `${RADIUS.card}px`,
                bgcolor: m3.scLowest,
                overflow: "hidden",
              }}
            >
              <TableContainer sx={{ overflowX: "auto" }}>
                <Table
                  size="small"
                  aria-label="Event trigger unit status table"
                  sx={{
                    "& th, & td": {
                      px: `${density.gutter}px`,
                      py: `${density.cellY}px`,
                      borderColor: m3.outlineVar,
                      verticalAlign: "top",
                    },
                    "& thead th": { bgcolor: m3.scLow },
                  }}
                >
                  <TableHead>
                    <TableRow>
                      <TableCell sx={{ ...LABEL_UPPER, color: m3.onSurfaceVar }}>Unit</TableCell>
                      <TableCell sx={{ ...LABEL_UPPER, color: m3.onSurfaceVar }}>Pipeline liveness</TableCell>
                      <TableCell sx={{ ...LABEL_UPPER, color: m3.onSurfaceVar }}>Last device event</TableCell>
                      <TableCell sx={{ ...LABEL_UPPER, color: m3.onSurfaceVar }}>Last received</TableCell>
                      <TableCell sx={{ ...LABEL_UPPER, color: m3.onSurfaceVar }}>Last policy collected</TableCell>
                      <TableCell sx={{ ...LABEL_UPPER, color: m3.onSurfaceVar }}>Pending / defer</TableCell>
                      <TableCell sx={{ ...LABEL_UPPER, color: m3.onSurfaceVar }}>Change-event activity</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {units.map((row, index) => {
                      const displayName = safeUnitDisplayName(row.displayName);
                      const liveness = describeLiveness(row.livenessState);
                      const pending = describePendingDefer(row.pendingDeferState, row.pendingStatusText);
                      const silence = describeNoChangeEvents(row);
                      const rowKey = `${row.vendor}:${row.unitKind}:${displayName}:${index}`;

                      return (
                        <TableRow key={rowKey} data-testid={`event-trigger-row-${index}`}>
                          <TableCell>
                            <Stack spacing={0.25}>
                              <Typography sx={{ ...TYPE.mono, fontWeight: 600, color: m3.onSurface }}>
                                {displayName}
                              </Typography>
                              <Typography sx={{ ...TYPE.label, color: m3.onSurfaceVar }}>
                                {unitKindLabel(row.unitKind)} · {eventTriggerVendorLabel(row.vendor)}
                              </Typography>
                            </Stack>
                          </TableCell>

                          <TableCell data-liveness-state={liveness.state}>
                            <Stack spacing={0.5} alignItems="flex-start">
                              <StatusPill tone={liveness.tone} word={liveness.label} dense />
                              <Typography sx={{ ...TYPE.label, color: m3.onSurfaceVar, maxWidth: 320 }}>
                                {liveness.wording}
                              </Typography>
                            </Stack>
                          </TableCell>

                          <TableCell>
                            <Timestamp at={row.lastDeviceEventAt} now={now} />
                          </TableCell>

                          <TableCell>
                            <Timestamp at={row.lastReceivedAt} now={now} />
                          </TableCell>

                          <TableCell>
                            <Timestamp at={row.lastPolicyCollectedAt} now={now} />
                          </TableCell>

                          <TableCell data-pending-state={pending.state}>
                            {pending.isPending ? (
                              <Stack spacing={0.5} alignItems="flex-start">
                                <StatusPill
                                  tone={pending.tone}
                                  word={pending.badgeLabel}
                                  title={pending.detailText ?? undefined}
                                  dense
                                />
                                {pending.detailText ? (
                                  <Typography sx={{ ...TYPE.label, color: m3.onSurfaceVar, maxWidth: 260 }}>
                                    {pending.detailText}
                                  </Typography>
                                ) : null}
                              </Stack>
                            ) : pending.state === "UNKNOWN" ? (
                              <StatusPill tone="unknown" word="UNKNOWN" dense />
                            ) : (
                              <Typography sx={{ ...TYPE.label, color: m3.onSurfaceVar }}>
                                {pending.badgeLabel}
                              </Typography>
                            )}
                          </TableCell>

                          <TableCell>
                            {silence ? (
                              <Stack
                                spacing={0.5}
                                alignItems="flex-start"
                                data-testid="no-change-events-info"
                                data-informational="true"
                              >
                                <StatusPill tone={silence.tone} word={silence.code} dense />
                                <Typography sx={{ ...TYPE.label, color: m3.onSurfaceVar, maxWidth: 260 }}>
                                  {silence.message}
                                </Typography>
                              </Stack>
                            ) : (
                              <Typography sx={{ ...TYPE.label, color: m3.onSurfaceVar }}>—</Typography>
                            )}
                          </TableCell>
                        </TableRow>
                      );
                    })}
                  </TableBody>
                </Table>
              </TableContainer>
            </Box>
          )}
        </>
      )}
    </Stack>
  );
}
