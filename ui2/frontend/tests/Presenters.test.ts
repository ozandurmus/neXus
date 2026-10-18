import { describe, expect, it } from "vitest";
import {
  actorLabel, artefactClassLabel, completedReasonOutcome, formatClock, jobStateWord, jobTypeLabel, outcomeLine, presentRaw,
  readinessReasonLabel, vendorLabel,
} from "../src/shell/presenters";
import { resultWords } from "../src/screens/JobLogsPanel";
import { displayInputToIso, formatStamp, isoToDisplayInput } from "../src/shell/time";

describe("vendor labels", () => {
  it.each([
    ["check_point", "Check Point"], ["palo_alto", "Palo Alto"], ["fortinet", "Fortinet"], ["cisco_asa", "Cisco ASA"],
    ["bluecoat", "Blue Coat"], ["infoblox", "Infoblox"], ["radware", "Radware"], ["pulse_secure", "Pulse Secure"],
  ])("names %s", (id, label) => expect(vendorLabel(id)).toBe(label));

  it("returns an id it does not know as it came, and writes UNKNOWN for an absent one", () => {
    expect(vendorLabel("some_new_vendor")).toBe("some_new_vendor");
    expect(vendorLabel(null)).toBe("UNKNOWN");
    expect(vendorLabel("  ")).toBe("UNKNOWN");
  });
});

describe("job type labels", () => {
  it.each([
    ["pan_failover_readiness", "Palo Alto readiness check"], ["cp_failover_readiness", "Check Point readiness check"],
    ["cp_system_log_collect", "Check Point log collection"], ["pan_system_log_collect", "Palo Alto log collection"],
    ["cp_inventory_collect", "Check Point inventory read"], ["fgt_configuration_collect", "FortiGate configuration read"],
    ["cp_gateway_backup", "Check Point backup"], ["cp_mds_export", "Check Point MDS export"], ["cp_gaia_snapshot", "Check Point Gaia snapshot"],
    ["asa_config_backup", "Cisco ASA backup"], ["rdw_discovery_enumerate", "Radware discovery"], ["bcmc_discovery_enumerate", "Blue Coat discovery"],
    ["device_confirm_palo_alto", "Palo Alto identity check"], ["device_confirm_https", "Identity check"],
    ["packet_analysis_run", "Packet analysis"], ["data_sanitize_run", "Data sanitizer run"],
  ])("labels %s", (id, label) => expect(jobTypeLabel(id)).toBe(label));

  it("uses the vendor hint for the vendor-neutral ids", () => {
    expect(jobTypeLabel("device_confirm_https", "cisco_asa")).toBe("Cisco ASA identity check");
    expect(jobTypeLabel("https_diagnostic", "fortinet")).toBe("Fortinet diagnostic read");
  });

  it("returns a capability id it does not know unchanged", () => {
    expect(jobTypeLabel("brand_new_capability")).toBe("brand_new_capability");
    expect(jobTypeLabel("")).toBe("UNKNOWN");
  });
});

describe("readiness reasons", () => {
  it.each([
    ["CHECK_NOT_READY", "Pre-check failed"], ["INSUFFICIENT_EVIDENCE", "Insufficient evidence"],
    ["COMMAND_UNAVAILABLE", "Command not available on device (login environment)"],
    ["TRUSTED_CONNECTION_REQUIRED", "Trusted connection required"], ["UNSUPPORTED_MODE", "Cluster mode not supported"],
    ["OPEN_INCIDENT", "Open failover incident"],
  ])("says %s in words", (code, words) => expect(readinessReasonLabel(code)).toBe(words));

  it("passes a sentence the server wrote, or an unknown code, through unchanged", () => {
    expect(readinessReasonLabel("the readiness job was cancelled before the run started")).toBe("the readiness job was cancelled before the run started");
    expect(readinessReasonLabel("SOME_FUTURE_CODE")).toBe("SOME_FUTURE_CODE");
    expect(readinessReasonLabel(undefined)).toBe("UNKNOWN");
  });
});

describe("status words", () => {
  it("names job states and keeps UNKNOWN as UNKNOWN", () => {
    expect(jobStateWord("COMPLETED")).toBe("Completed");
    expect(jobStateWord("REQUESTED")).toBe("Queued");
    expect(jobStateWord("EXECUTING")).toBe("Running");
    expect(jobStateWord("OUTCOME_UNKNOWN")).toBe("Outcome UNKNOWN");
    expect(jobStateWord("SOMETHING_NEW")).toBe("SOMETHING_NEW");
    expect(jobStateWord(null)).toBe("UNKNOWN");
  });

  it("writes one outcome line: state, duration, clock time of today", () => {
    const now = new Date("2026-10-18T09:00:00Z");
    expect(outcomeLine({ state: "COMPLETED", durationMs: 8038, at: "2026-10-18T03:01:00Z" }, now)).toBe("Completed · 8.0 s · 06:01");
  });

  it("writes the full stamp for a run that is not from today, and leaves out what is not recorded", () => {
    const now = new Date("2026-10-18T09:00:00Z");
    expect(outcomeLine({ state: "FAILED", durationMs: 52_100, at: "2026-10-17T03:01:00Z" }, now)).toBe("Failed · 52.1 s · 2026-10-17 06:01 GMT+3");
    expect(outcomeLine({ state: "COMPLETED" }, now)).toBe("Completed");
    expect(outcomeLine({ state: "COMPLETED", durationMs: null, at: null }, now)).toBe("Completed");
  });

  it("formats a clock time in display time", () => {
    expect(formatClock("2026-10-18T03:01:00Z")).toBe("06:01");
    expect(formatClock(null)).toBeNull();
  });
});

describe("raw value stays reachable", () => {
  it("returns the raw value beside a known label and none beside the raw fallback", () => {
    expect(presentRaw("pan_failover_readiness", (v) => jobTypeLabel(v))).toEqual({ label: "Palo Alto readiness check", raw: "pan_failover_readiness", known: true });
    expect(presentRaw("brand_new_capability", (v) => jobTypeLabel(v))).toEqual({ label: "brand_new_capability", raw: null, known: false });
    expect(presentRaw(undefined, vendorLabel)).toEqual({ label: "UNKNOWN", raw: null, known: false });
  });

  it("labels stored artefact classes and returns an unknown class as it came", () => {
    expect(artefactClassLabel("system_log")).toBe("System log");
    expect(artefactClassLabel("future_class")).toBe("future_class");
  });
});

describe("the one display stamp", () => {
  it("writes 2026-10-18 06:49 GMT+3 and UNKNOWN for an absent value", () => {
    expect(formatStamp("2026-10-18T03:49:30Z")).toBe("2026-10-18 06:49 GMT+3");
    expect(formatStamp(null)).toBe("UNKNOWN");
    expect(formatStamp("not a date")).toBe("not a date");
  });

  it("converts a picker value typed in display time to the UTC instant and back", () => {
    expect(displayInputToIso("2026-10-18T06:49")).toBe("2026-10-18T03:49:00.000Z");
    expect(isoToDisplayInput("2026-10-18T03:49:00.000Z")).toBe("2026-10-18T06:49");
    expect(displayInputToIso("")).toBeUndefined();
    expect(isoToDisplayInput(null)).toBe("");
  });
});

describe("RESULT words and actors (post-release review 2026-10-18, 2.4 / R8)", () => {
  it("reads a partial log-collection code as words with the raw code kept", () => {
    expect(readinessReasonLabel("partial:READ_SIZE_BELOW_OFFSET")).toBe("Partial \u2014 read size below offset");
    expect(presentRaw("partial:READ_SIZE_BELOW_OFFSET", readinessReasonLabel).raw).toBe("partial:READ_SIZE_BELOW_OFFSET");
  });
  it("takes the outcome word of a completed job from the terminal reason the worker wrote there", () => {
    expect(completedReasonOutcome("READY")).toBe("Ready");
    expect(completedReasonOutcome("NOT_READY")).toBe("Not ready");
    expect(completedReasonOutcome("UNKNOWN")).toBe("UNKNOWN");
    expect(completedReasonOutcome("SYSTEM_LOG_COLLECTED")).toBe("Collected");
    expect(completedReasonOutcome("COMPLETED")).toBeNull();
    expect(completedReasonOutcome("completed in 8038ms")).toBeNull();
    expect(resultWords({ state: "COMPLETED", outcome: null, terminal_reason: "NOT_READY" })).toEqual({ outcome: "Not ready", reason: null });
    expect(resultWords({ state: "COMPLETED", outcome: null, terminal_reason: "SYSTEM_LOG_COLLECTED" })).toEqual({ outcome: "Collected", reason: null });
    // a recorded outcome wins over the reason; a reason that only repeats the state stays hidden
    expect(resultWords({ state: "COMPLETED", outcome: "PARTIAL", terminal_reason: "SYSTEM_LOG_COLLECTED" })).toEqual({ outcome: "Partial", reason: null });
    expect(resultWords({ state: "COMPLETED", outcome: "SUCCESS", terminal_reason: "COMPLETED" })).toEqual({ outcome: null, reason: null });
    expect(resultWords({ state: "FAILED", outcome: null, terminal_reason: "partial:READ_SIZE_BELOW_OFFSET" })).toEqual({ outcome: null, reason: "partial:READ_SIZE_BELOW_OFFSET" });
  });
  it("names a service actor, keeps a masked session token beside its label and never rewrites a user", () => {
    expect(actorLabel("system:cp-failover-worker")).toEqual({ label: "service \u00b7 neXus cp failover worker", raw: "system:cp-failover-worker", known: true });
    expect(actorLabel("SN-2D0959634B")).toEqual({ label: "Masked actor", raw: "SN-2D0959634B", known: true });
    expect(actorLabel("ACTOR-01")).toEqual({ label: "ACTOR-01", raw: null, known: false });
    expect(actorLabel("")).toEqual({ label: "UNKNOWN", raw: null, known: false });
  });
});
