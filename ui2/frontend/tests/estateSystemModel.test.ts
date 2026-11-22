import { describe, expect, it } from "vitest";
import type {
  AuditEventView,
  DeviceSummary,
  ScheduledJobFamily,
  ScheduledJobsView,
  SystemHealth,
} from "../src/auth/adminApi";
import {
  AUDIT_PREVIEW_CAP,
  buildSystemSummary,
  summariseAudit,
  summariseEnrolment,
  summarisePlatform,
  summariseRuns,
  type TimedRead,
} from "../src/estate/estateSystemModel";
import { resolveEstateRoute, systemHref } from "../src/estate/estateRouting";

const AT = "2026-11-22T08:00:00.000Z";
const ok = <T,>(value: T): TimedRead<T> => ({ read: { state: "ok", value }, readAt: AT });
const failed = <T,>(): TimedRead<T> => ({ read: { state: "failed" }, readAt: null });
const restricted = <T,>(): TimedRead<T> => ({ read: { state: "restricted" }, readAt: AT });

function family(over: Partial<ScheduledJobFamily> & { family: string }): ScheduledJobFamily {
  return {
    label: over.family,
    description: "",
    cadence: "",
    enabled: true,
    state: "ON",
    lock_reason: null,
    will_run: true,
    note: null,
    window_open_now: false,
    next_window_at: "2026-11-22T22:00:00+00:00",
    last_run_at: null,
    changed_by: null,
    changed_at: null,
    reason: null,
    ...over,
  };
}

function jobsView(families: ScheduledJobFamily[], masterEnabled = true): ScheduledJobsView {
  return {
    master_enabled: masterEnabled,
    master_env: "SYNTHETIC_MASTER",
    can_write: false,
    masked: true,
    business_hours: null,
    families,
  };
}

function device(id: string, enrollment: string, latest?: string): DeviceSummary {
  return {
    device_id: id,
    vendor_hint: "CHECK_POINT",
    enrollment_state: enrollment,
    hostname: null,
    model: null,
    software_version: null,
    ha_role: null,
    cluster_member_ref: null,
    latest_job_state: latest ?? null,
  };
}

function health(state: SystemHealth["computation"]["state"], kpis?: Partial<SystemHealth["kpis"]>): SystemHealth {
  return {
    computation: { state, computed_at: "2026-11-22T06:30:00.000Z", duration_ms: 10, snapshot_ttl_seconds: 900, store_available: true },
    window_days: 7,
    cpu_p95_threshold_percent: 80,
    time_basis: "UTC",
    rules: {
      critical: [], attention: [], normal: "", unknown: "", state_order: ["CRITICAL", "ATTENTION", "NORMAL", "UNKNOWN"], p95: "",
      restart_rule: { id: "R", status: "", text: "" },
    },
    kpis: {
      monitored: { devices: 4, by_vendor: {}, not_collected_listed: 0 },
      crash_alerts: { devices: 0, alerts: 0, truncated: false },
      cpu_over_threshold: { devices: 0, evidenced: 3, unknown: 1, not_applicable: 0, basis: "" },
      collection_not_ok: { devices: 0, by_state: {} },
      ...kpis,
    },
    devices: [],
  };
}

function audit(n: number): AuditEventView[] {
  return Array.from({ length: n }, (_, i) => ({
    id: `E-${i}`,
    occurred_at: new Date(Date.parse("2026-11-22T07:00:00Z") - i * 60_000).toISOString(),
    actor: "USR-SYNTH",
    action: `ACTION_${i}`,
    target: "FW-TANGO-04",
    outcome: "OK",
  }));
}

describe("Estate System summary: What runs", () => {
  it("counts families that will run, idle, off and locked, and devices with a job in flight", () => {
    const s = summariseRuns(
      ok(jobsView([
        family({ family: "a" }),
        family({ family: "b", will_run: false }),
        family({ family: "c", state: "OFF", enabled: false, will_run: false, next_window_at: null }),
        family({ family: "d", state: "LOCKED", will_run: false }),
      ])),
      ok([device("1", "ENROLLED", "EXECUTING"), device("2", "ENROLLED", "requested"), device("3", "ENROLLED", "SUCCEEDED")]),
    );
    const byLabel = Object.fromEntries(s.lines.map((l) => [l.label, l.value]));
    expect(byLabel["Job families that will run"]).toBe("1 of 4");
    expect(byLabel["Switched on but idle"]).toBe("1");
    expect(byLabel["Switched off"]).toBe("1");
    expect(byLabel["Locked by a deployment switch"]).toBe("1");
    expect(byLabel["Devices with a job in flight (latest job EXECUTING, CLAIMED or REQUESTED)"]).toBe("2");
    expect(s.badge).toBe("na");
    expect(s.standardHref).toBe("?screen=administration&tab=scheduled-jobs&standard=1");
  });

  it("is a review item when the deployment switch stops every family", () => {
    const s = summariseRuns(ok(jobsView([family({ family: "a", state: "LOCKED", will_run: false })], false)), ok([]));
    expect(s.badge).toBe("watch");
  });

  it("is UNKNOWN, never OK, when the job list cannot be read", () => {
    const s = summariseRuns(failed(), ok([]));
    expect(s.badge).toBe("unk");
    expect(s.lines[0].value).toMatch(/^UNKNOWN/);
  });

  it("says Restricted when the job list is refused", () => {
    const s = summariseRuns(restricted(), ok([]));
    expect(s.badge).toBe("na");
    expect(s.word).toBe("Restricted");
  });

  it("marks the in-flight count UNKNOWN when only the device list is unreadable", () => {
    const s = summariseRuns(ok(jobsView([family({ family: "a" })])), failed());
    expect(s.lines.at(-1)?.value).toMatch(/^UNKNOWN/);
  });
});

describe("Estate System summary: Enrolment", () => {
  it("counts the registry and caps the state list with +N more naming the rest", () => {
    const states = ["ENROLLED", "ENROLLED", "PENDING", "PENDING", "PENDING", "REVOKED", "NEW_ONE", "OTHER_ONE", "THIRD_ONE"];
    const s = summariseEnrolment(ok(states.map((st, i) => device(String(i), st))));
    expect(s.lines[0]).toEqual({ label: "Devices in the registry", value: String(states.length) });
    expect(s.lines.length).toBe(1 + 4);
    expect(s.more).toMatch(/^\+\d+ more kinds \(\d+ devices\)$/);
    expect(s.badge).toBe("na");
    expect(s.evidenceAt).toBe(AT);
  });

  it("is UNKNOWN when unreadable", () => {
    expect(summariseEnrolment(failed()).badge).toBe("unk");
  });
});

describe("Estate System summary: Platform", () => {
  it("is OK only when the snapshot is READY and every figure is known and zero", () => {
    const s = summarisePlatform(ok(health("READY", {
      crash_alerts: { devices: 0, alerts: 0, truncated: false },
      cpu_over_threshold: { devices: 0, evidenced: 3, unknown: 0, not_applicable: 0, basis: "" },
      collection_not_ok: { devices: 0, by_state: {} },
    })));
    expect(s.badge).toBe("ok");
    expect(s.evidenceAt).toBe("2026-11-22T06:30:00.000Z");
  });

  it("is a review item when a flag count is above zero", () => {
    expect(summarisePlatform(ok(health("READY", { crash_alerts: { devices: 1, alerts: 2, truncated: false } }))).badge).toBe("watch");
  });

  it("shows UNKNOWN, not 0, for a figure that is not evidenced", () => {
    const s = summarisePlatform(ok(health("READY", { cpu_over_threshold: null })));
    expect(s.badge).toBe("unk");
    expect(s.lines.find((l) => l.label === "Devices with CPU over the threshold")?.value).toBe("UNKNOWN");
  });

  it("never shows OK while the snapshot is still computing", () => {
    const s = summarisePlatform(ok(health("COMPUTING")));
    expect(s.badge).toBe("unk");
    expect(s.lines[0].value).toMatch(/COMPUTING/);
  });

  it("is an act item when the computation failed", () => {
    expect(summarisePlatform(ok(health("FAILED"))).badge).toBe("act");
  });

  it("is UNKNOWN when the health read fails", () => {
    expect(summarisePlatform(failed()).badge).toBe("unk");
  });
});

describe("Estate System summary: Audit", () => {
  it("shows the newest entries first, capped, with +N more", () => {
    const s = summariseAudit(ok(audit(AUDIT_PREVIEW_CAP + 3)));
    expect(s.lines[0].value).toBe(String(AUDIT_PREVIEW_CAP + 3));
    expect(s.lines.length).toBe(1 + AUDIT_PREVIEW_CAP);
    expect(s.lines[1].value).toContain("ACTION_0");
    expect(s.more).toBe("+3 more");
  });

  it("has no +N more when the whole trail fits", () => {
    expect(summariseAudit(ok(audit(2))).more).toBeNull();
  });

  it("says Restricted for a role without audit access", () => {
    expect(summariseAudit(restricted()).word).toBe("Restricted");
  });
});

describe("buildSystemSummary", () => {
  it("returns the four sections in screen order, each linking to a standard screen with standard=1", () => {
    const all = buildSystemSummary({
      jobs: ok(jobsView([family({ family: "a" })])),
      devices: ok([device("1", "ENROLLED")]),
      health: ok(health("READY")),
      audit: ok(audit(1)),
    });
    expect(all.map((s) => s.id)).toEqual(["runs", "enrolment", "platform", "audit"]);
    for (const s of all) {
      expect(s.standardHref).toContain("standard=1");
      expect(s.standardHref.startsWith("?screen=")).toBe(true);
    }
  });
});

describe("System route", () => {
  it("resolves the Estate System screen only with view=system; the bare administration address stays standard", () => {
    expect(resolveEstateRoute("administration", "/", systemHref())).toEqual({ kind: "system" });
    expect(resolveEstateRoute("administration", "/", "?screen=administration")).toBeNull();
    expect(resolveEstateRoute("administration", "/", "?screen=administration&view=system&standard=1")).toBeNull();
  });
});
