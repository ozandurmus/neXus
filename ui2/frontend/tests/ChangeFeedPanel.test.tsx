import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { ChangeFeedPanel, coverageTone, coverageWord, kindTone, rangeToQuery } from "../src/screens/ChangeFeedPanel";
import type { ChangeFeedPage } from "../src/auth/adminApi";

/** Synthetic, masked-shaped data: pseudonyms, USR- tokens, RFC 5737 addresses; nothing from a real estate. */
const page: ChangeFeedPage = {
  events: [
    { key: "crash:a1", kind: "DEVICE_EVENT", source: "device_crash_alert", time: "2026-10-17T03:10:00Z", time_basis: "UTC", device_time: null,
      vendor: "check_point", device_id: "d-cp", device_name: "FW-TANGO-04", actor: "device", what: "new crash or dump file · process UNKNOWN · 1",
      detail: "newest file mtime (device epoch) 1760677200", tone: "BAD", diff: [],
      links: { device: "?screen=inventory&device_id=d-cp&tab=logs", policy_id: null, rule_id: null, job_id: "j-log", job_type: null } },
    { key: "pan_config:c:1", kind: "USER", source: "pan_config", time: "2026-10-16T23:14:09Z", time_basis: "DEVICE_LOCAL", device_time: "2026/10/17 02:14:09",
      vendor: "palo_alto", device_id: "d-pan", device_name: "FW-JULIET-06", actor: "USR-0123456789", what: "edit vsys vsys1 rulebase security rules app-backup-allow",
      detail: "result Submitted · client Web · seqno 41", tone: "NEUTRAL",
      diff: [{ op: "-", text: "<action>deny</action>" }, { op: "+", text: "<action>allow</action>" }],
      links: { device: "?screen=inventory&device_id=d-pan&tab=logs", policy_id: null, rule_id: null, job_id: null, job_type: null } },
    { key: "jobs:cp_system_log_collect:1760659200", kind: "NEXUS_JOB", source: "jobs", time: "2026-10-16T21:00:00Z", time_basis: "UTC", device_time: null,
      vendor: "check_point", device_id: null, device_name: null, actor: "neXus", what: "47 jobs · 47 devices · 47 completed", detail: "job type cp_system_log_collect",
      tone: "OK", diff: [], links: { device: null, policy_id: null, rule_id: null, job_id: null, job_type: "cp_system_log_collect" } },
    { key: "policy:3", kind: "USER", source: "policy_rule_history", time: "2026-10-16T15:30:00Z", time_basis: "UTC", device_time: null,
      vendor: "check_point", device_id: "d-cp", device_name: "FW-TANGO-04", actor: "USR-abcdef0123", what: "modified rule 7 · RULE-BRAVO-02 · policy POL-ALPHA-01",
      detail: null, tone: "NEUTRAL", diff: [{ op: "-", text: "action: \"allow\"" }, { op: "+", text: "action: \"deny\"" }],
      links: { device: "?screen=inventory&device_id=d-cp&tab=logs", policy_id: "p1", rule_id: "r1", job_id: null, job_type: null } },
  ],
  next_cursor: "synthetic_cursor", range_start: "2026-10-16T09:00:00Z", range_end: "2026-10-17T09:00:00Z",
  kinds: ["USER", "NEXUS_JOB", "DEVICE_EVENT"], vendor: null, device_id: null, masked: true,
  ordering_note: "Device-clock times (DEVICE_LOCAL) are shown as the device wrote them and ordered as Europe/Istanbul; no device prints a zone.",
  coverage: [
    { source: "policy_rule_history", kind: "USER", state: "OK", note: "stored rule revisions", events: 1 },
    { source: "pan_config", kind: "USER", state: "PARTIAL", note: "2 chunks read, 1 unreadable", events: 1 },
    { source: "policy_change_evidence", kind: "USER", state: "OFF", note: "Check Point show-changes: no evidence stored (the V163 reader is off by default)", events: 0 },
    { source: "cp_cpwd_elg", kind: "DEVICE_EVENT", state: "NOT_PERMITTED", note: "needs system_log_read", events: 0 },
    { source: "jobs", kind: "NEXUS_JOB", state: "UNAVAILABLE", note: "read failed: IllegalStateException", events: 0 },
  ],
};

function mount() {
  return render(<ThemeProvider theme={m3Theme}><ChangeFeedPanel /></ThemeProvider>);
}
const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
function stub(feed: (url: string) => Response) {
  const calls: string[] = [];
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    calls.push(url);
    if (url === "/devices") return response({ devices: [{ device_id: "d-cp", vendor_hint: "check_point", enrollment_state: "ENROLLED", hostname: "FW-TANGO-04", model: null, software_version: null, ha_role: null, cluster_member_ref: null }] });
    return feed(url);
  }));
  return calls;
}
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

describe("ChangeFeedPanel", () => {
  it("renders the timeline newest first with who · device · what, kind pills, device-clock times and deep links", async () => {
    stub(() => response(page));
    mount();
    expect(screen.getByText("Loading changes…")).toBeInTheDocument();
    const list = await screen.findByRole("list", { name: "Change timeline" });
    const items = within(list).getAllByRole("listitem");
    expect(items.map((li) => li.getAttribute("data-source"))).toEqual(["device_crash_alert", "pan_config", "jobs", "policy_rule_history"]);
    // who · device · what, the device a link to its Logs tab
    expect(within(items[1]).getByText("USR-0123456789")).toBeInTheDocument();
    expect(within(items[1]).getByRole("link", { name: "FW-JULIET-06" })).toHaveAttribute("href", "?screen=inventory&device_id=d-pan&tab=logs");
    expect(within(items[1]).getByText(/edit vsys vsys1 rulebase/)).toBeInTheDocument();
    // a device-clock time is the device's own text, never converted, and says so
    expect(within(items[1]).getByText("2026/10/17 02:14:09")).toBeInTheDocument();
    expect(within(items[1]).getByText("device clock")).toBeInTheDocument();
    // a UTC instant is the product's one stamp in GMT+3
    expect(within(items[0]).getByText("2026-10-17 06:10:00 GMT+3")).toBeInTheDocument();
    // kind pills from the role table: user = brand, neXus job = neutral/ok, device event = its state
    expect(within(items[1]).getByText("User").closest("[data-pill]")).toHaveAttribute("data-pill", "brand");
    expect(within(items[0]).getByText("Device event").closest("[data-pill]")).toHaveAttribute("data-pill", "bad");
    expect(within(items[2]).getByText("neXus job").closest("[data-pill]")).toHaveAttribute("data-pill", "neutral");
    // the folded jobs line names the job type in words and links to the jobs of that type
    expect(within(items[2]).getByText(/Check Point log collection · 47 jobs/)).toBeInTheDocument();
    expect(within(items[2]).getByRole("link", { name: "Jobs of this type" })).toHaveAttribute("href", "?screen=operations&tab=jobs&job_type=cp_system_log_collect");
    expect(within(items[0]).getByRole("link", { name: "Job" })).toHaveAttribute("href", "?screen=operations&tab=jobs&job=j-log");
    expect(within(items[3]).getByRole("link", { name: "Policy history" })).toHaveAttribute("href", "?screen=policy&policy_id=p1");
    expect(screen.getByText(/2026-10-16 12:00 GMT\+3 — 2026-10-17 12:00 GMT\+3 · page 1 · masked view/)).toBeInTheDocument();
  });

  it("keeps the diff collapsed in mono with added and removed lines coloured apart", async () => {
    stub(() => response(page));
    mount();
    const list = await screen.findByRole("list", { name: "Change timeline" });
    const policyItem = within(list).getAllByRole("listitem")[3];
    const details = policyItem.querySelector("details") as HTMLDetailsElement;
    expect(details.open).toBe(false);
    expect(within(policyItem).getByText("Diff · 1 added · 1 removed")).toBeInTheDocument();
    fireEvent.click(within(policyItem).getByText("Diff · 1 added · 1 removed"));
    const removed = within(policyItem).getByText('- action: "allow"');
    const added = within(policyItem).getByText('+ action: "deny"');
    expect(removed).toHaveAttribute("data-op", "-");
    expect(added).toHaveAttribute("data-op", "+");
    expect(getComputedStyle(removed).color).not.toEqual(getComputedStyle(added).color);
  });

  it("names every source and its state in a live coverage row, with the note on hover, and the ordering note", async () => {
    stub(() => response(page));
    mount();
    const coverage = await screen.findByRole("status", { name: "Coverage" });
    const pill = (source: string) => coverage.querySelector(`[data-fact="coverage:${source}"]`) as HTMLElement;
    expect(pill("policy_rule_history")).toHaveAttribute("data-pill", "ok");
    expect(pill("policy_rule_history").textContent).toBe("Read · 1 · Policy rule history");
    expect(pill("pan_config")).toHaveAttribute("data-pill", "warn");
    expect(pill("policy_change_evidence")).toHaveAttribute("data-pill", "neutral");
    expect(pill("policy_change_evidence").textContent).toBe("Reader off · Policy change evidence");
    expect(pill("cp_cpwd_elg").textContent).toBe("Not permitted · Check Point cpwd");
    expect(pill("jobs")).toHaveAttribute("data-pill", "unknown");
    expect(pill("jobs").textContent).toBe("UNKNOWN · neXus jobs");
    expect(within(coverage).getByText(/ordered as Europe\/Istanbul/)).toBeInTheDocument();
  });

  it("sends the filters it shows: range preset, kinds, vendor, device, and pages with the server cursor", async () => {
    const calls = stub(() => response(page));
    mount();
    await screen.findByRole("list", { name: "Change timeline" });
    const first = calls.find((u) => u.startsWith("/api/v2/changes/feed"))!;
    expect(first).toContain("kinds=USER%2CNEXUS_JOB%2CDEVICE_EVENT");
    expect(first).toContain("limit=50");
    expect(first).toMatch(/from=\d{4}-/);
    fireEvent.click(screen.getByRole("button", { name: "7 d" }));
    fireEvent.click(screen.getByRole("button", { name: "neXus job" })); // off
    fireEvent.mouseDown(screen.getByLabelText("Vendor"));
    fireEvent.click(await screen.findByRole("option", { name: "Palo Alto" }));
    fireEvent.click(screen.getByRole("button", { name: "Apply feed filters" }));
    await waitFor(() => expect(calls.filter((u) => u.startsWith("/api/v2/changes/feed")).length).toBe(2));
    const second = calls.filter((u) => u.startsWith("/api/v2/changes/feed"))[1];
    expect(second).toContain("kinds=USER%2CDEVICE_EVENT");
    expect(second).toContain("vendor=palo_alto");
    expect(second).not.toContain("cursor=");
    fireEvent.click(screen.getByRole("button", { name: "Older changes" }));
    await waitFor(() => expect(calls.filter((u) => u.startsWith("/api/v2/changes/feed")).length).toBe(3));
    const third = calls.filter((u) => u.startsWith("/api/v2/changes/feed"))[2];
    expect(third).toContain("cursor=synthetic_cursor");
    // the pages of one read share one window: the second page carries the range the server answered
    expect(third).toContain("from=2026-10-16T09%3A00%3A00Z");
    expect(third).toContain("to=2026-10-17T09%3A00%3A00Z");
    expect(await screen.findByText(/page 2/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Newer changes" })).toBeEnabled();
  });

  it("shows the empty state as a state panel, never zero events as silence", async () => {
    stub(() => response({ ...page, events: [], next_cursor: "" }));
    mount();
    expect(await screen.findByText("No stored change in this range")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Older changes" })).toBeDisabled();
  });

  it("says a refusal is a restriction and a failure is an error with Retry, both as status notices", async () => {
    stub(() => response({ error: "ACTION_REFUSED" }, 403));
    mount();
    const restricted = await screen.findByText(/Change feed/, { selector: "p" });
    expect(restricted.closest("[data-state]")).toHaveAttribute("data-state", "restricted");
    expect(screen.queryByRole("alert")).toBeNull();
    vi.unstubAllGlobals();
    let failures = 0;
    stub(() => { failures++; return failures === 1 ? response({ error: "boom", code: "FEED_DOWN" }, 500) : response(page); });
    mount();
    const error = await screen.findByText("Could not load the change feed");
    expect(error.closest("[data-state]")).toHaveAttribute("data-state", "error");
    expect(error.closest("[role]")).toHaveAttribute("role", "status");
    expect(screen.getByText("FEED_DOWN")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByRole("list", { name: "Change timeline" })).toBeInTheDocument();
  });
});

describe("change feed helpers", () => {
  it("maps kinds and coverage states onto the role table", () => {
    expect(kindTone({ kind: "USER", tone: "NEUTRAL" })).toBe("brand");
    expect(kindTone({ kind: "USER", tone: "BAD" })).toBe("bad");
    expect(kindTone({ kind: "NEXUS_JOB", tone: "OK" })).toBe("neutral");
    expect(kindTone({ kind: "NEXUS_JOB", tone: "WARN" })).toBe("warn");
    expect(kindTone({ kind: "DEVICE_EVENT", tone: "UNKNOWN" })).toBe("unknown");
    expect(coverageTone("OK")).toBe("ok");
    expect(coverageTone("UNAVAILABLE")).toBe("unknown");
    expect(coverageWord("UNAVAILABLE")).toBe("UNKNOWN");
    expect(coverageWord("OFF")).toBe("Reader off");
  });
  it("turns a preset into the instants the server expects and leaves a custom range to its fields", () => {
    const now = new Date("2026-10-17T09:00:00Z");
    expect(rangeToQuery("24h", { from: "", to: "" }, now)).toEqual({ from: "2026-10-16T09:00:00.000Z", to: "2026-10-17T09:00:00.000Z" });
    expect(rangeToQuery("7d", { from: "", to: "" }, now)).toEqual({ from: "2026-10-10T09:00:00.000Z", to: "2026-10-17T09:00:00.000Z" });
    expect(rangeToQuery("custom", { from: "2026-10-01T00:00:00.000Z", to: "" }, now)).toEqual({ from: "2026-10-01T00:00:00.000Z", to: undefined });
  });
});
