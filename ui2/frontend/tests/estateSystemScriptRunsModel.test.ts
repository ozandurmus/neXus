import { describe, expect, it } from "vitest";
import type { ScriptRunSummary, ScriptSummary } from "../src/auth/adminApi";
import {
  SCRIPT_RUNS_READ_CAP,
  SYSTEM_LIST_CAP,
  summariseRuns,
  summariseScriptRunRow,
  type ScriptRunRead,
  type TimedRead,
} from "../src/estate/estateSystemModel";

// Synthetic fixtures only (FW-TANGO-04 style names). Nothing here is a real host, address or credential.
const AT = "2026-11-22T08:00:00.000Z";
const ok = <T,>(value: T): TimedRead<T> => ({ read: { state: "ok", value }, readAt: AT });
const failed = <T,>(): TimedRead<T> => ({ read: { state: "failed" }, readAt: null });
const restricted = <T,>(): TimedRead<T> => ({ read: { state: "restricted" }, readAt: AT });
const jobsOk = ok({ master_enabled: true, families: [] } as never);
const devicesOk = ok([] as never);

const script = (name: string, over: Partial<ScriptSummary> = {}): ScriptSummary =>
  ({ script_id: `sid-${name}`, name, enabled: true, schedule: "0 2 * * *", ...over }) as ScriptSummary;

const run = (requestedAt: string, over: Partial<ScriptRunSummary> = {}): ScriptRunSummary =>
  ({ run_id: `r-${requestedAt}`, state: "FINISHED", outcome: "SUCCESS", requested_at: requestedAt, ...over }) as ScriptRunSummary;

const rowOk = (s: ScriptSummary, runs: ScriptRunSummary[]): ScriptRunRead => ({
  script: s, read: { state: "ok", value: runs }, readAt: AT,
});

function lineFor(section: ReturnType<typeof summariseRuns>, label: string): string | undefined {
  return section.lines.find((l) => l.label === label)?.value;
}

describe("What runs: script row summary", () => {
  it("a script with no listed run says No runs, never a zero-run OK", () => {
    const row = summariseScriptRunRow(rowOk(script("FW-TANGO-04-backup"), []));
    expect(row.label).toBe("FW-TANGO-04-backup");
    expect(row.value).toContain("No runs");
    expect(row.value).not.toMatch(/\bOK\b/);
  });

  it("a failed runs read is UNKNOWN, never OK", () => {
    const row = summariseScriptRunRow({ script: script("FW-TANGO-04-sweep"), read: { state: "failed" }, readAt: null });
    expect(row.value).toContain("UNKNOWN: runs not read");
    expect(row.value).not.toMatch(/\bOK\b/);
  });

  it("a refused runs read is Restricted, not UNKNOWN and not OK", () => {
    const row = summariseScriptRunRow({ script: script("FW-TANGO-04-sweep"), read: { state: "restricted" }, readAt: AT });
    expect(row.value).toContain("Restricted for this account");
  });

  it("shows the schedule state, the last run and the run count with what it counts", () => {
    const runs = [run("2026-11-20T02:00:00.000Z", { outcome: "FAILED" }), run("2026-11-21T02:00:00.000Z")];
    const row = summariseScriptRunRow(rowOk(script("FW-TANGO-04-sweep"), runs));
    expect(row.value).toContain("On schedule");
    expect(row.value).toContain("last SUCCESS");
    expect(row.value).toContain("2 runs in the list as read");
  });

  it("a disabled script reads as Off", () => {
    const row = summariseScriptRunRow(rowOk(script("FW-TANGO-04-old", { enabled: false }), []));
    expect(row.value.startsWith("Off")).toBe(true);
  });
});

describe("What runs: script section", () => {
  it("caps the rows at SYSTEM_LIST_CAP and prints +N more", () => {
    const scripts = Array.from({ length: SYSTEM_LIST_CAP + 2 }, (_, i) => script(`FW-TANGO-04-job-${i}`));
    const s = summariseRuns(jobsOk, devicesOk, {
      scripts: ok(scripts),
      runs: scripts.map((sc) => rowOk(sc, [])),
    });
    expect(s.lines.filter((l) => l.label.startsWith("FW-TANGO-04-job-"))).toHaveLength(SYSTEM_LIST_CAP);
    expect(s.more).toContain("+2 more scripts");
  });

  it("prints the not-read limit as a stated limit, never as zero runs", () => {
    const scripts = Array.from({ length: SCRIPT_RUNS_READ_CAP + 5 }, (_, i) => script(`FW-TANGO-04-n-${i}`));
    const s = summariseRuns(jobsOk, devicesOk, {
      scripts: ok(scripts),
      runs: scripts.slice(0, SCRIPT_RUNS_READ_CAP).map((sc) => rowOk(sc, [])),
    });
    expect(s.more).toContain("+5 more scripts not read");
    expect(s.more).toContain(`first ${SCRIPT_RUNS_READ_CAP} scripts`);
  });

  it("an unreadable script list is UNKNOWN, not zero scripts", () => {
    const s = summariseRuns(jobsOk, devicesOk, { scripts: failed(), runs: [] });
    expect(lineFor(s, "Script runs")).toBe("UNKNOWN: script list not read");
    expect(s.lines.some((l) => l.label.startsWith("Scripts in the list"))).toBe(false);
  });

  it("states the script list read instant as the evidence stamp of the script rows", () => {
    const s = summariseRuns(jobsOk, devicesOk, { scripts: ok([script("FW-TANGO-04-a")]), runs: [rowOk(script("FW-TANGO-04-a"), [])] });
    expect(lineFor(s, "Scripts in the list (as read)")).toBe("1");
    expect(lineFor(s, "Script list read at")).toBeTruthy();
  });

  it("offers the standard Script Execution link and keeps the scheduled-jobs link", () => {
    const s = summariseRuns(jobsOk, devicesOk, { scripts: ok([]), runs: [] });
    expect(s.extraLinks?.[0].href).toBe("?screen=operations&tab=script-execution&standard=1");
    expect(s.standardHref).toBe("?screen=administration&tab=scheduled-jobs&standard=1");
  });

  it("keeps the script block when the jobs read failed", () => {
    const s = summariseRuns(failed(), devicesOk, { scripts: ok([script("FW-TANGO-04-a")]), runs: [rowOk(script("FW-TANGO-04-a"), [])] });
    expect(s.badge).toBe("unk");
    expect(s.lines.some((l) => l.label === "FW-TANGO-04-a")).toBe(true);
  });
});
