import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { SecurityScanSummaryView, SystemHealth, SystemHealthDevice } from "../src/auth/adminApi";
import {
  PLATFORM_HEALTH_HREF,
  PLATFORM_MAX_NAMES,
  PLATFORM_STATUS_HREF,
  buildPlatformFindings,
  rankPlatformFindings,
  type PlatformFindingItem,
} from "../src/estate/estatePlatformFindings";
import { EstatePlatformCard } from "../src/estate/EstatePlatformCard";

function device(n: number, state: SystemHealthDevice["state"]): SystemHealthDevice {
  return {
    device_id: `dev-tango-${String(n).padStart(2, "0")}`,
    hostname: `FW-TANGO-${String(n).padStart(2, "0")}`,
    vendor_hint: "check_point",
    role: "gateway",
    cluster_member_ref: null,
    monitored: true,
    state,
    state_reason: null,
    cpu: { level: "EVIDENCED", reason: null, threshold_percent: 85, newest_p95_percent: null, days_over_threshold: 0, longest_run_over_threshold: 0, days: [], day_basis: "sar" },
    memory: { level: "EVIDENCED", reason: null, newest_used_p95_percent: null },
    restarts: { level: "EVIDENCED", reason: null, count: 0, max_in_one_hour: 0, termination_lines: 0, busiest_hour: null, rule: "" },
    crash: { level: "EVIDENCED", reason: null, new_alerts: 0, newest_alert_at: null },
    collection: { state: "OK", reason: null, last_job_at: null, last_job_id: null, outcomes: {} },
    logs_day: null,
  } as unknown as SystemHealthDevice;
}

function health(devices: SystemHealthDevice[], computationState: SystemHealth["computation"]["state"] = "READY"): SystemHealth {
  return {
    computation: { state: computationState, computed_at: "2026-11-21T10:00:00Z", duration_ms: 10, snapshot_ttl_seconds: 900, store_available: true },
    window_days: 7,
    cpu_p95_threshold_percent: 85,
    time_basis: "",
    rules: {} as SystemHealth["rules"],
    kpis: { monitored: null, crash_alerts: null, cpu_over_threshold: null, collection_not_ok: null },
    devices,
  } as unknown as SystemHealth;
}

function finding(n: number, severity: "HIGH" | "MEDIUM", status: "new" | "existing" | "accepted" = "new") {
  return {
    tool: "semgrep",
    rule: `rule-${severity.toLowerCase()}-${n}`,
    severity,
    location: `src/tango/module_${n}.py`,
    status,
    fix_available: false,
  } as const;
}

function scan(overrides: Partial<SecurityScanSummaryView> = {}): SecurityScanSummaryView {
  return {
    available: true,
    mail: { route_enabled: true, smtp_configured: true, recipients_configured: true, delivering: true, last_sent_at: null, last_error: null },
    stale_after_hours: 24,
    generated_at: "2026-11-21T02:30:00Z",
    age_hours: 3,
    stale: false,
    passed: true,
    scan_errors: 0,
    blocking: 0,
    counts: {
      semgrep: { HIGH: { new: 1, existing: 1, accepted: 4, fixed: 0 }, MEDIUM: { new: 0, existing: 2, accepted: 0, fixed: 0 } },
    },
    findings_listed_in_summary: true,
    findings_total: 3,
    findings: [finding(1, "HIGH"), finding(2, "HIGH"), finding(3, "MEDIUM")],
    ...overrides,
  } as SecurityScanSummaryView;
}

const ok = <T,>(value: T) => ({ state: "ok" as const, value });

describe("Estate Brief platform findings: ranking", () => {
  it("ranks act before review before UNKNOWN, and by count inside a group", () => {
    const items = buildPlatformFindings(
      ok(health([device(1, "CRITICAL"), device(2, "CRITICAL"), device(3, "ATTENTION"), device(4, "UNKNOWN")])),
      ok(scan()),
    );
    expect(items.map((i) => i.key)).toEqual([
      "platform-health-critical",
      "platform-scan-high",
      "platform-health-attention",
      "platform-scan-medium",
      "platform-health-unknown",
    ]);
    expect(items.map((i) => i.severity)).toEqual(["critical", "critical", "serious", "serious", "unknown"]);
  });

  it("orders by rank inside one severity group", () => {
    const sorted = rankPlatformFindings([
      { key: "platform-health-unknown", severity: "unknown", rank: 30, count: 1 },
      { key: "platform-scan-unreadable", severity: "unknown", rank: 32, count: 0 },
      { key: "platform-health-unreadable", severity: "unknown", rank: 31, count: 0 },
    ] as unknown as PlatformFindingItem[]);
    expect(sorted.map((i) => i.key)).toEqual(["platform-health-unknown", "platform-health-unreadable", "platform-scan-unreadable"]);
  });

  it("counts open findings only: accepted HIGH/MEDIUM are not listed or counted", () => {
    const view = scan({
      counts: { semgrep: { HIGH: { new: 0, existing: 0, accepted: 5, fixed: 0 } } },
      findings: [finding(9, "HIGH", "accepted")],
      findings_total: 1,
    });
    const items = buildPlatformFindings(ok(health([])), ok(view));
    expect(items).toEqual([]);
  });

  it("caps names at the limit and prints the rest as +N more, with the count of what it counts", () => {
    const many = Array.from({ length: PLATFORM_MAX_NAMES + 3 }, (_, i) => device(i + 1, "CRITICAL"));
    const [critical] = buildPlatformFindings(ok(health(many)), { state: "loading" });
    expect(critical.key).toBe("platform-health-critical");
    expect(critical.count).toBe(PLATFORM_MAX_NAMES + 3);
    expect(critical.names).toHaveLength(PLATFORM_MAX_NAMES);
    expect(critical.more).toBe(3);
    expect(critical.what).toBe(`${PLATFORM_MAX_NAMES + 3} devices CRITICAL on device health (7-day window)`);
    expect(critical.href).toBe(PLATFORM_HEALTH_HREF);
    expect(critical.at).toBe("2026-11-21T10:00:00Z");
  });

  it("links each item to its standard-view detail", () => {
    const items = buildPlatformFindings(ok(health([device(1, "ATTENTION")])), ok(scan({ stale: true, age_hours: 30 })));
    const byKey = Object.fromEntries(items.map((i) => [i.key, i]));
    expect(byKey["platform-health-attention"].href).toBe(PLATFORM_HEALTH_HREF);
    expect(byKey["platform-scan-high"].href).toBe(PLATFORM_STATUS_HREF);
    expect(byKey["platform-scan-stale"].what).toBe("The last daily scan finished 30 h ago");
  });
});

describe("Estate Brief platform findings: unreadable and clear cases", () => {
  it("says an unreadable health source is UNKNOWN, never fine", () => {
    const items = buildPlatformFindings({ state: "failed" }, ok(scan({ counts: {}, findings: [], findings_total: 0 })));
    expect(items).toHaveLength(1);
    expect(items[0].key).toBe("platform-health-unreadable");
    expect(items[0].severity).toBe("unknown");
    expect(items[0].what).toBe("Device health could not be read");
    expect(items[0].detail).toBe("Not counted as fine.");
    expect(items[0].at).toBeNull();
  });

  it("says a restricted scan source is UNKNOWN for this role", () => {
    const items = buildPlatformFindings(ok(health([])), { state: "restricted" });
    expect(items).toEqual([
      expect.objectContaining({ key: "platform-scan-unreadable", severity: "unknown", what: "The security scan summary is not available to this role" }),
    ]);
  });

  it("treats a computation that has not finished as UNKNOWN", () => {
    const items = buildPlatformFindings(ok(health([], "COMPUTING")), ok(scan({ counts: {}, findings: [], findings_total: 0 })));
    expect(items.map((i) => i.key)).toEqual(["platform-health-unreadable"]);
    expect(items[0].what).toBe("Device health has not been computed yet");
  });

  it("returns no items when both sources are read and nothing is open", () => {
    expect(buildPlatformFindings(ok(health([device(1, "NORMAL")])), ok(scan({ counts: {}, findings: [], findings_total: 0 })))).toEqual([]);
  });

  it("renders the unreadable state as UNKNOWN text in the card, and the clear state as checked", () => {
    const { unmount } = render(
      <EstatePlatformCard
        now={new Date("2026-11-22T12:00:00Z")}
        platform={{ health: { state: "failed" }, scan: { state: "loading" }, items: buildPlatformFindings({ state: "failed" }, { state: "loading" }) }}
      />,
    );
    expect(screen.getByText("Device health could not be read")).toBeTruthy();
    expect(screen.getByText("Not counted as fine.")).toBeTruthy();
    unmount();

    render(
      <EstatePlatformCard
        now={new Date("2026-11-22T12:00:00Z")}
        platform={{ health: ok(health([])), scan: ok(scan({ counts: {}, findings: [], findings_total: 0 })), items: [] }}
      />,
    );
    expect(screen.getByTestId("estate-platform-clear").textContent).toContain("No device health problems");
  });
});
