import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type {
  NotificationRouteView,
  NotificationSettingsView,
  SystemLogSettingsView,
} from "../src/auth/adminApi";
import { EvidenceStamp } from "../src/estate/EstatePrimitives";
import { EstateSystemNotifications } from "../src/estate/EstateSystemNotifications";
import type { TimedRead } from "../src/estate/estateSystemModel";
import {
  NOTIFICATION_ROUTES_CAP,
  NOTIFICATIONS_STANDARD_HREF,
  summariseNotificationsAndLogging,
  SYSLOG_SETTINGS_STANDARD_HREF,
} from "../src/estate/estateSystemNotificationsModel";
import { resolveAdminRoute } from "../src/screens/AdministrationScreen";
import { formatUtc } from "../src/shell/time";

// Synthetic fixtures only (FW-TANGO-04 style names, 192.0.2.x addresses). Nothing here names a real host or recipient.
const AT_READ = "2026-11-22T08:00:00.000Z";
const AT_UPDATED = "2026-11-22T07:30:00.000Z";
const NOW = new Date("2026-11-22T08:05:00.000Z");
const MIB = 1024 * 1024;

const SYNTHETIC_SYSLOG_HOST = "192.0.2.50";
const SYNTHETIC_SMTP_HOST = "192.0.2.55";
const SYNTHETIC_FROM_ADDR = ["noreply", "192.0.2.55"].join("@");
const SYNTHETIC_TO_ADDR = ["ops-tango-04", "192.0.2.55"].join("@");
const SYNTHETIC_ROUTE_ADDR = ["sec-tango-04", "192.0.2.55"].join("@");

const ALL_ROUTE_TYPES: readonly NotificationRouteView["type"][] = [
  "admin_event",
  "login_security",
  "backup_failure",
  "job_failure",
  "config_change",
  "compliance_regression",
  "device_health",
  "security_scan",
  "device_crash_file",
  "backup_size_anomaly",
];

function buildRoutes(overrides: Partial<Record<NotificationRouteView["type"], Partial<NotificationRouteView>>> = {}): NotificationRouteView[] {
  return ALL_ROUTE_TYPES.map((type, idx) => ({
    type,
    enabled: idx < 4,
    recipients: SYNTHETIC_ROUTE_ADDR,
    last_sent_at: idx === 0 ? AT_UPDATED : null,
    last_error: null,
    ...(overrides[type] ?? {}),
  }));
}

function buildNotifications(over: Partial<NotificationSettingsView> = {}): NotificationSettingsView {
  return {
    syslog_enabled: true,
    syslog_host: SYNTHETIC_SYSLOG_HOST,
    syslog_port: 514,
    syslog_protocol: "tcp",
    syslog_facility: 16,
    smtp_enabled: true,
    smtp_host: SYNTHETIC_SMTP_HOST,
    smtp_port: 587,
    smtp_starttls: true,
    smtp_from: SYNTHETIC_FROM_ADDR,
    smtp_to: SYNTHETIC_TO_ADDR,
    forward_audit_to_syslog: true,
    notify_job_failure: false,
    notification_identity: "masked",
    updated_at: AT_UPDATED,
    routes: buildRoutes(),
    ...over,
  };
}

function buildSystemLogs(over: Partial<SystemLogSettingsView> = {}): SystemLogSettingsView {
  return {
    raw_retention_days: 30,
    device_cap_bytes: 512 * MIB,
    hcp_retention_days: 14,
    spike_data_retention_days: 7,
    spike_data_cap_bytes: 256 * MIB,
    ledger_retention_days: 90,
    include_management_servers: false,
    spike_data_enabled: true,
    updated_at: AT_UPDATED,
    updated_by: "fp-synthetic-tango",
    can_write: false,
    bounds: {
      raw_retention_days: { type: "integer", min: 1, max: 365 },
      device_cap_bytes: { type: "integer", min: MIB, max: 4096 * MIB },
      hcp_retention_days: { type: "integer", min: 1, max: 90 },
      spike_data_retention_days: { type: "integer", min: 1, max: 90 },
      spike_data_cap_bytes: { type: "integer", min: MIB, max: 2048 * MIB },
      ledger_retention_days: { type: "integer", min: 1, max: 365 },
      include_management_servers: { type: "boolean" },
      spike_data_enabled: { type: "boolean" },
    },
    ...over,
  };
}

const ok = <T,>(value: T, readAt: string | null = AT_READ): TimedRead<T> => ({
  read: { state: "ok", value },
  readAt,
});
const failed = <T,>(): TimedRead<T> => ({ read: { state: "failed" }, readAt: AT_READ });
const restricted = <T,>(): TimedRead<T> => ({ read: { state: "restricted" }, readAt: AT_READ });
const skipped = <T,>(): TimedRead<T> => ({ read: { state: "skipped" }, readAt: null });

describe("Estate System: Notifications and logging card", () => {
  it("renders notification posture, system-log retention and caps in days and MiB, EvidenceStamp, and both standard=1 links without leaking hostnames or e-mails", () => {
    const { container } = render(
      <EstateSystemNotifications
        notifications={ok(buildNotifications())}
        systemLogs={ok(buildSystemLogs())}
        now={NOW}
      />,
    );

    const card = screen.getByTestId("estate-system-notifications");
    expect(within(card).getByRole("heading", { name: "Notifications and logging" })).toBeTruthy();
    expect(card.querySelector('[data-state-badge="ok"]')?.textContent).toBe("Fine");

    expect(screen.getByText("Device names in messages").nextElementSibling?.textContent).toBe("Masked pseudonyms");
    expect(screen.getByText("Remote syslog").nextElementSibling?.textContent).toBe(
      "On (TCP, audit forwarding on, failed-job forwarding off)",
    );
    expect(screen.getByText("Mail relay").nextElementSibling?.textContent).toBe("On (STARTTLS)");
    expect(screen.getByText("Notification routes enabled").nextElementSibling?.textContent).toBe("4 of 10 routes");
    expect(screen.getByText("Routes with a last delivery error").nextElementSibling?.textContent).toBe("0 routes");

    expect(screen.getByText("Raw log retention").nextElementSibling?.textContent).toBe("30 days");
    expect(screen.getByText("Per-device storage cap").nextElementSibling?.textContent).toBe("512 MiB");
    expect(screen.getByText("HCP & ledger retention").nextElementSibling?.textContent).toBe("HCP 14 days · ledger 90 days");
    expect(screen.getByText("Management servers & Spike Detective").nextElementSibling?.textContent).toBe(
      "management servers off · Spike Detective on (7 days, 256 MiB cap)",
    );

    const expectedStamp = render(
      <EvidenceStamp at={AT_UPDATED} source="notifications and log collection" now={NOW} />,
    ).container.textContent;
    expect(card.textContent).toContain(expectedStamp);

    const notifLink = within(card).getByRole("link", { name: "Open notifications (standard) →" });
    expect(notifLink.getAttribute("href")).toBe(NOTIFICATIONS_STANDARD_HREF);
    const notifParams = new URLSearchParams(NOTIFICATIONS_STANDARD_HREF);
    const notifRoute = resolveAdminRoute(notifParams.get("tab"), notifParams.get("section"));
    expect(notifRoute.page.slug).toBe("platform");
    expect(notifRoute.section).toBe("notifications");

    const syslogLink = within(card).getByRole("link", { name: "Open log collection (standard) →" });
    expect(syslogLink.getAttribute("href")).toBe(SYSLOG_SETTINGS_STANDARD_HREF);
    const syslogParams = new URLSearchParams(SYSLOG_SETTINGS_STANDARD_HREF);
    const syslogRoute = resolveAdminRoute(syslogParams.get("tab"), syslogParams.get("section"));
    expect(syslogRoute.page.slug).toBe("platform");
    expect(syslogRoute.section).toBe("system-logs");

    const fullText = container.textContent ?? "";
    expect(fullText).not.toContain(SYNTHETIC_SYSLOG_HOST);
    expect(fullText).not.toContain(SYNTHETIC_SMTP_HOST);
    expect(fullText).not.toContain(SYNTHETIC_FROM_ADDR);
    expect(fullText).not.toContain(SYNTHETIC_TO_ADDR);
    expect(fullText).not.toContain(SYNTHETIC_ROUTE_ADDR);
  });

  it("caps the 10 notification routes at NOTIFICATION_ROUTES_CAP (5) and renders +5 more notification routes", () => {
    render(
      <EstateSystemNotifications
        notifications={ok(buildNotifications())}
        systemLogs={ok(buildSystemLogs())}
        now={NOW}
      />,
    );

    expect(NOTIFICATION_ROUTES_CAP).toBe(5);
    const rows = screen.getAllByTestId("estate-system-notification-route-row");
    expect(rows).toHaveLength(5);
    expect(rows[0].textContent).toContain("Administration changes");
    expect(rows[0].textContent).toContain(`On · last sent ${formatUtc(AT_UPDATED, false)}`);
    expect(rows[1].textContent).toContain("Sign-in and access");
    expect(rows[1].textContent).toContain("On · never sent");
    expect(rows[4].textContent).toContain("Configuration changes");
    expect(rows[4].textContent).toContain("Off · never sent");

    const more = screen.getByTestId("estate-system-notifications-more");
    expect(more.textContent).toBe("+5 more notification routes");
  });

  it("raises watch (Review) badge when any notification route reports a last_error or when notification_identity is raw", () => {
    const withError = buildNotifications({
      routes: buildRoutes({
        backup_failure: { last_error: `relay ${SYNTHETIC_SMTP_HOST} rejected ${SYNTHETIC_ROUTE_ADDR}` },
      }),
    });
    const { container, unmount } = render(
      <EstateSystemNotifications
        notifications={ok(withError)}
        systemLogs={ok(buildSystemLogs())}
        now={NOW}
      />,
    );

    expect(container.querySelector('[data-state-badge="watch"]')?.textContent).toBe("Review");
    expect(screen.getByText("Routes with a last delivery error").nextElementSibling?.textContent).toBe("1 route");
    expect(container.textContent).toContain("On · last delivery failed");
    expect(container.textContent).not.toContain(SYNTHETIC_SMTP_HOST);
    expect(container.textContent).not.toContain(SYNTHETIC_ROUTE_ADDR);
    unmount();

    const withRawIdentity = buildNotifications({ notification_identity: "raw" });
    const rawRender = render(
      <EstateSystemNotifications
        notifications={ok(withRawIdentity)}
        systemLogs={ok(buildSystemLogs())}
        now={NOW}
      />,
    );
    expect(rawRender.container.querySelector('[data-state-badge="watch"]')?.textContent).toBe("Review");
    expect(screen.getByText("Device names in messages").nextElementSibling?.textContent).toBe(
      "Raw device names (audited)",
    );
  });

  it("renders UNKNOWN badge and a0 EvidenceStamp when a read fails or returns malformed data, never OK", () => {
    const failedRender = render(
      <EstateSystemNotifications
        notifications={failed<NotificationSettingsView>()}
        systemLogs={failed<SystemLogSettingsView>()}
        now={NOW}
      />,
    );
    expect(failedRender.container.querySelector('[data-state-badge="unk"]')?.textContent).toBe("UNKNOWN");
    expect(failedRender.container.querySelector('[data-state-badge="ok"]')).toBeNull();
    expect(failedRender.container.querySelector('[data-evidence-stamp="a0"]')).not.toBeNull();
    failedRender.unmount();

    const malformedNotifications = { ...buildNotifications(), routes: null as unknown as NotificationRouteView[] };
    const malformedSystemLogs = { ...buildSystemLogs(), raw_retention_days: Number.NaN };
    const malformedRender = render(
      <EstateSystemNotifications
        notifications={ok(malformedNotifications)}
        systemLogs={ok(malformedSystemLogs)}
        now={NOW}
      />,
    );
    expect(malformedRender.container.querySelector('[data-state-badge="unk"]')?.textContent).toBe("UNKNOWN");
    expect(malformedRender.container.querySelector('[data-state-badge="ok"]')).toBeNull();
    expect(malformedRender.container.querySelector('[data-evidence-stamp="a0"]')).not.toBeNull();

    const oneSkipped = summariseNotificationsAndLogging(skipped(), ok(buildSystemLogs()));
    expect(oneSkipped.badge).toBe("unk");
  });

  it("keeps one readable section visible when the other returns HTTP 403 (Restricted), and shows Restricted only when both are refused", () => {
    const notifRestricted = render(
      <EstateSystemNotifications
        notifications={restricted<NotificationSettingsView>()}
        systemLogs={ok(buildSystemLogs({ updated_at: null }))}
        now={NOW}
      />,
    );
    expect(screen.getByText("Notifications").nextElementSibling?.textContent).toBe(
      "Your role cannot read notifications.",
    );
    expect(screen.getByText("Raw log retention").nextElementSibling?.textContent).toBe("30 days");
    expect(screen.getByText("Per-device storage cap").nextElementSibling?.textContent).toBe("512 MiB");
    expect(notifRestricted.container.querySelector('[data-state-badge="unk"]')?.textContent).toBe("UNKNOWN");
    expect(notifRestricted.container.querySelector('[data-evidence-stamp="a1"]')).not.toBeNull();
    notifRestricted.unmount();

    const logsRestricted = render(
      <EstateSystemNotifications
        notifications={ok(buildNotifications())}
        systemLogs={restricted<SystemLogSettingsView>()}
        now={NOW}
      />,
    );
    expect(screen.getByText("Device names in messages").nextElementSibling?.textContent).toBe("Masked pseudonyms");
    expect(screen.getByText("Log collection").nextElementSibling?.textContent).toBe(
      "Your role cannot read log collection.",
    );
    expect(logsRestricted.container.querySelector('[data-state-badge="unk"]')?.textContent).toBe("UNKNOWN");
    logsRestricted.unmount();

    const bothRefused = render(
      <EstateSystemNotifications
        notifications={restricted<NotificationSettingsView>()}
        systemLogs={restricted<SystemLogSettingsView>()}
        now={NOW}
      />,
    );
    expect(bothRefused.container.querySelector('[data-state-badge="na"]')?.textContent).toBe("Restricted");
    expect(screen.getByText("Access").nextElementSibling?.textContent).toBe(
      "Your role cannot read notifications and log collection.",
    );
    expect(bothRefused.container.querySelector('[data-evidence-stamp="a0"]')).not.toBeNull();
  });
});
