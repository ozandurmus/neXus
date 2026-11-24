import { describe, expect, it } from "vitest";
import type { ScriptListResponse, ScriptRunSummary, ScriptSummary } from "../src/auth/adminApi";
import {
  DEFAULT_SCHEDULE_ZONE,
  SCRIPT_KINDS,
  describeApiError,
  describeRunNotification,
  extractAliasWarnings,
  formatRunAs,
  inferScriptKindFromExtension,
  notifySendsMail,
  parseEgressInput,
  resolveScheduleZone,
  stemFromFileName,
} from "../src/screens/ScriptFormHelpers";

function makeRunSummary(overrides: Partial<ScriptRunSummary> = {}): ScriptRunSummary {
  return {
    job_id: "run-101",
    run_id: "run-101",
    script_id: "scr-sample",
    version: 1,
    state: "COMPLETED",
    outcome: "SUCCESS",
    trigger_kind: "MANUAL",
    scheduled_slot: null,
    run_as: "role:operator",
    requested_by: "USR-0a1b2c3d4e",
    requested_at: "2026-11-24T08:00:00Z",
    started_at: "2026-11-24T08:00:01Z",
    finished_at: "2026-11-24T08:00:05Z",
    duration_ms: 4000,
    exit_code: 0,
    stdout_bytes: 12,
    stderr_bytes: 0,
    stdout_truncated: false,
    stderr_truncated: false,
    has_output: true,
    output_sha256: null,
    output_size_bytes: 12,
    output_tail: null,
    egress: [],
    egress_device_targets: [],
    credential_refs_used: [],
    failure_reason: null,
    ...overrides,
  };
}

function makeScriptSummary(overrides: Partial<ScriptSummary> = {}): ScriptSummary {
  return {
    script_id: "scr-sample",
    name: "sample-check",
    kind: "sh",
    current_version: 1,
    sha256: null,
    size_bytes: 64,
    secret_findings: { count: 0, line_numbers: [], findings: [] },
    enabled: true,
    schedule: null,
    next_five_fires: [],
    timeout_s: 300,
    egress: [],
    egress_device_targets: [],
    credential_refs: [],
    notify: "none",
    run_as: "role:operator",
    created_by_actor_fingerprint: "USR-0a1b2c3d4e",
    created_at: "2026-11-24T07:00:00Z",
    updated_at: "2026-11-24T07:00:00Z",
    warnings: [],
    ...overrides,
  };
}

function makeScriptList(overrides: Partial<ScriptListResponse> = {}): ScriptListResponse {
  return {
    scripts: [],
    masked: true,
    ...overrides,
  };
}

describe("inferScriptKindFromExtension", () => {
  it("recognizes every supported script extension, including paths and upper case", () => {
    for (const kind of SCRIPT_KINDS) {
      expect(inferScriptKindFromExtension(`sample-check.${kind}`)).toEqual({
        kind,
        isBatOrCmd: false,
        isPs1: false,
        unsupportedExtension: null,
      });
    }
    expect(inferScriptKindFromExtension("  /opt/scripts/SAMPLE-CHECK.SH  ")).toEqual({
      kind: "sh",
      isBatOrCmd: false,
      isPs1: false,
      unsupportedExtension: null,
    });
    expect(inferScriptKindFromExtension("C:\\scripts\\RunCheck.PY")).toEqual({
      kind: "py",
      isBatOrCmd: false,
      isPs1: false,
      unsupportedExtension: null,
    });
  });

  it("flags .bat, .cmd, .ps1, and unknown extensions with null kind", () => {
    expect(inferScriptKindFromExtension("legacy.BAT")).toEqual({
      kind: null,
      isBatOrCmd: true,
      isPs1: false,
      unsupportedExtension: "bat",
    });
    expect(inferScriptKindFromExtension("legacy.cmd")).toEqual({
      kind: null,
      isBatOrCmd: true,
      isPs1: false,
      unsupportedExtension: "cmd",
    });
    expect(inferScriptKindFromExtension("deploy.PS1")).toEqual({
      kind: null,
      isBatOrCmd: false,
      isPs1: true,
      unsupportedExtension: "ps1",
    });
    expect(inferScriptKindFromExtension("notes.TXT")).toEqual({
      kind: null,
      isBatOrCmd: false,
      isPs1: false,
      unsupportedExtension: "txt",
    });
  });

  it("returns all-clear nulls when extension is missing, empty, or a dotfile", () => {
    const emptyResult = {
      kind: null,
      isBatOrCmd: false,
      isPs1: false,
      unsupportedExtension: null,
    };
    expect(inferScriptKindFromExtension("sample-check")).toEqual(emptyResult);
    expect(inferScriptKindFromExtension("dir.with.dots/noext")).toEqual(emptyResult);
    expect(inferScriptKindFromExtension(".hidden")).toEqual(emptyResult);
    expect(inferScriptKindFromExtension("trailing.")).toEqual(emptyResult);
    expect(inferScriptKindFromExtension("")).toEqual(emptyResult);
    expect(inferScriptKindFromExtension("   ")).toEqual(emptyResult);
  });
});

describe("stemFromFileName", () => {
  it("strips directories and the final extension and normalizes unsafe characters", () => {
    expect(stemFromFileName("sample-check.sh")).toBe("sample-check");
    expect(stemFromFileName("  /var/scripts/nightly check!.sh  ")).toBe("nightly-check");
    expect(stemFromFileName("C:\\tools\\fw.tango.04.v2.py")).toBe("fw.tango.04.v2");
    expect(stemFromFileName("plain-script")).toBe("plain-script");
  });

  it("handles dotfiles, empty input, and delimiter-only names", () => {
    expect(stemFromFileName(".env")).toBe(".env");
    expect(stemFromFileName("")).toBe("");
    expect(stemFromFileName("   ")).toBe("");
    expect(stemFromFileName("---")).toBe("");
  });
});

describe("parseEgressInput", () => {
  it("splits on commas and newlines, trims whitespace, skips blank lines, and deduplicates", () => {
    const raw = "\n  192.0.2.10  \n\n  192.0.2.20:443 , 192.0.2.10\n  198.51.100.5  , , \n";
    expect(parseEgressInput(raw)).toEqual(["192.0.2.10", "192.0.2.20:443", "198.51.100.5"]);
  });

  it("returns an empty array for empty or whitespace/separator-only input", () => {
    expect(parseEgressInput("")).toEqual([]);
    expect(parseEgressInput("   \n , \n\t ")).toEqual([]);
  });
});

describe("notifySendsMail", () => {
  it("returns true only for smtp and both, and false for non-mail, empty, null, and undefined", () => {
    expect(notifySendsMail("smtp")).toBe(true);
    expect(notifySendsMail("both")).toBe(true);
    expect(notifySendsMail("syslog")).toBe(false);
    expect(notifySendsMail("none")).toBe(false);
    expect(notifySendsMail("")).toBe(false);
    expect(notifySendsMail("UNKNOWN")).toBe(false);
    expect(notifySendsMail(null)).toBe(false);
    expect(notifySendsMail(undefined)).toBe(false);
  });
});

describe("describeRunNotification", () => {
  it("handles run summaries without the optional notification field", () => {
    const unfinished = makeRunSummary({ finished_at: null, state: "RUNNING", outcome: null });
    expect(describeRunNotification(unfinished)).toEqual({
      tone: "neutral",
      word: "—",
      title: "The run has not finished; notifications are sent at run end.",
    });

    const finishedWithoutField = makeRunSummary();
    expect(describeRunNotification(finishedWithoutField)).toEqual({
      tone: "unknown",
      word: "Not recorded",
      title: "No notification outcome is recorded for this run (not processed yet, or the run finished before run notifications existed).",
    });

    const finishedWithNull = makeRunSummary({ notification: null });
    expect(describeRunNotification(finishedWithNull).tone).toBe("unknown");
  });

  it("formats recorded notification outcomes, including warn, bad, ok, neutral, and unknown states", () => {
    expect(
      describeRunNotification(
        makeRunSummary({
          notification: {
            syslog: "NOT_REQUESTED",
            syslog_code: null,
            smtp: "NOT_REQUESTED",
            smtp_code: null,
            notified_at: "2026-11-24T08:01:00Z",
          },
        }),
      ),
    ).toEqual({
      tone: "neutral",
      word: "Not requested",
      title: "The script asks for no notification. Recorded 2026-11-24T08:01:00Z.",
    });

    expect(
      describeRunNotification(
        makeRunSummary({
          notification: {
            syslog: "SENT",
            syslog_code: null,
            smtp: "SKIPPED",
            smtp_code: "failure_only",
            notified_at: "2026-11-24T08:01:00Z",
          },
        }),
      ),
    ).toEqual({
      tone: "ok",
      word: "Syslog sent · Mail skipped",
      title: "Syslog sent · Mail skipped: mail on failure only, and the run succeeded. Recorded 2026-11-24T08:01:00Z.",
    });

    expect(
      describeRunNotification(
        makeRunSummary({
          notification: {
            syslog: "NOT_REQUESTED",
            syslog_code: null,
            smtp: "SKIPPED",
            smtp_code: "no_recipients",
            notified_at: "2026-11-24T08:01:00Z",
          },
        }),
      ),
    ).toEqual({
      tone: "warn",
      word: "Mail skipped",
      title: "Mail skipped: no mail recipients are configured in Settings > Notifications. Recorded 2026-11-24T08:01:00Z.",
    });

    expect(
      describeRunNotification(
        makeRunSummary({
          notification: {
            syslog: "SKIPPED",
            syslog_code: "syslog_not_configured",
            smtp: "FAILED",
            smtp_code: "smtp_not_configured",
            notified_at: "2026-11-24T08:01:00Z",
          },
        }),
      ),
    ).toEqual({
      tone: "bad",
      word: "Syslog skipped · Mail failed",
      title:
        "Syslog skipped: syslog is not configured in Settings > Notifications · Mail failed: the SMTP relay is not configured in Settings > Notifications. Recorded 2026-11-24T08:01:00Z.",
    });

    expect(
      describeRunNotification(
        makeRunSummary({
          notification: {
            syslog: "",
            syslog_code: null,
            smtp: "CUSTOM_STATE",
            smtp_code: null,
            notified_at: "2026-11-24T08:01:00Z",
          },
        }),
      ),
    ).toEqual({
      tone: "unknown",
      word: "UNKNOWN",
      title: "Syslog UNKNOWN · Mail CUSTOM_STATE. Recorded 2026-11-24T08:01:00Z.",
    });
  });
});

describe("extractAliasWarnings", () => {
  it("returns an empty list for undefined or empty warnings", () => {
    expect(extractAliasWarnings(undefined)).toEqual([]);
    expect(extractAliasWarnings([])).toEqual([]);
  });

  it("keeps only credential alias and variable-name collision warnings", () => {
    const mixed = [
      "Timeout is above 300s",
      "Two credentials resolve to NEXUS_CRED_API_KEY",
      "Both references map to the same variable name",
      "Alias mismatch on FW-TANGO-04",
      "Schedule cron fires every minute",
    ];
    expect(extractAliasWarnings(mixed)).toEqual([
      "Two credentials resolve to NEXUS_CRED_API_KEY",
      "Both references map to the same variable name",
      "Alias mismatch on FW-TANGO-04",
    ]);
  });
});

describe("resolveScheduleZone", () => {
  it("prefers detail schedule_zone, falls back to list schedule_zone, then DEFAULT_SCHEDULE_ZONE", () => {
    expect(resolveScheduleZone(null)).toBe(DEFAULT_SCHEDULE_ZONE);
    expect(resolveScheduleZone(makeScriptList(), makeScriptSummary())).toBe(DEFAULT_SCHEDULE_ZONE);
    expect(resolveScheduleZone(makeScriptList({ schedule_zone: "UTC" }), null)).toBe("UTC");
    expect(
      resolveScheduleZone(
        makeScriptList({ schedule_zone: "UTC" }),
        makeScriptSummary({ schedule_zone: "" }),
      ),
    ).toBe("UTC");
    expect(
      resolveScheduleZone(
        makeScriptList({ schedule_zone: "UTC" }),
        makeScriptSummary({ schedule_zone: "Europe/Berlin" }),
      ),
    ).toBe("Europe/Berlin");
  });
});

describe("formatRunAs", () => {
  it("formats role tokens, actor fingerprints, masked actors, service actors, and empty input", () => {
    expect(formatRunAs("role:operator")).toBe("Operator (role:operator)");
    expect(formatRunAs("role:replay_viewer")).toBe("aiview · read-only (role:replay_viewer)");
    expect(formatRunAs("USR-0a1b2c3d4e")).toBe("USR-0a1b2c3d4e");
    expect(formatRunAs("SN-0A1B2C3D4E")).toBe("Masked actor");
    expect(formatRunAs("system:scheduler")).toBe("System:Scheduler (system:scheduler)");
    expect(formatRunAs("")).toBe("UNKNOWN");
    expect(formatRunAs("   ")).toBe("UNKNOWN");
  });
});

describe("describeApiError", () => {
  it("extracts body message, reason, or error with status fallback", () => {
    expect(
      describeApiError({
        status: 400,
        body: { message: "Invalid schedule", reason: "bad_cron", error: "validation_error" },
      }),
    ).toBe("Invalid schedule");
    expect(
      describeApiError({
        status: 400,
        body: { message: "   ", reason: "bad_cron", error: "validation_error" },
      }),
    ).toBe("bad_cron");
    expect(
      describeApiError({
        status: 422,
        body: { error: "unprocessable" },
      }),
    ).toBe("unprocessable");
    expect(describeApiError({ status: 403, body: {} })).toBe("Request failed (status 403).");
  });

  it("handles Error instances, plain strings, and unknown objects", () => {
    expect(describeApiError(new Error("Network timed out"))).toBe("Network timed out");
    expect(describeApiError(new Error(""))).toBe("Request failed.");
    expect(describeApiError("plain string error")).toBe("Request failed.");
    expect(describeApiError({ detail: "ignored" })).toBe("Request failed.");
    expect(describeApiError(null)).toBe("Request failed.");
    expect(describeApiError(undefined)).toBe("Request failed.");
  });
});
