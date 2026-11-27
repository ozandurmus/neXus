import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { EventTriggerStatusPanel } from "../src/screens/eventtrigger/EventTriggerStatusPanel";
import {
  describeKillSwitchBanner,
  describeLiveness,
  describeNoChangeEvents,
  describePendingDefer,
  formatCoverageRatio,
  summarizeKillSwitchSettings,
  type EventTriggerKillSwitchSummary,
  type EventTriggerProjection,
  type PendingDeferState,
  type ProjectedUnitRow,
} from "../src/screens/eventtrigger/eventTriggerModel";

const NOW = new Date("2026-11-27T12:00:00Z");

const ACTIVE_KILL_SWITCH: EventTriggerKillSwitchSummary = {
  globalEnabled: true,
  haltedVendors: [],
  haltedSources: [],
  failClosedUnknown: false,
};

function makeRow(over: Partial<ProjectedUnitRow> = {}): ProjectedUnitRow {
  return {
    unitKind: "DOMAIN",
    displayName: "DOM-TANGO-04",
    vendor: "checkpoint",
    livenessState: "CONNECTED",
    livenessHealthy: true,
    lastDeviceEventAt: "2026-11-27T11:45:00Z",
    lastReceivedAt: "2026-11-27T11:46:00Z",
    lastPolicyCollectedAt: "2026-11-27T11:50:00Z",
    pendingDeferState: "NONE",
    pendingStatusText: null,
    triggerDueAt: null,
    noChangeEventsFlag: false,
    noChangeEventsCode: null,
    noChangeEventsMessage: null,
    noChangeEventsAlarm: false,
    ...over,
  };
}

function makeProjection(over: Partial<EventTriggerProjection> = {}): EventTriggerProjection {
  return {
    masked: true,
    projectedAt: NOW.toISOString(),
    units: [
      makeRow(),
      makeRow({
        unitKind: "CLUSTER",
        displayName: "CLS-ROMEO-01",
        vendor: "paloalto",
        livenessState: "HEARTBEAT_OK",
      }),
      makeRow({
        unitKind: "DEVICE",
        displayName: "FW-JULIET-06",
        vendor: "fortinet",
        livenessState: "DISCONNECTED",
        livenessHealthy: false,
      }),
    ],
    fleetCoverageByVendor: {
      checkpoint: { vendor: "checkpoint", healthyLiveUnits: 1, totalUnits: 1 },
      paloalto: { vendor: "paloalto", healthyLiveUnits: 1, totalUnits: 1 },
      fortinet: { vendor: "fortinet", healthyLiveUnits: 0, totalUnits: 1 },
    },
    ...over,
  };
}

describe("EventTriggerStatusPanel (read-only operator & masked liveness panel)", () => {
  it("pins all 7 closed liveness states and fails closed to UNKNOWN on null or unrecognized state", () => {
    const cases: ReadonlyArray<{
      readonly input: string | null | undefined;
      readonly expectedState: string;
      readonly expectedTone: string;
      readonly expectedWording: string;
      readonly displayName: string;
    }> = [
      {
        input: "CONNECTED",
        expectedState: "CONNECTED",
        expectedTone: "ok",
        expectedWording: "Live TLS session connected.",
        displayName: "DOM-TANGO-04",
      },
      {
        input: "HEARTBEAT_OK",
        expectedState: "HEARTBEAT_OK",
        expectedTone: "ok",
        expectedWording: "Relay heartbeat healthy.",
        displayName: "CLS-ROMEO-01",
      },
      {
        input: "DISCONNECTED",
        expectedState: "DISCONNECTED",
        expectedTone: "bad",
        expectedWording: "TLS event stream disconnected; covered by 6h scheduled collection.",
        displayName: "FW-JULIET-06",
      },
      {
        input: "HEARTBEAT_STALE",
        expectedState: "HEARTBEAT_STALE",
        expectedTone: "warn",
        expectedWording: "Event pipeline stalled (relay heartbeat or MDS Log Exporter unhealthy).",
        displayName: "DOM-ALPHA-01",
      },
      {
        input: "PASSIVE_NO_HEARTBEAT",
        expectedState: "PASSIVE_NO_HEARTBEAT",
        expectedTone: "neutral",
        expectedWording: "Passive event source configured; liveness between change events is not measurable.",
        displayName: "FW-BRAVO-02",
      },
      {
        input: "DEGRADED",
        expectedState: "DEGRADED",
        expectedTone: "warn",
        expectedWording: "Event stream degraded (dropped or unmatched frames in last 7d); see source counters.",
        displayName: "DOM-CHARLIE-03",
      },
      {
        input: "NOT_ENROLLED",
        expectedState: "NOT_ENROLLED",
        expectedTone: "neutral",
        expectedWording: "Not covered by change events; collected on schedule (6h policy / 00:00 daily config).",
        displayName: "CLS-DELTA-05",
      },
      {
        input: "UNEXPECTED_LIVENESS_TOKEN",
        expectedState: "UNKNOWN",
        expectedTone: "unknown",
        expectedWording: "Event pipeline liveness unknown",
        displayName: "GAP",
      },
      {
        input: null,
        expectedState: "UNKNOWN",
        expectedTone: "unknown",
        expectedWording: "Event pipeline liveness unknown",
        displayName: "FW-ECHO-07",
      },
    ];

    const projection = makeProjection({
      units: cases.map((c) =>
        makeRow({
          displayName: c.displayName,
          livenessState: c.input,
          livenessHealthy: c.expectedTone === "ok",
        })
      ),
    });

    render(<EventTriggerStatusPanel projection={projection} killSwitch={ACTIVE_KILL_SWITCH} now={NOW} />);

    const table = screen.getByRole("table", { name: "Event trigger unit status table" });
    cases.forEach((c, index) => {
      const meta = describeLiveness(c.input);
      expect(meta.state).toBe(c.expectedState);
      expect(meta.tone).toBe(c.expectedTone);
      expect(meta.wording).toBe(c.expectedWording);

      const rowEl = within(table).getByTestId(`event-trigger-row-${index}`);
      expect(rowEl).toHaveTextContent(c.displayName);
      const pill = rowEl.querySelector(`[data-pill="${c.expectedTone}"]`);
      expect(pill).not.toBeNull();
      expect(pill).toHaveTextContent(c.expectedState);
      expect(rowEl).toHaveTextContent(c.expectedWording);
    });

    // Unrecognized arbitrary liveness token must never be echoed
    expect(table.textContent ?? "").not.toContain("UNEXPECTED_LIVENESS_TOKEN");
    // Panel renders zero mutation controls
    expect(screen.queryByRole("button")).toBeNull();
    expect(screen.queryByRole("switch")).toBeNull();
  });

  it("pins per-vendor fleet coverage ratios (healthyLiveUnits / totalUnits)", () => {
    const projection = makeProjection({
      fleetCoverageByVendor: {
        checkpoint: { vendor: "checkpoint", healthyLiveUnits: 2, totalUnits: 5 },
        paloalto: { vendor: "paloalto", healthyLiveUnits: 1, totalUnits: 2 },
        cisco_asa: { vendor: "cisco_asa", healthyLiveUnits: 0, totalUnits: 2 },
      },
    });

    expect(formatCoverageRatio({ vendor: "checkpoint", healthyLiveUnits: 2, totalUnits: 5 })).toBe("2 / 5");
    expect(formatCoverageRatio({ vendor: "checkpoint", healthyLiveUnits: 6, totalUnits: 5 })).toBe("UNKNOWN");

    render(<EventTriggerStatusPanel projection={projection} killSwitch={ACTIVE_KILL_SWITCH} now={NOW} />);

    const strip = screen.getByTestId("event-trigger-fleet-coverage");
    const cpCard = within(strip).getByTestId("fleet-coverage-checkpoint");
    expect(cpCard).toHaveTextContent("Check Point");
    expect(cpCard).toHaveTextContent("2 / 5");

    const panCard = within(strip).getByTestId("fleet-coverage-paloalto");
    expect(panCard).toHaveTextContent("Palo Alto");
    expect(panCard).toHaveTextContent("1 / 2");

    const asaCard = within(strip).getByTestId("fleet-coverage-cisco_asa");
    expect(asaCard).toHaveTextContent("Cisco ASA");
    expect(asaCard).toHaveTextContent("0 / 2");
  });

  it("renders NO_CHANGE_EVENTS_N_DAYS strictly as informational, never says 'no changes', and hides it when flag is false or NOT_ENROLLED/null lastReceivedAt", () => {
    const healthyQuietRow = makeRow({
      displayName: "DOM-TANGO-04",
      livenessState: "CONNECTED",
      livenessHealthy: true,
      lastReceivedAt: "2026-11-13T12:00:00Z",
      noChangeEventsFlag: true,
      noChangeEventsCode: "NO_CHANGE_EVENTS_N_DAYS",
      noChangeEventsMessage: "No policy-install events received for 14 days",
      noChangeEventsAlarm: false,
    });
    const staleQuietRow = makeRow({
      displayName: "FW-JULIET-06",
      vendor: "fortinet",
      livenessState: "DISCONNECTED",
      livenessHealthy: false,
      lastReceivedAt: "2026-11-09T12:00:00Z",
      noChangeEventsFlag: true,
      noChangeEventsCode: "NO_CHANGE_EVENTS_N_DAYS",
      noChangeEventsMessage: "No policy-install events received for 18 days",
      noChangeEventsAlarm: false,
    });
    const missingLastReceivedRow = makeRow({
      displayName: "CLS-ROMEO-01",
      livenessState: "CONNECTED",
      livenessHealthy: true,
      lastReceivedAt: null,
      noChangeEventsFlag: false,
      noChangeEventsCode: null,
      noChangeEventsMessage: null,
      noChangeEventsAlarm: false,
    });
    const notEnrolledRow = makeRow({
      displayName: "GAP",
      livenessState: "NOT_ENROLLED",
      livenessHealthy: false,
      lastReceivedAt: null,
      noChangeEventsFlag: false,
      noChangeEventsCode: null,
      noChangeEventsMessage: null,
      noChangeEventsAlarm: false,
    });
    // Defensive check: even if a malformed payload sets flag=true on NOT_ENROLLED, helper suppresses it.
    const defensiveNotEnrolled = makeRow({
      displayName: "DOM-TANGO-04",
      livenessState: "NOT_ENROLLED",
      livenessHealthy: false,
      lastReceivedAt: "2026-10-01T12:00:00Z",
      noChangeEventsFlag: true,
      noChangeEventsMessage: "No policy-install events received for 57 days",
    });

    expect(describeNoChangeEvents(healthyQuietRow)).toEqual({
      code: "NO_CHANGE_EVENTS_N_DAYS",
      message: "No policy-install events received for 14 days",
      tone: "neutral",
      alarm: false,
    });
    expect(describeNoChangeEvents(missingLastReceivedRow)).toBeNull();
    expect(describeNoChangeEvents(notEnrolledRow)).toBeNull();
    expect(describeNoChangeEvents(defensiveNotEnrolled)).toBeNull();

    const projection = makeProjection({
      units: [healthyQuietRow, staleQuietRow, missingLastReceivedRow, notEnrolledRow],
    });
    render(<EventTriggerStatusPanel projection={projection} killSwitch={ACTIVE_KILL_SWITCH} now={NOW} />);

    const row0 = screen.getByTestId("event-trigger-row-0");
    const info0 = within(row0).getByTestId("no-change-events-info");
    expect(info0).toHaveAttribute("data-informational", "true");
    expect(info0).toHaveTextContent("NO_CHANGE_EVENTS_N_DAYS");
    expect(info0).toHaveTextContent("No policy-install events received for 14 days");
    // Must never carry an alarm/error pill or alert role
    expect(info0.querySelector("[data-pill='neutral']")).not.toBeNull();
    expect(info0.querySelector("[data-pill='bad']")).toBeNull();
    expect(info0.querySelector("[data-pill='warn']")).toBeNull();
    expect(within(info0).queryByRole("alert")).toBeNull();

    const row1 = screen.getByTestId("event-trigger-row-1");
    const info1 = within(row1).getByTestId("no-change-events-info");
    expect(info1).toHaveTextContent("No policy-install events received for 18 days");
    expect(info1.querySelector("[data-pill='bad']")).toBeNull();

    const row2 = screen.getByTestId("event-trigger-row-2");
    expect(within(row2).queryByTestId("no-change-events-info")).toBeNull();

    const row3 = screen.getByTestId("event-trigger-row-3");
    expect(within(row3).queryByTestId("no-change-events-info")).toBeNull();

    const panelText = screen.getByTestId("event-trigger-status-panel").textContent ?? "";
    expect(panelText.toLowerCase()).not.toContain("no changes");
  });

  it("pins all 5 PendingDeferState values", () => {
    const states: ReadonlyArray<{
      readonly state: PendingDeferState;
      readonly statusText: string | null;
      readonly expectedText: string;
      readonly expectedTone: string | null;
    }> = [
      { state: "NONE", statusText: null, expectedText: "Idle", expectedTone: null },
      {
        state: "DEBOUNCING",
        statusText: "Change detected — collection debouncing (due 2026-11-27T12:01:30Z)",
        expectedText: "DEBOUNCING",
        expectedTone: "brand",
      },
      {
        state: "DEFERRED_SOURCE_BUSY",
        statusText: "Deferred: management source busy (DEFERRED_SOURCE_BUSY)",
        expectedText: "DEFERRED_SOURCE_BUSY",
        expectedTone: "warn",
      },
      {
        state: "DEFERRED_BACKUP_WINDOW",
        statusText: "Held for 01:45–03:00 backup window (DEFERRED_BACKUP_WINDOW)",
        expectedText: "DEFERRED_BACKUP_WINDOW",
        expectedTone: "warn",
      },
      {
        state: "DEFERRED_WINDOW_GUARD",
        statusText: "Held for scheduled collection window guard (DEFERRED_WINDOW_GUARD)",
        expectedText: "DEFERRED_WINDOW_GUARD",
        expectedTone: "warn",
      },
    ];

    const projection = makeProjection({
      units: states.map((s, idx) =>
        makeRow({
          displayName: idx === 0 ? "DOM-TANGO-04" : idx === 1 ? "CLS-ROMEO-01" : "FW-JULIET-06",
          pendingDeferState: s.state,
          pendingStatusText: s.statusText,
          triggerDueAt: s.state === "NONE" ? null : "2026-11-27T12:01:30Z",
        })
      ),
    });

    render(<EventTriggerStatusPanel projection={projection} killSwitch={ACTIVE_KILL_SWITCH} now={NOW} />);

    states.forEach((s, idx) => {
      const meta = describePendingDefer(s.state, s.statusText);
      expect(meta.state).toBe(s.state);

      const row = screen.getByTestId(`event-trigger-row-${idx}`);
      const pendingCell = row.querySelector(`[data-pending-state="${s.state}"]`);
      expect(pendingCell).not.toBeNull();
      expect(pendingCell).toHaveTextContent(s.expectedText);
      if (s.expectedTone) {
        expect(pendingCell?.querySelector(`[data-pill="${s.expectedTone}"]`)).not.toBeNull();
      }
      if (s.statusText) {
        expect(pendingCell).toHaveTextContent(s.statusText);
      }
    });
  });

  it("pins kill-switch banner for active, global halted, vendor/source halted, and fail-closed unknown states", () => {
    const projection = makeProjection();

    // 1. Active
    const { rerender } = render(
      <EventTriggerStatusPanel projection={projection} killSwitch={ACTIVE_KILL_SWITCH} now={NOW} />
    );
    let banner = screen.getByTestId("event-trigger-kill-switch-banner");
    expect(banner).toHaveAttribute("data-kill-switch-state", "ACTIVE");
    expect(banner.querySelector("[data-pill='ok']")).not.toBeNull();
    expect(banner).toHaveTextContent("ACTIVE");

    // 2. Globally halted
    rerender(
      <EventTriggerStatusPanel
        projection={projection}
        killSwitch={{ globalEnabled: false, haltedVendors: [], haltedSources: [], failClosedUnknown: false }}
        now={NOW}
      />
    );
    banner = screen.getByTestId("event-trigger-kill-switch-banner");
    expect(banner).toHaveAttribute("data-kill-switch-state", "GLOBAL_HALTED");
    expect(banner.querySelector("[data-pill='bad']")).not.toBeNull();
    expect(banner).toHaveTextContent("GLOBAL HALTED");

    // 3. Vendor + source halted (derived via summarizeKillSwitchSettings)
    const partialSummary = summarizeKillSwitchSettings({
      globalEnabled: true,
      vendorEnabled: { checkpoint: false, paloalto: true },
      eventSourceEnabled: { "00000000-0000-4000-8000-000000000101": false },
    });
    rerender(<EventTriggerStatusPanel projection={projection} killSwitch={partialSummary} now={NOW} />);
    banner = screen.getByTestId("event-trigger-kill-switch-banner");
    expect(banner).toHaveAttribute("data-kill-switch-state", "PARTIAL_HALTED");
    expect(banner.querySelector("[data-pill='warn']")).not.toBeNull();
    expect(banner).toHaveTextContent("PARTIALLY HALTED");
    expect(banner).toHaveTextContent("Check Point");
    expect(banner).toHaveTextContent("00000000-0000-4000-8000-000000000101");

    // 4. Fail-closed unknown (explicit flag and null snapshot)
    const missingSnapshotSummary = summarizeKillSwitchSettings(null);
    expect(missingSnapshotSummary.failClosedUnknown).toBe(true);
    expect(describeKillSwitchBanner(missingSnapshotSummary).status).toBe("UNKNOWN");

    rerender(<EventTriggerStatusPanel projection={projection} killSwitch={missingSnapshotSummary} now={NOW} />);
    banner = screen.getByTestId("event-trigger-kill-switch-banner");
    expect(banner).toHaveAttribute("data-kill-switch-state", "UNKNOWN");
    expect(banner.querySelector("[data-pill='unknown']")).not.toBeNull();
    expect(banner).toHaveTextContent(/failing closed/i);

    rerender(<EventTriggerStatusPanel projection={null} killSwitch={null} now={NOW} />);
    banner = screen.getByTestId("event-trigger-kill-switch-banner");
    expect(banner).toHaveAttribute("data-kill-switch-state", "UNKNOWN");
    expect(screen.getByText("Event-trigger liveness unavailable")).toBeInTheDocument();
  });

  it("refuses to render unit rows unless the projection is explicitly masked", () => {
    const unmasked = [false, undefined].map((masked) =>
      makeProjection({
        masked: masked as unknown as boolean,
        units: [makeRow({ displayName: "FW-TANGO-04", pendingDeferState: "DEBOUNCING", pendingStatusText: "FW-TANGO-04 raw" })],
      }),
    );
    for (const projection of unmasked) {
      const { unmount } = render(
        <EventTriggerStatusPanel projection={projection} killSwitch={ACTIVE_KILL_SWITCH} now={NOW} />,
      );
      expect(screen.getByText("Masking not confirmed")).toBeInTheDocument();
      expect(screen.queryByTestId("event-trigger-units-table")).toBeNull();
      expect(screen.queryByTestId("event-trigger-fleet-coverage")).toBeNull();
      expect(screen.getByTestId("event-trigger-status-panel").textContent ?? "").not.toContain("FW-TANGO-04");
      unmount();
    }
  });

  it("renders only the closed pending wording, never free text", () => {
    const meta = describePendingDefer("DEBOUNCING", "Ask admin of FW-TANGO-04 192.0.2.7");
    expect(meta.detailText).toBe("Change detected — collection debouncing");
    expect(describePendingDefer("DEBOUNCING", "Change detected — collection debouncing (due 2026-11-27T12:01:30Z)").detailText)
      .toBe("Change detected — collection debouncing (due 2026-11-27T12:01:30Z)");
  });

  it("never invents a day count when the silence message is missing or malformed", () => {
    for (const noChangeEventsMessage of [null, "garbage 192.0.2.7"]) {
      const notice = describeNoChangeEvents(
        makeRow({ noChangeEventsFlag: true, noChangeEventsMessage, lastReceivedAt: "2026-11-01T00:00:00Z" }),
      );
      expect(notice?.message).toBe("No policy-install events received (day count unavailable)");
      expect(notice?.message).not.toMatch(/\d/);
    }
  });

  it("shows a raised silence flag even when lastReceivedAt is missing", () => {
    const row = makeRow({ noChangeEventsFlag: true, lastReceivedAt: null, noChangeEventsMessage: null });
    expect(describeNoChangeEvents(row)?.code).toBe("NO_CHANGE_EVENTS_N_DAYS");
    render(<EventTriggerStatusPanel projection={makeProjection({ units: [row] })} killSwitch={ACTIVE_KILL_SWITCH} now={NOW} />);
    expect(within(screen.getByTestId("event-trigger-row-0")).getByTestId("no-change-events-info")).toBeInTheDocument();
  });

  it("treats a missing globalEnabled as UNKNOWN, and draws unreadable coverage as unknown", () => {
    for (const globalEnabled of [undefined, null]) {
      const summary = summarizeKillSwitchSettings({ globalEnabled, vendorEnabled: {}, eventSourceEnabled: {} });
      expect(summary.failClosedUnknown).toBe(true);
      expect(describeKillSwitchBanner(summary).status).toBe("UNKNOWN");
    }
    render(
      <EventTriggerStatusPanel
        projection={makeProjection({
          fleetCoverageByVendor: { checkpoint: { vendor: "checkpoint", healthyLiveUnits: 6, totalUnits: 5 } },
        })}
        killSwitch={ACTIVE_KILL_SWITCH}
        now={NOW}
      />,
    );
    const card = screen.getByTestId("fleet-coverage-checkpoint");
    expect(card.querySelector("[data-pill='unknown']")).not.toBeNull();
    expect(card).not.toHaveTextContent("live event-covered units");
  });
});
