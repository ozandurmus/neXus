import { describe, expect, it } from "vitest";
import type { CpFailoverSummary, DeviceSummary, FailoverIncident, ReadinessCheck, ReadinessMember } from "../src/auth/adminApi";
import { haReadinessItems } from "../src/screens/HaReadinessList";
import { chassisRollup, incidentsUnreadableRow, failoverButtonLabel, groupChecks, reasonLine, rowState, segmentCounts, segmentFromReadiness, splitSummary } from "../src/screens/failover/failoverRowModel";

const AT = "2026-11-20T09:00:00Z";
const members: ReadinessMember[] = [
  { device_id: "m1", hostname: "FW-ROMEO-01", ha_role: "active" }, { device_id: "m2", hostname: "FW-ROMEO-02", ha_role: "standby" }];
const cluster = (ref: string, vendor = "check_point") => ({ ref, title: ref, members: members.map(m => ({ ...m, vendor_hint: vendor, inventory_collected_at: AT })) as unknown as DeviceSummary[] });
type Result = ReadinessCheck["result"];
const check = (no: number, title: string, device: "m1" | "m2", result: Result, summary: string, blocking = true, extra: Partial<ReadinessCheck> = {}): ReadinessCheck & { status: Result; derived: Record<string, unknown> } =>
  ({ checkNo: no, title, member: device === "m1" ? "Member 1" : "Member 2", device_id: device, result, status: result, summary, blocking, derived: {}, observedAt: AT, ...extra } as ReadinessCheck & { status: Result; derived: Record<string, unknown> });
/** One check as both members report it. */
const both = (no: number, title: string, results: [Result, Result], summaries: [string, string] | string, blocking = true, extra: Partial<ReadinessCheck> = {}) => {
  const [a, b] = typeof summaries === "string" ? [summaries, summaries] : summaries;
  return [check(no, title, "m1", results[0], a, blocking, extra), check(no, title, "m2", results[1], b, blocking, extra)];
};
const roles = [check(1, "Cluster state", "m1", "PASS", "Active", true, { derived: { role: "ACTIVE" } }), check(1, "Cluster state", "m2", "PASS", "Standby", true, { derived: { role: "STANDBY" } })];
const summary = (ref: string, status: "READY" | "NOT_READY" | "UNKNOWN" | null, extra: Partial<CpFailoverSummary> = {}, checks: ReadinessCheck[] = []): CpFailoverSummary => ({
  clusterId: ref, unitId: ref, cluster_member_ref: ref, vendor: "check_point", activeWindow: false, lastRunAt: null, lastRunState: null, lastRunOutcome: null,
  masked: false, canRunReadiness: true,
  readiness: status ? { status, observedAt: AT, failedCheck: status === "NOT_READY" ? "State synchronization" : "", checks: checks as never } : null, ...extra });
const itemFor = (ref: string, vendor: string, rows: CpFailoverSummary[]) => haReadinessItems([cluster(ref, vendor)], { [ref]: rows })[0];
const incident = (over: Partial<FailoverIncident>): FailoverIncident => ({ incidentRef: "inc-1", clusterId: "x", unitId: "x", cluster_member_ref: "CLS-OTHER", reason: "r",
  openedAt: AT, incidentActive: true, blocksEstate: false, unresolvedSends: 0, members: [], ...over });

describe("rowState", () => {
  it("a READY Check Point cluster can be failed over", () => {
    const item = itemFor("CLS-A", "check_point", [summary("CLS-A", "READY")]);
    expect(rowState(item, item.base, [], false)).toBe("CAN_FAIL_OVER");
  });
  it("a READY Cisco ASA pair is readiness only, never failover-able", () => {
    const item = itemFor("CLS-A", "cisco_asa", [{ ...summary("CLS-A", "READY"), vendor: "cisco_asa", readinessAvailable: true }]);
    expect(rowState(item, item.base, [], false)).toBe("READINESS_ONLY");
  });
  it("READY with a server refusal is blocked", () => {
    const item = itemFor("CLS-A", "check_point", [summary("CLS-A", "READY", { failoverRefusal: "VSX_MODE_UNKNOWN" })]);
    expect(rowState(item, item.base, [], false)).toBe("BLOCKED");
    const refused = itemFor("CLS-B", "check_point", [summary("CLS-B", "READY", { refusalReason: "OPEN_INCIDENT" })]);
    expect(rowState(refused, refused.base, [], false)).toBe("BLOCKED");
  });
  it("no readiness is unknown, and so is every row when the summary read failed", () => {
    const none = itemFor("CLS-A", "check_point", [summary("CLS-A", null)]);
    expect(rowState(none, none.base, [], false)).toBe("UNKNOWN");
    const ready = itemFor("CLS-B", "check_point", [summary("CLS-B", "READY")]);
    expect(rowState(ready, ready.base, [], true)).toBe("UNKNOWN");
  });
  it("unreadable incidents turn a would-be Ready row into Unknown, and leave blocked, refused and incident rows as they are", () => {
    const ready = itemFor("CLS-A", "check_point", [summary("CLS-A", "READY")]);
    expect(rowState(ready, ready.base, [], false, true)).toBe("UNKNOWN");
    expect(rowState(ready, ready.base, [], false, false)).toBe("CAN_FAIL_OVER");
    expect(incidentsUnreadableRow("UNKNOWN", "READY", false, true)).toBe(true);
    expect(incidentsUnreadableRow("UNKNOWN", "READY", true, true)).toBe(false);
    expect(incidentsUnreadableRow("UNKNOWN", "UNKNOWN", false, true)).toBe(false);
    const notReady = itemFor("CLS-B", "check_point", [summary("CLS-B", "NOT_READY")]);
    expect(rowState(notReady, notReady.base, [], false, true)).toBe("BLOCKED");
    const refused = itemFor("CLS-C", "check_point", [summary("CLS-C", "READY", { failoverRefusal: "VSX_MODE_UNKNOWN" })]);
    expect(rowState(refused, refused.base, [], false, true)).toBe("BLOCKED");
    expect(rowState(ready, ready.base, [incident({ cluster_member_ref: "CLS-A" })], false, true)).toBe("INCIDENT");
  });
  it("an open incident on the cluster comes first; an estate fence reaches executable vendors only", () => {
    const item = itemFor("CLS-A", "check_point", [summary("CLS-A", "READY")]);
    expect(rowState(item, item.base, [incident({ cluster_member_ref: "CLS-A" })], false)).toBe("INCIDENT");
    expect(rowState(item, item.base, [incident({ blocksEstate: true })], false)).toBe("INCIDENT");
    expect(rowState(item, item.base, [incident({})], false)).toBe("CAN_FAIL_OVER");
    const asa = itemFor("CLS-B", "cisco_asa", [{ ...summary("CLS-B", "READY"), vendor: "cisco_asa", readinessAvailable: true }]);
    expect(rowState(asa, asa.base, [incident({ blocksEstate: true })], false)).toBe("READINESS_ONLY");
  });
  it("a NOT_READY chassis is blocked by its own state and is not counted as able to fail over, whatever its virtual systems say", () => {
    const chassis = summary("CLS-V", "NOT_READY", { unitKind: "VSX_CHASSIS", chassisAction: "FAILOVER_ALL_VIRTUAL_SYSTEMS" });
    const vs = { ...summary("CLS-V", "READY"), unitId: "opaque-vs-1", virtual_system: "VS-1", unitKind: "VIRTUAL_SYSTEM" as const };
    const item = itemFor("CLS-V", "check_point", [chassis, vs]);
    expect(rowState(item, item.base, [], false)).toBe("BLOCKED");
    expect(rowState(item, vs, [], false)).toBe("CAN_FAIL_OVER");
    const ready = itemFor("CLS-R", "check_point", [summary("CLS-R", "READY")]);
    const counts = segmentCounts([{ state: rowState(item, item.base, [], false) }, { state: rowState(ready, ready.base, [], false) }]);
    expect(counts).toEqual({ can: 1, blocked: 1, unknown: 0, "readiness-only": 0, all: 2 });
  });
  it("a READY chassis with no offered action is not claimed failover-able", () => {
    const item = itemFor("CLS-V", "check_point", [summary("CLS-V", "READY", { unitKind: "VSX_CHASSIS" })]);
    expect(rowState(item, item.base, [], false)).toBe("BLOCKED");
  });
  it("maps the Overview's ?readiness= to a segment", () => {
    expect([segmentFromReadiness("NOT_READY"), segmentFromReadiness("READY"), segmentFromReadiness("UNKNOWN"), segmentFromReadiness("x")]).toEqual(["blocked", "can", "unknown", null]);
  });
  it("words the failover button for a cluster, a chassis and a virtual system", () => {
    expect(failoverButtonLabel(summary("C", "READY"), 0)).toBe("Fail over");
    expect(failoverButtonLabel({ unitId: "c", clusterId: "c", unitKind: "VSX_CHASSIS", chassisAction: "FAILOVER" }, 3)).toBe("Fail over chassis");
    expect(failoverButtonLabel({ unitId: "c", clusterId: "c", unitKind: "VSX_CHASSIS", chassisAction: "FAILOVER_ALL_VIRTUAL_SYSTEMS" }, 5)).toBe("Fail over all VS (5)");
    expect(failoverButtonLabel({ unitId: "v", clusterId: "c" }, 0)).toBe("Fail over VS");
  });
  it("rolls a chassis up by its virtual systems and never folds unknown into ready", () => {
    const vs = (n: number, status: "READY" | "NOT_READY" | null) => ({ ...summary("CLS-V", status), unitId: `vs-${n}`, unitKind: "VIRTUAL_SYSTEM" as const });
    expect(chassisRollup([summary("CLS-V", "READY"), vs(1, "READY"), vs(2, "READY"), vs(3, "NOT_READY"), vs(4, null)])).toBe("4 virtual systems: 2 ready, 1 not ready, 1 unknown");
  });
});

describe("reasonLine", () => {
  it("names the standby member and its role when only the standby fails state synchronization", () => {
    const checks = [...roles, ...both(9, "State synchronization", ["PASS", "FAIL"], ["Sync OK, 0 lost updates", "Sync status: Lost; +3 lost updates"])];
    const line = reasonLine(summary("CLS-A", "NOT_READY", {}, checks), members);
    expect(line).toMatchObject({ title: "State synchronization", headline: "Sync status: Lost", more: 0, where: { text: "on FW-ROMEO-02 (standby)", kind: "member" } });
  });
  it("says the member is not identified when both rows of a pair-level check carry the same text (PAN config sync)", () => {
    const text = "Config sync is disabled on 1 member(s): the running configurations are not kept equal";
    const checks = [...roles, ...both(4, "Configuration sync", ["FAIL", "FAIL"], text)];
    const line = reasonLine(summary("CLS-A", "NOT_READY", { vendor: "palo_alto" }, checks), members);
    expect(line.where).toEqual({ text: "member not identified by this check", kind: "unidentified" });
    // No semicolon and no " (" in this text: the cut rule leaves it whole (the server wording changes in the display-string lane).
    expect(line.headline).toBe(text);
    expect(line.headline).not.toContain("Configuration sync");
  });
  it("uses the per-member sync state of a PAN config sync row to name the member that has it off", () => {
    const rows = both(4, "Configuration sync", ["FAIL", "FAIL"], "Config sync is OFF on this member", true);
    rows[0].memberSync = "on"; rows[1].memberSync = "off";
    const line = reasonLine(summary("CLS-A", "NOT_READY", { vendor: "palo_alto" }, [...roles, ...rows]), members);
    expect(line.where).toEqual({ text: "on FW-ROMEO-02 (standby)", kind: "member" });
  });
  it("says both members when both fail with different text", () => {
    const checks = [...roles, ...both(3, "Interfaces", ["FAIL", "FAIL"], ["1 of 2 required interfaces up", "0 of 2 required interfaces up"])];
    expect(reasonLine(summary("CLS-A", "NOT_READY", {}, checks), members).where).toEqual({ text: "on both members", kind: "both" });
  });
  it("PAN session synchronization reads 'failed or disabled' without the counters", () => {
    const text = "Session synchronization failed or disabled; active sync enabled; passive sync disabled; passive session setup disabled, sent 0, recv 0";
    const checks = [...roles, ...both(5, "Session synchronization", ["FAIL", "FAIL"], text)];
    const line = reasonLine(summary("CLS-A", "NOT_READY", { vendor: "palo_alto" }, checks), members);
    expect(line.title).toBe("Session synchronization");
    expect(line.headline).toBe("failed or disabled");
    expect(JSON.stringify(line)).not.toMatch(/sent 0|recv 0/);
  });
  it("counts the other blocking checks as +N more and leads with a FAIL before an UNKNOWN", () => {
    const checks = [...roles, ...both(2, "Cluster IP table", ["UNKNOWN", "UNKNOWN"], "Not read"), ...both(3, "Interfaces", ["FAIL", "PASS"], ["0 of 2 up", "2 of 2 up"]),
      ...both(7, "Versions", ["FAIL", "FAIL"], "Differ")];
    const line = reasonLine(summary("CLS-A", "NOT_READY", {}, checks), members);
    expect(line.title).toBe("Interfaces");
    expect(line.more).toBe(2);
  });
  it("rewrites ISO instants into display time and never repeats the check title", () => {
    const { headline, detail } = splitSummary("State synchronization", "State synchronization: +1 lost updates since 2026-11-23T03:00:24.520360Z; baseline 2026-11-22T03:00:00Z");
    expect(headline).toBe("+1 lost updates since 2026-11-23 06:00 GMT+3");
    expect(detail).toBe("baseline 2026-11-22 06:00 GMT+3");
    expect(splitSummary("Interfaces", "2 of 2 required interfaces up").headline).toBe("2 of 2 required interfaces up");
  });
  it("cuts at the first parenthesis when there is no semicolon, and rewrites the known Connections text", () => {
    expect(splitSummary("ARP", "123 ARP entries (source: x)")).toEqual({ headline: "123 ARP entries", detail: "(source: x)" });
    expect(splitSummary("Connections", "Count unavailable, Count unavailable, below tolerance").headline).toBe("Not evaluated - session continuity is not measured");
  });
  it("a READY row says how many blocking checks passed and lists its first warning", () => {
    const checks = [...roles, ...both(2, "Cluster IP table", ["PASS", "PASS"], "ok"), ...both(8, "Last failover less than 6 hours ago", ["WARN", "WARN"], "5 h ago", false)];
    expect(reasonLine(summary("CLS-A", "READY", {}, checks), members)).toEqual({ headline: "2 of 2 blocking checks passed", more: 0, warning: "Last failover less than 6 hours ago" });
  });
  it("keeps the server's refusal code beside its label", () => {
    const line = reasonLine(summary("CLS-A", "READY", { refusalReason: "OPEN_INCIDENT" }, roles), members);
    expect(line.code).toBe("OPEN_INCIDENT");
    expect(line.headline.length).toBeGreaterThan(0);
    const vsx = reasonLine(summary("CLS-V", "READY", { failoverRefusal: "VIRTUAL_SYSTEM_NOT_READY" }, roles), members);
    expect(vsx.code).toBe("VIRTUAL_SYSTEM_NOT_READY");
  });
  it("Never checked, and the stop reason of a pre-check run that could not finish", () => {
    expect(reasonLine(summary("CLS-A", null), members).headline).toBe("Never checked");
    expect(reasonLine(undefined, members).headline).toBe("Never checked");
    const stopped = reasonLine(summary("CLS-A", "UNKNOWN", { readiness: { status: "UNKNOWN", observedAt: AT, failedCheck: "", stopCode: "COMMAND_UNAVAILABLE", checks: [] } }), members);
    expect(stopped.headline).toMatch(/^Pre-checks could not finish: /);
    expect(stopped.code).toBe("COMMAND_UNAVAILABLE");
  });
});

describe("groupChecks", () => {
  const thirteen = () => {
    const rows: ReadinessCheck[] = [];
    for (let no = 1; no <= 13; no++) rows.push(...both(no, `Check ${no}`, ["PASS", "PASS"], `ok ${no}`, true, no === 1 ? { derived: { role: "ACTIVE" } } : {}));
    const set = (no: number, results: [Result, Result], blocking = true) => {
      for (let i = rows.length - 1; i >= 0; i--) if (rows[i].checkNo === no) rows.splice(i, 1);
      rows.push(...both(no, `Check ${no}`, results, ["one", "two"], blocking));
    };
    set(9, ["PASS", "FAIL"]);
    set(6, ["NOT_EVALUATED", "NOT_EVALUATED"]);
    set(10, ["PASS", "PASS"], false);
    set(11, ["PASS", "WARN"], false);
    set(12, ["UNKNOWN", "PASS"]);
    return rows;
  };
  it("puts every one of 13 checks in exactly one group", () => {
    const groups = groupChecks(thirteen(), members, "check_point", AT);
    const sizes = Object.values(groups).map(g => g.length);
    expect(sizes.reduce((a, b) => a + b, 0)).toBe(13);
    const all = Object.values(groups).flat().map(e => e.no).sort((a, b) => a - b);
    expect(all).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13]);
    expect(groups.blocking.map(e => e.no)).toEqual([9]);
    expect(groups.warnings.map(e => e.no)).toEqual([11]);
    expect(groups.info.map(e => e.no)).toEqual([10]);
  });
  it("a blocking NOT_EVALUATED (or UNKNOWN) check is in unknown, never in passed or information", () => {
    const groups = groupChecks(thirteen(), members, "check_point", AT);
    expect(groups.unknown.map(e => e.no)).toEqual([6, 12]);
    expect(groups.passed.map(e => e.no)).not.toContain(6);
    expect(groups.info.map(e => e.no)).not.toContain(6);
  });
  it("a blocking check with WARN on one member and UNKNOWN on the other is unknown, never a warning", () => {
    const rows: ReadinessCheck[] = [];
    for (let no = 1; no <= 3; no++) rows.push(...both(no, `Check ${no}`, ["PASS", "PASS"], `ok ${no}`, true, no === 1 ? { derived: { role: "ACTIVE" } } : {}));
    const set = (no: number, results: [Result, Result], blocking: boolean) => {
      for (let i = rows.length - 1; i >= 0; i--) if (rows[i].checkNo === no) rows.splice(i, 1);
      rows.push(...both(no, `Check ${no}`, results, ["one", "two"], blocking));
    };
    set(2, ["WARN", "UNKNOWN"], true);
    set(3, ["WARN", "PASS"], true);
    const groups = groupChecks(rows, members, "check_point", AT);
    expect(groups.unknown.map(e => e.no)).toEqual([2]);
    expect(groups.warnings.map(e => e.no)).toEqual([3]);
    expect(Object.values(groups).flat()).toHaveLength(3);
    set(2, ["NOT_EVALUATED", "WARN"], true);
    expect(groupChecks(rows, members, "check_point", AT).unknown.map(e => e.no)).toEqual([2]);
  });
  it("only the failing member's cell is marked failed; identical texts merge into one cell", () => {
    const groups = groupChecks(thirteen(), members, "check_point", AT);
    const blocker = groups.blocking[0];
    expect(blocker.same).toBe(false);
    expect(blocker.cells.map(c => c.failed)).toEqual([false, true]);
    expect(blocker.cells.map(c => c.memberLabel)).toEqual(["FW-ROMEO-01", "FW-ROMEO-02"]);
    expect(blocker.cells[0].role).toBe("active");
    expect(groups.passed.every(e => e.same)).toBe(true);
  });
});
