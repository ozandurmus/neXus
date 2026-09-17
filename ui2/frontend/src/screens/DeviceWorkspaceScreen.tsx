import { useState } from "react";
import Box from "@mui/material/Box";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

import { ScreenHeader, EmptyPanel, ScreenRoot } from "../shell/ScreenLayout";
import { M3Button, StatusChip } from "../shell/M3Widgets";
import { m3 } from "../theme/m3Theme";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import { getDeviceWorkspace, type DeviceWorkspaceView, type ActionAffordance, type TransportSummary, type ApiError } from "../auth/adminApi";
import { enrollmentStateLabel } from "../shell/deviceCopy";

function describeApiError(err: unknown): string {
  const apiErr = err as Partial<ApiError>;
  const serverError = typeof apiErr.body?.error === "string" ? (apiErr.body.error as string) : undefined;
  if (serverError) return serverError;
  return `request failed${apiErr.status ? ` (status ${apiErr.status})` : ""}`;
}

function vendorLabel(vendorHint: string): string {
  return vendorHint === "check_point" ? "Check Point" : vendorHint === "palo_alto" ? "Palo Alto" : vendorHint;
}

function enrollmentStateCopy(state: string): string {
  if (state === "DRAFT") return "Registered. Not confirmed reachable. Never collected from.";
  if (state === "ENROLLED") return "A past authorized confirmation succeeded.";
  if (state === "UNREACHABLE") return "The most recent recorded contact failed.";
  if (state === "DEGRADED") return "Reachable at last contact, with a non-fatal capability-level signal recorded.";
  return "The recorded enrollment state is not recognized. No action on this device is treated as eligible.";
}

function AffordanceAction({ actionId, affordance }: { actionId: string; affordance: ActionAffordance }) {
  const isRefused = affordance.outcome !== "PERMITTED";

  const getReasonMessage = () => {
    if (affordance.outcome === "DENIED") return "You may not.";
    if (affordance.reason_code === "role_token_unbound") return "This capability has no binding.";
    if (affordance.reason_code === "actor_group_set_stale") return "Your group set is stale.";
    if (affordance.reason_code === "actor_not_in_required_group") return "You are not in the required group.";
    return affordance.reason_code ? affordance.reason_code : "Action refused.";
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
        subtitle={device ? device.device_id : "Loading..."}
        actions={<M3Button emphasis="outlined" href="?screen=inventory">Back to Inventory</M3Button>}
      />
      
      {error && (
        <Box sx={{ p: 3 }}>
          <EmptyPanel title="Workspace unavailable" body={error}>
            <Box sx={{ display: "flex", justifyContent: "flex-end" }}>
              <M3Button emphasis="outlined" onClick={refresh}>Retry</M3Button>
            </Box>
          </EmptyPanel>
        </Box>
      )}
      
      {!error && !device && (
        <Box sx={{ p: 3 }}>
          <Typography>Loading device details...</Typography>
        </Box>
      )}
      
      {device && (
        <Box sx={{ display: "flex", flexGrow: 1, overflow: "hidden" }}>
          {/* Info Sidebar */}
          <Box sx={{ width: 350, flexShrink: 0, borderRight: "1px solid", borderColor: "divider", overflowY: "auto", p: 3, bgcolor: m3.scLowest, display: "flex", flexDirection: "column", gap: 4 }}>
            <Box>
              <Typography variant="subtitle2" color="text.secondary" sx={{ mb: 1.5, textTransform: 'uppercase' }}>Identity</Typography>
              <Stack spacing={1}>
                <Typography variant="body2"><strong>Device ID:</strong> {device.device_id}</Typography>
                <Typography variant="body2"><strong>Vendor:</strong> {vendorLabel(device.vendor_hint)} <em>(hint)</em></Typography>
                <Typography variant="body2"><strong>Registered:</strong> {new Date(device.created_at).toLocaleString()}</Typography>
                <Typography variant="body2"><strong>Source:</strong> {device.registration_source}</Typography>
                <Typography variant="body2"><strong>Test Target:</strong> {device.is_test_target ? "Yes" : "No"}</Typography>
                <Typography variant="body2"><strong>Credential:</strong> {device.credential_configured ? "Configured" : "Not configured"}</Typography>
              </Stack>
            </Box>

            <Box>
              <Typography variant="subtitle2" color="text.secondary" sx={{ mb: 1.5, textTransform: 'uppercase' }}>Enrollment State</Typography>
              <Stack spacing={1.5}>
                <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
                  <StatusChip tone="neutral" label={enrollmentStateLabel(device.enrollment_state)} />
                  {device.disabled && <StatusChip tone="bad" label="Disabled" />}
                </Box>
                <Typography variant="body2">{enrollmentStateCopy(device.enrollment_state)}</Typography>
              </Stack>
            </Box>

            <Box>
              <Typography variant="subtitle2" color="text.secondary" sx={{ mb: 1.5, textTransform: 'uppercase' }}>Transport Summary</Typography>
              <Stack spacing={1}>
                <Typography variant="body2"><strong>Transport:</strong> {device.transport.presence}</Typography>
                {device.transport.transports.length > 0 && (
                  <Box sx={{ pl: 1 }}>
                    {device.transport.transports.map((t) => (
                      <Typography key={t.endpoint_id} variant="body2">
                        • {t.transport_kind} ({t.endpoint_id})
                      </Typography>
                    ))}
                  </Box>
                )}
                <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
                  The management address is held by the service and is not displayed.
                </Typography>
              </Stack>
            </Box>

            <Box>
              <Typography variant="subtitle2" color="text.secondary" sx={{ mb: 1.5, textTransform: 'uppercase' }}>Collected Data</Typography>
              <Typography variant="body2" color="text.secondary">
                Collection is not yet direction-ed for this device's vendor (PO Gate 2026-09-12).
              </Typography>
            </Box>
          </Box>

          {/* Task Slider / Action Area */}
          <Box sx={{ flexGrow: 1, overflowY: "auto", p: 4, display: "flex", flexDirection: "column", gap: 3, bgcolor: m3.scHigh }}>
            <Typography variant="h6" sx={{ fontWeight: "medium" }}>Tasks & Affordances</Typography>
            <Stack spacing={2} sx={{ maxWidth: 800 }}>
              {Object.entries(device.action_affordance).map(([actionId, affordance]) => (
                <AffordanceAction key={actionId} actionId={actionId} affordance={affordance} />
              ))}
              {Object.keys(device.action_affordance).length === 0 && (
                <Typography variant="body1" color="text.secondary">No actions available for this device.</Typography>
              )}
            </Stack>
          </Box>
        </Box>
      )}
    </ScreenRoot>
  );
}
