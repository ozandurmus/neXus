import { useState } from "react";
import Box from "@mui/material/Box";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

import { ScreenHeader, EmptyPanel, ScreenRoot } from "../shell/ScreenLayout";
import { M3Button, StatusChip } from "../shell/M3Widgets";
import { m3 } from "../theme/m3Theme";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import { getDeviceWorkspace, type DeviceWorkspaceView, type ActionAffordanceView, type TransportSummary, type ApiError } from "../auth/adminApi";

function describeApiError(err: unknown): string {
  const apiErr = err as Partial<ApiError>;
  const serverError = typeof apiErr.body?.error === "string" ? (apiErr.body.error as string) : undefined;
  if (serverError) return serverError;
  return `request failed${apiErr.status ? ` (status ${apiErr.status})` : ""}`;
}

function vendorLabel(vendorHint: string): string {
  return vendorHint === "check_point" ? "Check Point" : vendorHint === "palo_alto" ? "Palo Alto" : vendorHint;
}

function enrollmentStateLabel(state: string): string {
  if (state === "DRAFT") return "Draft";
  if (state === "ENROLLED") return "Enrolled";
  if (state === "UNREACHABLE") return "Unreachable";
  if (state === "DEGRADED") return "Degraded";
  return "Not evaluable";
}

function enrollmentStateCopy(state: string): string {
  if (state === "DRAFT") return "Registered. Not confirmed reachable. Never collected from.";
  if (state === "ENROLLED") return "A past authorized confirmation succeeded.";
  if (state === "UNREACHABLE") return "The most recent recorded contact failed.";
  if (state === "DEGRADED") return "Reachable at last contact, with a non-fatal capability-level signal recorded.";
  return "The recorded enrollment state is not recognized. No action on this device is treated as eligible.";
}

function AffordanceAction({ actionId, affordance }: { actionId: string; affordance: ActionAffordanceView }) {
  const isRefused = affordance.outcome !== "PERMITTED";

  const getReasonMessage = () => {
    if (affordance.outcome === "DENIED") return "You may not.";
    if (affordance.reasonCode === "role_token_unbound") return "This capability has no binding.";
    if (affordance.reasonCode === "actor_group_set_stale") return "Your group set is stale.";
    if (affordance.reasonCode === "actor_not_in_required_group") return "You are not in the required group.";
    return affordance.reasonCode ? affordance.reasonCode : "Action refused.";
  };

  const handleAction = async () => {
    if (isRefused) {
      alert(`Refused (403): ${affordance.outcome} - ${getReasonMessage()}`);
      return;
    }
  };

  return (
    <Box sx={{ display: "flex", alignItems: "center", gap: 2, p: 1.5, border: "1px solid", borderColor: "divider", borderRadius: 2 }}>
      <M3Button emphasis="outlined" onClick={handleAction} disabled={false}>
        {actionId}
      </M3Button>
      {isRefused && (
        <Typography variant="body2" color="error">
          Refused by {affordance.authority || "authority"}: {getReasonMessage()}
        </Typography>
      )}
    </Box>
  );
}

export function DeviceWorkspaceScreen({ deviceId }: { readonly deviceId: string }) {
  const { data: device, error, refresh } = useFetchOnMount(
    () => getDeviceWorkspace(deviceId),
    describeApiError,
  );

  return (
    <ScreenRoot>
      <ScreenHeader
        title="Device Workspace"
        subtitle={device ? device.deviceId : "Loading..."}
        actions={<M3Button emphasis="outlined" href="?screen=inventory">Back to Inventory</M3Button>}
      />
      <Box sx={{ p: 3, display: "flex", flexDirection: "column", gap: 3 }}>
        {error && (
          <EmptyPanel title="Workspace unavailable" body={error}>
            <Box sx={{ display: "flex", justifyContent: "flex-end" }}>
              <M3Button emphasis="outlined" onClick={refresh}>Retry</M3Button>
            </Box>
          </EmptyPanel>
        )}
        {!error && !device && <Typography>Loading device details...</Typography>}
        {device && (
          <>
            <Box sx={{ bgcolor: m3.scHigh, p: 2, borderRadius: 2 }}>
              <Typography variant="subtitle1" sx={{ mb: 2, fontWeight: "medium" }}>Identity</Typography>
              <Stack spacing={1}>
                <Typography variant="body2"><strong>Device ID:</strong> {device.deviceId}</Typography>
                <Typography variant="body2"><strong>Vendor:</strong> {vendorLabel(device.vendorHint)} <em>(hint)</em></Typography>
                <Typography variant="body2"><strong>Registered:</strong> {new Date(device.createdAt).toLocaleString()}</Typography>
                <Typography variant="body2"><strong>Source:</strong> {device.registrationSource}</Typography>
                <Typography variant="body2"><strong>Test Target:</strong> {device.isTestTarget ? "Yes" : "No"}</Typography>
                <Typography variant="body2"><strong>Credential:</strong> {device.credentialConfigured ? "Configured" : "Not configured"}</Typography>
              </Stack>
            </Box>

            <Box sx={{ bgcolor: m3.scHigh, p: 2, borderRadius: 2 }}>
              <Typography variant="subtitle1" sx={{ mb: 2, fontWeight: "medium" }}>Transport Summary</Typography>
              <Stack spacing={1}>
                <Typography variant="body2"><strong>Transport:</strong> {device.transport.presence}</Typography>
                {device.transport.transports.length > 0 && (
                  <Box sx={{ pl: 2 }}>
                    {device.transport.transports.map((t) => (
                      <Typography key={t.endpointId} variant="body2">
                        {t.transportKind} ({t.endpointId})
                      </Typography>
                    ))}
                  </Box>
                )}
                <Typography variant="body2" color="text.secondary">
                  The management address is held by the service and is not displayed.
                </Typography>
              </Stack>
            </Box>

            <Box sx={{ bgcolor: m3.scHigh, p: 2, borderRadius: 2 }}>
              <Typography variant="subtitle1" sx={{ mb: 2, fontWeight: "medium" }}>Enrollment State</Typography>
              <Stack spacing={1.5}>
                <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
                  <StatusChip tone="neutral" label={enrollmentStateLabel(device.enrollmentState)} />
                  {device.disabled && <StatusChip tone="error" label="Disabled" />}
                </Box>
                <Typography variant="body2">{enrollmentStateCopy(device.enrollmentState)}</Typography>
              </Stack>
            </Box>

            <Box sx={{ bgcolor: m3.scHigh, p: 2, borderRadius: 2 }}>
              <Typography variant="subtitle1" sx={{ mb: 2, fontWeight: "medium" }}>Actions & Affordances</Typography>
              <Stack spacing={1.5}>
                {Object.entries(device.actionAffordance).map(([actionId, affordance]) => (
                  <AffordanceAction key={actionId} actionId={actionId} affordance={affordance} />
                ))}
                {Object.keys(device.actionAffordance).length === 0 && (
                  <Typography variant="body2" color="text.secondary">No actions available.</Typography>
                )}
              </Stack>
            </Box>

            <Box sx={{ bgcolor: m3.scHigh, p: 2, borderRadius: 2 }}>
              <Typography variant="subtitle1" sx={{ mb: 2, fontWeight: "medium" }}>Collected Data</Typography>
              <Typography variant="body2" color="text.secondary">
                Collection is not yet direction-ed for this device's vendor (PO Gate 2026-09-12).
              </Typography>
            </Box>
          </>
        )}
      </Box>
    </ScreenRoot>
  );
}
