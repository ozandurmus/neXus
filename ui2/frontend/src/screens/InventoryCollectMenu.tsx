import { useEffect, useMemo, useRef, useState } from "react";
import Box from "@mui/material/Box";
import Menu from "@mui/material/Menu";
import MenuItem from "@mui/material/MenuItem";
import Typography from "@mui/material/Typography";
import { PLANE_COLLECT, planeGranted } from "../shell/actionAccess";
import { M3Button } from "../shell/M3Widgets";
import type { ApiError } from "../auth/adminApi";
import { getDevice, requestInventoryCollect, requestConfigurationCollect, requestSystemLogCollect, retryDeviceConfirm, collectDeviceBackup } from "../auth/adminApi";
import { useSession } from "../auth/SessionContext";
import { isTerminalJobState, jobPhaseLabel } from "../shell/deviceCopy";
import { vendorDisplayName } from "../shell/States";
import { PLANE_OPERATIONS } from "../shell/actionAccess";
import { hasBackupMethod } from "./backupCounts";
import { describeSystemLogCollectError, describeSystemLogCollectOutcome } from "./DeviceLogsPanel";

const POLL_INTERVAL_MS = 1750;

export function describeApiError(err: unknown): string {
  const apiErr = err as Partial<ApiError>;
  const code = typeof apiErr.body?.code === "string" ? (apiErr.body.code as string) : undefined;
  const reason = typeof apiErr.body?.reason === "string" ? (apiErr.body.reason as string) : undefined;
  const reasonCode = typeof apiErr.body?.reason_code === "string" ? (apiErr.body.reason_code as string) : undefined;
  if (code === "DEVICE_NOT_ELIGIBLE" && reason && reason.includes("DRAFT")) {
    return "Device is pending enrollment confirmation. Inventory can be collected once enrolled.";
  }
  if (code && reason) return `${code}: ${reason}`;
  if (apiErr.body?.error === "VALIDATION_FAILED") {
    if (reasonCode === "DEVICE_NOT_DRAFT") {
      return "Device is already confirmed and enrolled (please refresh).";
    }
    if (reasonCode === "address_ref_invalid") {
      return "Invalid IP address or hostname. Check for whitespace or unsupported characters.";
    }
    if (reasonCode === "credential_reference_not_found") {
      return "Selected credential was not found.";
    }
    if (reasonCode === "DEVICE_NOT_FOUND") {
      return "Device not found.";
    }
    if (reasonCode) return `Validation failed: ${reasonCode}`;
  }
  if (reasonCode) return reasonCode;
  const serverError = typeof apiErr.body?.error === "string" ? (apiErr.body.error as string) : undefined;
  if (serverError) return serverError;
  return `request failed${apiErr.status ? ` (status ${apiErr.status})` : ""}`;
}

export type DeviceCollectMenuKey = "inventory" | "configuration" | "backup" | "system_logs";

export interface DeviceCollectMenuItem {
  readonly key: DeviceCollectMenuKey;
  readonly label: string;
  readonly description: string;
  readonly disabled: boolean;
  readonly disabledReason: string | null;
}

const CONFIGURATION_COLLECT_VENDORS: ReadonlySet<string> = new Set([
  "check_point",
  "palo_alto",
  "fortinet",
  "cisco_asa",
  "bluecoat",
]);

const BACKUP_COLLECT_VENDORS: ReadonlySet<string> = new Set([
  "check_point",
  "palo_alto",
  "fortinet",
  "cisco_asa",
  "infoblox",
  "radware",
  "bluecoat",
  "pulse_secure",
]);

const SYSTEM_LOG_COLLECT_VENDORS: ReadonlySet<string> = new Set([
  "check_point",
  "palo_alto",
]);

export function buildDeviceCollectMenuItems(input: {
  readonly vendorHint?: string | null;
  readonly role?: string | null;
  readonly enrollmentState?: string | null;
  readonly backupTarget?: boolean;
  readonly permissions?: readonly string[];
}): readonly DeviceCollectMenuItem[] {
  const canCollectPlane = planeGranted(input.permissions, PLANE_COLLECT);
  const canOperationsPlane = planeGranted(input.permissions, PLANE_OPERATIONS);
  const vendor = input.vendorHint ?? null;
  const role = input.role ?? null;
  const isDraft = input.enrollmentState === "DRAFT";
  const items: DeviceCollectMenuItem[] = [];

  if (canCollectPlane) {
    items.push({
      key: "inventory",
      label: "Inventory",
      description: "reads the device's interfaces, routes and platform facts",
      disabled: false,
      disabledReason: null,
    });

    const configDisabledReason = (() => {
      if (isDraft) return "Device enrollment is not confirmed yet";
      if (vendor && !CONFIGURATION_COLLECT_VENDORS.has(vendor)) {
        return `Unsupported vendor (${vendorDisplayName(vendor)} has no configuration collection)`;
      }
      if (role === "management_server" && vendor !== "check_point") {
        return `Configuration collection is disabled on ${vendor ? vendorDisplayName(vendor) : "this"} management server (unsupported role)`;
      }
      if (role && role !== "gateway" && !(role === "management_server" && vendor === "check_point")) {
        return `Unsupported role (${role}) for configuration collection`;
      }
      return null;
    })();

    items.push({
      key: "configuration",
      label: "Configuration",
      description: "reads the device's running configuration and settings",
      disabled: configDisabledReason !== null,
      disabledReason: configDisabledReason,
    });
  }

  if (canOperationsPlane) {
    const backupDisabledReason = (() => {
      if (isDraft) return "Device enrollment is not confirmed yet";
      if (vendor && !BACKUP_COLLECT_VENDORS.has(vendor)) {
        return `Unsupported vendor (${vendorDisplayName(vendor)} has no backup collection)`;
      }
      if (vendor && !hasBackupMethod({ role: role ?? "gateway", vendor_hint: vendor })) {
        return role === "management_server"
          ? `Backup is disabled on ${vendorDisplayName(vendor)} management servers (unsupported role)`
          : `Unsupported role (${role ?? "unknown"}) for backup collection`;
      }
      if (input.backupTarget === false) {
        return "Not switched on as a backup target";
      }
      return null;
    })();

    items.push({
      key: "backup",
      label: "Backup",
      description: "reads and stores a backup archive of the device",
      disabled: backupDisabledReason !== null,
      disabledReason: backupDisabledReason,
    });

    const logsDisabledReason = (() => {
      if (isDraft) return "Device enrollment is not confirmed yet";
      if (role === "management_server") {
        return "System-log collection is disabled on management servers";
      }
      if (vendor && !SYSTEM_LOG_COLLECT_VENDORS.has(vendor)) {
        return `Unsupported vendor (${vendorDisplayName(vendor)} has no system-log collection)`;
      }
      if (role && role !== "gateway") {
        return `Unsupported role (${role}) for system-log collection`;
      }
      return null;
    })();

    items.push({
      key: "system_logs",
      label: "System logs",
      description: "reads the device's recent system log lines",
      disabled: logsDisabledReason !== null,
      disabledReason: logsDisabledReason,
    });
  }

  return items;
}

/**
 * Device "Collect" menu button: offers every manual collection (Inventory, Configuration,
 * Backup, System logs) permitted by the viewer's role, showing items the device cannot run
 * disabled with the reason in words.
 */
export function CollectNowButton({
  deviceId,
  onCollected,
  enrollmentState,
  vendorHint,
  role,
  backupTarget,
}: {
  readonly deviceId: string;
  readonly onCollected: () => void;
  readonly enrollmentState?: string;
  readonly vendorHint?: string | null;
  readonly role?: string | null;
  readonly backupTarget?: boolean;
}) {
  const session = useSession();
  const [phase, setPhase] = useState<"idle" | "submitting" | "polling" | "error">("idle");
  const [jobState, setJobState] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [statusMessage, setStatusMessage] = useState<string | null>(null);
  const [overrideEnrolled, setOverrideEnrolled] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const buttonWrapRef = useRef<HTMLDivElement | null>(null);
  const isDraft = !overrideEnrolled && enrollmentState === "DRAFT";

  useEffect(() => {
    setOverrideEnrolled(false);
    setError(null);
    setStatusMessage(null);
    setMenuOpen(false);
  }, [deviceId]);

  // Check the latest job state on mount so that past failures are immediately visible and not hanging
  useEffect(() => {
    let cancelled = false;
    getDevice(deviceId)
      .then((result) => {
        if (cancelled) return;
        if (result.enrollment_state === "ENROLLED") {
          setOverrideEnrolled(true);
        }
        if (result.job) {
          setJobState(result.job.state);
          if (!isTerminalJobState(result.job.state)) {
            setPhase("polling");
          } else if (result.job.state === "FAILED" || result.job.state === "REJECTED") {
            const reason = result.job.terminal_reason ? `: ${result.job.terminal_reason}` : "";
            setError(`Last run failed (${result.job.state})${reason}`);
          }
        }
      })
      .catch(() => {
        // ignore initial query errors
      });
    return () => {
      cancelled = true;
    };
  }, [deviceId]);

  useEffect(() => {
    if (phase !== "polling") return undefined;
    let cancelled = false;

    const tick = () => {
      getDevice(deviceId)
        .then((result) => {
          if (cancelled) return;
          if (result.enrollment_state === "ENROLLED") {
            setOverrideEnrolled(true);
          }
          setJobState(result.job?.state ?? null);
          if (result.job !== null && isTerminalJobState(result.job.state)) {
            if (result.job.state === "FAILED" || result.job.state === "REJECTED") {
              const reason = result.job.terminal_reason ? `: ${result.job.terminal_reason}` : "";
              setError(`Operation failed (${result.job.state})${reason}`);
              setPhase("error");
            } else {
              setPhase("idle");
              setError(null);
              setOverrideEnrolled(true);
              onCollected();
            }
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
  }, [phase, deviceId, onCollected]);

  const handleInventoryCollect = async () => {
    setError(null);
    setStatusMessage(null);
    setPhase("submitting");
    try {
      if (isDraft) {
        await retryDeviceConfirm(deviceId);
      } else {
        await requestInventoryCollect(deviceId);
      }
      setPhase("polling");
    } catch (err) {
      const apiErr = err as Partial<ApiError>;
      if (isDraft && apiErr.body?.error === "VALIDATION_FAILED" && apiErr.body?.reason_code === "DEVICE_NOT_DRAFT") {
        setOverrideEnrolled(true);
        setPhase("idle");
        setError(null);
        onCollected();
        return;
      }
      setError(describeApiError(err));
      setPhase("error");
    }
  };

  const handleSelectItem = async (key: DeviceCollectMenuKey) => {
    setMenuOpen(false);
    if (key === "inventory") {
      await handleInventoryCollect();
      return;
    }
    setError(null);
    setStatusMessage(null);
    setPhase("submitting");
    try {
      if (key === "configuration") {
        await requestConfigurationCollect(deviceId);
        setStatusMessage("Configuration collection admitted.");
        setPhase("polling");
        onCollected();
        return;
      }
      if (key === "backup") {
        await collectDeviceBackup(deviceId, "Manual backup from device Collect menu");
        setStatusMessage("Backup request admitted.");
        setPhase("idle");
        onCollected();
        return;
      }
      if (key === "system_logs") {
        const outcome = await requestSystemLogCollect(deviceId);
        const msg = describeSystemLogCollectOutcome(outcome);
        if (msg.tone === "bad") {
          setError(msg.text);
          setPhase("error");
        } else {
          setStatusMessage(msg.text);
          setPhase("idle");
        }
        onCollected();
      }
    } catch (err) {
      if (key === "system_logs") {
        setError(describeSystemLogCollectError(err).text);
      } else {
        setError(describeApiError(err));
      }
      setPhase("error");
    }
  };

  const menuItems = useMemo(
    () =>
      buildDeviceCollectMenuItems({
        vendorHint,
        role,
        enrollmentState: isDraft ? "DRAFT" : "ENROLLED",
        backupTarget,
        permissions: session?.permissions,
      }),
    [vendorHint, role, isDraft, backupTarget, session?.permissions],
  );

  const isRunning = phase === "submitting" || phase === "polling";
  const buttonLabel = isRunning
    ? isDraft
      ? `Confirming (${jobPhaseLabel(jobState ?? "REQUESTED")})...`
      : jobPhaseLabel(jobState ?? "REQUESTED")
    : isDraft
    ? error
      ? "Retry confirmation"
      : "Confirm enrollment"
    : "Collect";

  return (
    <Box ref={buttonWrapRef} sx={{ display: "flex", flexDirection: "column", gap: 0.5, alignItems: "flex-start" }}>
      <M3Button
        deviceJob
        plane={PLANE_COLLECT}
        emphasis="outlined"
        icon="download"
        aria-haspopup={isDraft ? undefined : "menu"}
        aria-expanded={isDraft ? undefined : menuOpen}
        onClick={() => {
          if (isDraft) {
            void handleInventoryCollect();
          } else {
            setMenuOpen(true);
          }
        }}
        disabled={isRunning}
      >
        {buttonLabel}
      </M3Button>
      {!isDraft && (
        <Menu
          anchorEl={buttonWrapRef.current}
          open={menuOpen}
          onClose={() => setMenuOpen(false)}
          MenuListProps={{ "aria-label": "Collect menu" }}
        >
          {menuItems.map((item) => (
            <MenuItem
              key={item.key}
              disabled={item.disabled || isRunning}
              data-collect-item={item.key}
              onClick={() => void handleSelectItem(item.key)}
              sx={{
                display: "flex",
                flexDirection: "column",
                alignItems: "flex-start",
                gap: 0.25,
                py: 1,
                whiteSpace: "normal",
                maxWidth: 440,
              }}
            >
              <Typography variant="body2" sx={{ fontWeight: 600 }}>
                {item.label}: {item.description}
              </Typography>
              {item.disabledReason && (
                <Typography
                  variant="caption"
                  color="text.secondary"
                  data-testid={`collect-disabled-${item.key}`}
                >
                  {item.disabledReason}
                </Typography>
              )}
            </MenuItem>
          ))}
        </Menu>
      )}
      {isDraft && !error && !isRunning && (
        <Typography variant="body2" color="text.secondary">
          Device is pending enrollment confirmation.
        </Typography>
      )}
      {statusMessage && !error && (
        <Typography variant="body2" color="success.main" data-testid="device-collect-status">
          {statusMessage}
        </Typography>
      )}
      {error && (
        <Typography variant="body2" color="error" sx={{ maxWidth: 450, wordBreak: "break-word" }}>
          {error}
        </Typography>
      )}
    </Box>
  );
}

