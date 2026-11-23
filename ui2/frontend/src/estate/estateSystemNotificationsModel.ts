/**
 * Pure summary model for the Estate System "Notifications and logging" card (Concept row 28:
 * `28 | Notifications and logging | System · Notifications | Moved`).
 *
 * Parity with the standard view's `NotificationSettingsPanel` (`GET /api/v2/config/notifications`) and
 * `SystemLogSettingsPanel` (`GET /api/v2/config/system-logs`). Safe posture, switches, retentions, caps and route
 * counts only: raw syslog/SMTP hostnames, sender/recipient e-mail addresses and raw error texts never enter this model.
 */
import type {
  NotificationRouteView,
  NotificationSettingsView,
  SystemLogSettingsView,
} from "../auth/adminApi";
import { plural } from "../shell/plural";
import { formatUtc } from "../shell/time";
import { BADGE_WORD_DEFAULT, type BadgeState } from "./estateModel";
import type { SystemLine, TimedRead } from "./estateSystemModel";

const MIB = 1024 * 1024;

/** Notification routes shown before "+N more notification routes". */
export const NOTIFICATION_ROUTES_CAP = 5;

/** Standard Administration › Notifications and logging › Notifications tab (`standard=1`). */
export const NOTIFICATIONS_STANDARD_HREF =
  "?screen=administration&tab=platform&section=notifications&standard=1";

/** Standard Administration › Notifications and logging › Log collection tab (`standard=1`). */
export const SYSLOG_SETTINGS_STANDARD_HREF =
  "?screen=administration&tab=platform&section=system-logs&standard=1";

export const NOTIFICATION_ROUTE_TITLES: Readonly<Record<NotificationRouteView["type"], string>> = {
  admin_event: "Administration changes",
  login_security: "Sign-in and access",
  backup_failure: "Backup failures",
  job_failure: "Other job failures",
  config_change: "Configuration changes",
  compliance_regression: "Compliance regressions",
  device_health: "Device reachability",
  security_scan: "Security scans",
  device_crash_file: "Device crash and dump files",
  backup_size_anomaly: "Backup size deviation",
};

export interface NotificationRouteSummaryRow {
  readonly type: string;
  readonly title: string;
  readonly enabledWord: "On" | "Off";
  readonly deliveryWord: string;
  readonly hasError: boolean;
}

export interface NotificationsAndLoggingSummary {
  readonly badge: BadgeState;
  readonly word: string;
  readonly bothRestricted: boolean;
  readonly notificationsState: "ok" | "restricted" | "unknown";
  readonly systemLogsState: "ok" | "restricted" | "unknown";
  readonly lines: readonly SystemLine[];
  readonly routeRowsShown: readonly NotificationRouteSummaryRow[];
  readonly totalRoutes: number;
  readonly evidenceAt: string | null;
}

function isValidRoute(r: unknown): r is NotificationRouteView {
  if (!r || typeof r !== "object" || Array.isArray(r)) return false;
  const route = r as Partial<NotificationRouteView>;
  return typeof route.type === "string" && route.type.trim().length > 0 && typeof route.enabled === "boolean";
}

function isValidNotifications(v: unknown): v is NotificationSettingsView {
  if (!v || typeof v !== "object" || Array.isArray(v)) return false;
  const s = v as Partial<NotificationSettingsView>;
  return Array.isArray(s.routes) && s.routes.every(isValidRoute);
}

function isValidSystemLogs(v: unknown): v is SystemLogSettingsView {
  if (!v || typeof v !== "object" || Array.isArray(v)) return false;
  const s = v as Partial<SystemLogSettingsView>;
  return (
    typeof s.raw_retention_days === "number" &&
    Number.isFinite(s.raw_retention_days) &&
    typeof s.device_cap_bytes === "number" &&
    Number.isFinite(s.device_cap_bytes) &&
    typeof s.hcp_retention_days === "number" &&
    Number.isFinite(s.hcp_retention_days) &&
    typeof s.spike_data_retention_days === "number" &&
    Number.isFinite(s.spike_data_retention_days) &&
    typeof s.spike_data_cap_bytes === "number" &&
    Number.isFinite(s.spike_data_cap_bytes) &&
    typeof s.ledger_retention_days === "number" &&
    Number.isFinite(s.ledger_retention_days)
  );
}

function hasDeliveryError(route: NotificationRouteView): boolean {
  return typeof route.last_error === "string" && route.last_error.trim().length > 0;
}

function routeTitleOf(type: string): string {
  return (NOTIFICATION_ROUTE_TITLES as Readonly<Record<string, string>>)[type] ?? type;
}

function isParseableIso(iso: string | null | undefined): iso is string {
  return typeof iso === "string" && iso.trim().length > 0 && !Number.isNaN(Date.parse(iso));
}

function oldestReadableReadAt(
  reads: ReadonlyArray<{ readonly ok: boolean; readonly readAt: string | null }>,
): string | null {
  const instants = reads
    .filter((r) => r.ok && isParseableIso(r.readAt))
    .map((r) => r.readAt as string);
  if (instants.length === 0) return null;
  return instants.reduce((a, b) => (Date.parse(b) < Date.parse(a) ? b : a));
}

/**
 * Pure summary for the Estate System "Notifications and logging" card.
 *
 * Independent read handling mirrors `EstateSystemPodsStorage`: one refused (`restricted`) read never hides the other
 * readable source, and the card is `"Restricted"` (`badge: "na", word: "Restricted"`) only when both reads are
 * `restricted`. Any `failed`, `skipped`, or malformed payload forces `badge: "unk"` (never `"ok"`).
 */
export function summariseNotificationsAndLogging(
  notifications: TimedRead<NotificationSettingsView>,
  systemLogs: TimedRead<SystemLogSettingsView>,
): NotificationsAndLoggingSummary {
  const notificationsOk =
    notifications.read.state === "ok" && isValidNotifications(notifications.read.value)
      ? notifications.read.value
      : null;
  const systemLogsOk =
    systemLogs.read.state === "ok" && isValidSystemLogs(systemLogs.read.value)
      ? systemLogs.read.value
      : null;

  const notificationsState: "ok" | "restricted" | "unknown" =
    notificationsOk !== null
      ? "ok"
      : notifications.read.state === "restricted"
        ? "restricted"
        : "unknown";
  const systemLogsState: "ok" | "restricted" | "unknown" =
    systemLogsOk !== null
      ? "ok"
      : systemLogs.read.state === "restricted"
        ? "restricted"
        : "unknown";

  const bothRestricted = notificationsState === "restricted" && systemLogsState === "restricted";
  if (bothRestricted) {
    return {
      badge: "na",
      word: "Restricted",
      bothRestricted: true,
      notificationsState,
      systemLogsState,
      lines: [{ label: "Access", value: "Your role cannot read notifications and log collection." }],
      routeRowsShown: [],
      totalRoutes: 0,
      evidenceAt: null,
    };
  }

  const lines: SystemLine[] = [];
  let routeRowsShown: NotificationRouteSummaryRow[] = [];
  let totalRoutes = 0;
  let watchCondition = false;

  if (notificationsState === "restricted") {
    lines.push({ label: "Notifications", value: "Your role cannot read notifications." });
  } else if (notificationsState === "unknown" || !notificationsOk) {
    lines.push({ label: "Notifications", value: "UNKNOWN: notifications could not be read" });
  } else {
    const s = notificationsOk;
    const isRaw = (s.notification_identity ?? "masked") === "raw";
    const routes = s.routes;
    const enabledRoutes = routes.filter((r) => r.enabled === true);
    const erroredRoutes = routes.filter(hasDeliveryError);
    if (isRaw || erroredRoutes.length > 0) {
      watchCondition = true;
    }

    const protocolWord = (s.syslog_protocol ?? "udp").toUpperCase();
    const auditWord = s.forward_audit_to_syslog ? "on" : "off";
    const jobFailureWord = s.notify_job_failure ? "on" : "off";

    lines.push(
      {
        label: "Device names in messages",
        value: !isRaw ? "Masked pseudonyms" : "Raw device names (audited)",
      },
      {
        label: "Remote syslog",
        value: s.syslog_enabled
          ? `On (${protocolWord}, audit forwarding ${auditWord}, failed-job forwarding ${jobFailureWord})`
          : "Off",
      },
      {
        label: "Mail relay",
        value: s.smtp_enabled ? `On (${s.smtp_starttls ? "STARTTLS" : "plain SMTP"})` : "Off",
      },
      {
        label: "Notification routes enabled",
        value: `${enabledRoutes.length} of ${plural(routes.length, "route", "routes")}`,
      },
      {
        label: "Routes with a last delivery error",
        value: plural(erroredRoutes.length, "route", "routes"),
      },
    );

    totalRoutes = routes.length;
    routeRowsShown = routes.slice(0, NOTIFICATION_ROUTES_CAP).map((route): NotificationRouteSummaryRow => {
      const hasError = hasDeliveryError(route);
      const deliveryWord = hasError
        ? "last delivery failed"
        : isParseableIso(route.last_sent_at)
          ? `last sent ${formatUtc(route.last_sent_at, false)}`
          : "never sent";
      return {
        type: route.type,
        title: routeTitleOf(route.type),
        enabledWord: route.enabled ? "On" : "Off",
        deliveryWord,
        hasError,
      };
    });
  }

  if (systemLogsState === "restricted") {
    lines.push({ label: "Log collection", value: "Your role cannot read log collection." });
  } else if (systemLogsState === "unknown" || !systemLogsOk) {
    lines.push({ label: "Log collection", value: "UNKNOWN: log collection could not be read" });
  } else {
    const v = systemLogsOk;
    lines.push(
      {
        label: "Raw log retention",
        value: plural(v.raw_retention_days, "day", "days"),
      },
      {
        label: "Per-device storage cap",
        value: `${Math.round(v.device_cap_bytes / MIB)} MiB`,
      },
      {
        label: "HCP & ledger retention",
        value: `HCP ${plural(v.hcp_retention_days, "day", "days")} · ledger ${plural(v.ledger_retention_days, "day", "days")}`,
      },
      {
        label: "Management servers & Spike Detective",
        value: `management servers ${v.include_management_servers ? "on" : "off"} · Spike Detective ${v.spike_data_enabled ? "on" : "off"} (${plural(v.spike_data_retention_days, "day", "days")}, ${Math.round(v.spike_data_cap_bytes / MIB)} MiB cap)`,
      },
    );
  }

  const anyUnknown = notificationsState === "unknown" || systemLogsState === "unknown";
  const anyRestricted = notificationsState === "restricted" || systemLogsState === "restricted";
  const badge: BadgeState = anyUnknown
    ? "unk"
    : watchCondition
      ? "watch"
      : anyRestricted
        ? "unk"
        : "ok";
  const word = BADGE_WORD_DEFAULT[badge];

  const evidenceAt =
    systemLogsOk !== null && isParseableIso(systemLogsOk.updated_at)
      ? systemLogsOk.updated_at
      : oldestReadableReadAt([
          { ok: notificationsOk !== null, readAt: notifications.readAt },
          { ok: systemLogsOk !== null, readAt: systemLogs.readAt },
        ]);

  return {
    badge,
    word,
    bothRestricted: false,
    notificationsState,
    systemLogsState,
    lines,
    routeRowsShown,
    totalRoutes,
    evidenceAt,
  };
}
