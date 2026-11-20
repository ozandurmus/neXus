import { render, screen, waitFor } from "@testing-library/react";
import { ThemeProvider } from "@mui/material/styles";
import { afterEach, describe, expect, it, vi } from "vitest";
import { m3Theme } from "../src/theme/m3Theme";
import { PlatformHealthIndicator } from "../src/shell/PlatformHealthIndicator";
import { SystemStatusPanel } from "../src/screens/SystemStatusPanel";
import * as api from "../src/auth/adminApi";
import type { PodsView, SecurityScanSummaryView, StorageView } from "../src/auth/adminApi";
import {
  failingAlerts, loadErrorText, platformHealthIndicatorViolation, platformStatusViolations, type PlatformStatusSnapshot,
} from "../e2e/safety";

vi.mock("../src/auth/adminApi", () => ({
  getSystemPods: vi.fn(),
  getSystemStorage: vi.fn(),
  getSecurityScanSummary: vi.fn(),
}));

afterEach(() => vi.resetAllMocks());

const SESSION = { permissions: ["Devices"], canReadAdministration: false };

const BASE_POD = {
  component: "svc", phase: "Running", state: "Running", ready: "1/1", restarts: 0,
  started_at: "2026-11-18T06:00:00Z", image_digest: null,
  cpu_millicores: null, cpu_limit_millicores: null, memory_bytes: null, memory_limit_bytes: null,
  owner_kind: "ReplicaSet", owner_name: "svc-rs", created_at: "2026-11-18T06:00:00Z",
};

const HEALTHY_PODS: PodsView = {
  available: true,
  read_at: "2026-11-19T08:00:00Z",
  pods: [{ ...BASE_POD, name: "svc-a-0" }, { ...BASE_POD, name: "svc-b-0" }],
  quarantined_by_role: [],
  disk: {
    level: "ok",
    thresholds: { warning_percent: 80, critical_percent: 90 },
    volumes: [{ name: "root", total_bytes: 1000, usable_bytes: 400, used_percent: 60, level: "ok" }],
  },
};

const BASE_STORAGE: StorageView = {
  read_at: "2026-11-19T08:00:00Z",
  backups: [],
  top_devices: [],
  database_bytes: 1024,
  configuration: { stored_bytes: 0, artefacts: 0, text_in_database_bytes: 0 },
  artefact_volume: { total_bytes: 1000, usable_bytes: 400, used_percent: 60, level: "ok", note: "synthetic volume" },
};

const CLEAN_SCAN: SecurityScanSummaryView = {
  available: true,
  mail: { route_enabled: true, smtp_configured: true, recipients_configured: true, delivering: true, last_sent_at: null, last_error: null },
  stale_after_hours: 26,
  generated_at: "2026-11-19T02:30:00Z",
  age_hours: 5,
  stale: false,
  passed: true,
  scan_errors: 0,
  blocking: 0,
  counts: {},
  findings_listed_in_summary: true,
  findings_total: 0,
  findings: [],
  failed: [],
};

/** Same DOM extraction shape as `assertPlatformStatus` in `e2e/fixtures.ts`. */
function snapshotDom(): PlatformStatusSnapshot {
  const panel = document.querySelector('section[aria-label="Platform status"]');
  const headings = panel
    ? [...panel.querySelectorAll("h1, h2, h3, h4, h5, h6")].map((el) => (el.textContent ?? "").trim())
    : [];
  const hasStorageSection = headings.includes("What the evidence takes");
  const servicePodsHeading = headings.find((h) => /^Service pods \(.+\)$/.test(h)) ?? null;
  const panelText = panel?.textContent ?? "";
  const observedUnknown = /Service view\s*·\s*observed\s+UNKNOWN\b/i.test(panelText);
  const indicatorEl = document.querySelector("[data-platform-health]");
  const indicator = indicatorEl ? {
    state: indicatorEl.getAttribute("data-platform-health"),
    ariaLabel: indicatorEl.getAttribute("aria-label"),
  } : null;
  const summaryEl = panel?.querySelector('[data-testid="platform-health-summary"]') ?? null;
  const summary = summaryEl ? {
    label: (summaryEl.children[0]?.textContent ?? "").trim(),
    reason: (summaryEl.children[1]?.textContent ?? "").trim(),
    text: (summaryEl.textContent ?? "").trim(),
  } : null;
  const alerts = panel ? [...panel.querySelectorAll('[role="alert"]')].map((el) => ({
    className: typeof el.className === "string" ? el.className : "",
    statusAlert: el.getAttribute("data-status-alert"),
    text: (el.textContent ?? "").trim(),
  })) : [];
  return { hasPanel: Boolean(panel), hasStorageSection, servicePodsHeading, observedUnknown, indicator, summary, alerts };
}

function renderPlatformStatusPage() {
  return render(
    <ThemeProvider theme={m3Theme}>
      <header><PlatformHealthIndicator session={SESSION} /></header>
      <main>
        <section aria-label="Platform status">
          <SystemStatusPanel />
        </section>
      </main>
    </ThemeProvider>,
  );
}

describe("Nightly e2e Platform status fixture rule", () => {
  it("passes when Platform status is healthy", async () => {
    vi.mocked(api.getSystemPods).mockResolvedValue(HEALTHY_PODS);
    vi.mocked(api.getSystemStorage).mockResolvedValue(BASE_STORAGE);
    vi.mocked(api.getSecurityScanSummary).mockResolvedValue(CLEAN_SCAN);

    renderPlatformStatusPage();
    await screen.findByTestId("platform-health-summary");
    await waitFor(() => expect(document.querySelector("[data-platform-health]")).not.toBeNull());

    const snap = snapshotDom();
    expect(loadErrorText.test(document.body.textContent ?? "")).toBe(false);
    expect(failingAlerts(snap.alerts)).toEqual([]);
    expect(platformHealthIndicatorViolation(snap.indicator)).toBeNull();
    expect(platformStatusViolations(snap)).toEqual([]);
  });

  it("passes on a legitimate disk usage warning and artefact store near-limit warning", async () => {
    vi.mocked(api.getSystemPods).mockResolvedValue({
      ...HEALTHY_PODS,
      disk: {
        level: "warning",
        thresholds: { warning_percent: 80, critical_percent: 90 },
        volumes: [{ name: "artefact_store", total_bytes: 1000, usable_bytes: 160, used_percent: 84, level: "warning" }],
      },
    });
    vi.mocked(api.getSystemStorage).mockResolvedValue({
      ...BASE_STORAGE,
      artefact_volume: {
        quota_bytes: 1000, used_bytes: 840, remaining_bytes: 160, usage_percent: 84,
        near_limit: true, quota_exceeded: false, warning: "artefact_store_near_quota",
      },
    });
    vi.mocked(api.getSecurityScanSummary).mockResolvedValue({
      ...CLEAN_SCAN,
      stale: true,
      age_hours: 30,
      mail: { ...CLEAN_SCAN.mail, delivering: false, smtp_configured: false },
    });

    renderPlatformStatusPage();
    await screen.findByText(/Disk warning: artefact_store 84% used/);
    await screen.findByTestId("security-scan-stale");
    await waitFor(() => expect(document.querySelector("[data-platform-health]")).not.toBeNull());

    const snap = snapshotDom();
    expect(snap.alerts.length).toBe(4);
    expect(loadErrorText.test(document.body.textContent ?? "")).toBe(false);
    expect(failingAlerts(snap.alerts)).toEqual([]);
    expect(platformStatusViolations(snap)).toEqual([]);
  });

  it("passes on a legitimate operations warning (quarantined permits, degraded light with stated reason)", async () => {
    vi.mocked(api.getSystemPods).mockResolvedValue({
      ...HEALTHY_PODS,
      quarantined_by_role: [{ owner: "policy", quarantined_permits: 2 }],
    });
    vi.mocked(api.getSystemStorage).mockResolvedValue(BASE_STORAGE);
    vi.mocked(api.getSecurityScanSummary).mockResolvedValue(CLEAN_SCAN);

    renderPlatformStatusPage();
    await screen.findByText(/policy: 2 quarantined/);
    await waitFor(() => expect(document.querySelector('[data-platform-health="degraded"]')).not.toBeNull());

    const snap = snapshotDom();
    expect(loadErrorText.test(document.body.textContent ?? "")).toBe(false);
    expect(failingAlerts(snap.alerts)).toEqual([]);
    expect(platformStatusViolations(snap)).toEqual([]);
  });

  it("passes when pod status is unavailable with a stated operational reason (degraded, not healthy)", async () => {
    vi.mocked(api.getSystemPods).mockResolvedValue({
      available: false,
      read_at: "2026-11-19T08:00:00Z",
      reason: "the service runs without a Kubernetes service account token; pod status cannot be read",
      pods: [],
    });
    vi.mocked(api.getSystemStorage).mockResolvedValue(BASE_STORAGE);
    vi.mocked(api.getSecurityScanSummary).mockResolvedValue(CLEAN_SCAN);

    renderPlatformStatusPage();
    await screen.findByText(/Service pods \(UNKNOWN\)/);
    await waitFor(() => expect(document.querySelector('[data-platform-health="degraded"]')).not.toBeNull());

    const snap = snapshotDom();
    expect(loadErrorText.test(document.body.textContent ?? "")).toBe(false);
    expect(failingAlerts(snap.alerts)).toEqual([]);
    expect(platformStatusViolations(snap)).toEqual([]);
  });

  it("fails on an operational error state (disk critical or artefact quota exceeded)", async () => {
    vi.mocked(api.getSystemPods).mockResolvedValue({
      ...HEALTHY_PODS,
      disk: {
        level: "critical",
        thresholds: { warning_percent: 80, critical_percent: 90 },
        volumes: [{ name: "artefact_store", total_bytes: 1000, usable_bytes: 60, used_percent: 94, level: "critical" }],
      },
    });
    vi.mocked(api.getSystemStorage).mockResolvedValue(BASE_STORAGE);
    vi.mocked(api.getSecurityScanSummary).mockResolvedValue(CLEAN_SCAN);

    renderPlatformStatusPage();
    await screen.findByText(/Disk critical: artefact_store 94% used/);
    await waitFor(() => expect(document.querySelector('[data-platform-health="degraded"]')).not.toBeNull());

    const snap = snapshotDom();
    expect(failingAlerts(snap.alerts)).toHaveLength(1);
    expect(platformStatusViolations(snap)).toEqual(expect.arrayContaining([expect.stringContaining("error, unmarked, or reasonless alert")]));
  });

  it("fails on a failed status API request (error alert, down indicator, missing sections)", async () => {
    vi.mocked(api.getSystemPods).mockRejectedValue({ status: 503 });
    vi.mocked(api.getSystemStorage).mockRejectedValue({ status: 503 });
    vi.mocked(api.getSecurityScanSummary).mockResolvedValue(CLEAN_SCAN);

    renderPlatformStatusPage();
    await screen.findByText(/System status could not be read/);
    await waitFor(() => expect(document.querySelector('[data-platform-health="down"]')).not.toBeNull());

    const snap = snapshotDom();
    expect(loadErrorText.test(document.body.textContent ?? "")).toBe(true);
    expect(failingAlerts(snap.alerts)).toHaveLength(1);
    expect(platformHealthIndicatorViolation(snap.indicator)).toContain("error state (down)");
    expect(platformStatusViolations(snap)).toEqual(expect.arrayContaining([
      "Platform status Storage section is missing",
      "Platform status Service pods section is missing",
      "Platform health indicator reports error state (down)",
    ]));
  });

  it("fails when the Platform status panel is missing", () => {
    render(<ThemeProvider theme={m3Theme}><main><p>Empty placeholder</p></main></ThemeProvider>);
    expect(platformStatusViolations(snapshotDom())).toEqual(expect.arrayContaining([
      "Platform status panel is missing",
      "Platform status Storage section is missing",
      "Platform status Service pods section is missing",
      "Platform health indicator is missing",
    ]));
  });

  it("fails when an UNKNOWN pod status is falsely presented as healthy", async () => {
    vi.mocked(api.getSystemPods).mockResolvedValue({
      available: false,
      read_at: "2026-11-19T08:00:00Z",
      reason: "the service runs without a Kubernetes service account token; pod status cannot be read",
      pods: [],
    });
    vi.mocked(api.getSystemStorage).mockResolvedValue(BASE_STORAGE);
    vi.mocked(api.getSecurityScanSummary).mockResolvedValue(CLEAN_SCAN);

    renderPlatformStatusPage();
    await screen.findByText(/Service pods \(UNKNOWN\)/);
    await waitFor(() => expect(document.querySelector("[data-platform-health]")).not.toBeNull());

    // Tamper the rendered indicator to simulate a regression where UNKNOWN is reported as healthy (ok).
    const el = document.querySelector("[data-platform-health]")!;
    el.setAttribute("data-platform-health", "ok");
    el.setAttribute("aria-label", "neXus status: OK. All service pods running and ready.");

    expect(platformStatusViolations(snapshotDom())).toEqual(expect.arrayContaining([
      "Platform status shows healthy (ok) while service pods are UNKNOWN",
    ]));
  });
});
