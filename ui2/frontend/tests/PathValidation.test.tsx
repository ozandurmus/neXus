import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { PathAnalysisScreen } from "../src/screens/PathAnalysisScreen";
import { PathResultView } from "../src/screens/pathanalysis/PathResultView";
import { pollDelay, type PathVerification, type ValidationState } from "../src/screens/pathanalysis/usePathValidation";
import {
  UDP_NOTICE, VERIFY_EXPLANATION, classifyValidationFailure, countValidation, explainValidation, hopValidation, requestFromResult, summarise,
  windowTime,
} from "../src/screens/pathanalysis/pathValidationWords";
import { PATH_ALLOWED, PATH_PARTIAL, PATH_VALIDATION_COMPLETE, PATH_VALIDATION_PENDING, bravoHop, tangoHop } from "../src/preview/pathPreviewData";
import * as api from "../src/auth/adminApi";
import type { ApiError, PathResult, PathValidation, PathValidationHop } from "../src/auth/adminApi";

vi.mock("../src/auth/adminApi", async (importOriginal) => ({
  ...await importOriginal<typeof import("../src/auth/adminApi")>(),
  queryPath: vi.fn(),
  validatePath: vi.fn(),
  getPathValidation: vi.fn(),
  responsesAreMasked: vi.fn(() => false),
}));

const queryPath = vi.mocked(api.queryPath);
const validatePath = vi.mocked(api.validatePath);
const getPathValidation = vi.mocked(api.getPathValidation);

const NOW = new Date("2026-11-04T08:30:00Z");
const refusal = (status: number, body: Record<string, unknown>): ApiError => ({ status, body });
const noop = () => undefined;

function verification(state: Partial<ValidationState>, start: PathVerification["start"] = noop, checkAgain = noop): PathVerification {
  return {
    state: { phase: "idle", branchId: undefined, validationId: null, validation: null, failure: null, polls: 0, lastPolledAt: null, ...state },
    start, checkAgain, reset: noop,
  };
}
function complete(validation: PathValidation, branchId?: string): PathVerification {
  return verification({ phase: "complete", branchId, validationId: validation.validation_id, validation, polls: 2, lastPolledAt: "2026-11-04T08:06:00Z" });
}
function failed(error: ApiError, afterSubmit = false): PathVerification {
  return verification({ phase: "failed", failure: classifyValidationFailure(error), validationId: afterSubmit ? "pv-x" : null,
    validation: afterSubmit ? PATH_VALIDATION_PENDING : null });
}
function renderResult(result: PathResult, v?: PathVerification) {
  return render(<ThemeProvider theme={m3Theme}><PathResultView result={result} verification={v} now={NOW} /></ThemeProvider>);
}
function renderScreen() {
  return render(<ThemeProvider theme={m3Theme}><PathAnalysisScreen /></ThemeProvider>);
}
function notEvaluable(hop: PathValidationHop, reasons: string[], checks: PathValidationHop["checks"] = []): PathValidationHop {
  return { ...hop, result: "NOT_EVALUABLE", checks, reasons, job_id: checks.length ? hop.job_id : null, job_state: checks.length ? "COMPLETED" : null };
}
const flush = () => act(async () => { await Promise.resolve(); await Promise.resolve(); });

beforeEach(() => {
  queryPath.mockReset(); validatePath.mockReset(); getPathValidation.mockReset();
});
afterEach(() => { vi.useRealTimers(); });

describe("the words of a verification", () => {
  it("summarises hop counts honestly: confirmed, disagreeing, not evaluable, beyond the cap, pending", () => {
    expect(summarise(PATH_VALIDATION_COMPLETE)).toBe("1 of 2 hops confirmed by the devices; 1 disagrees with the computed path.");
    const [t, b] = PATH_VALIDATION_COMPLETE.hops;
    const four: PathValidation = { ...PATH_VALIDATION_COMPLETE, result: "MATCH", hops_not_validated: 2,
      hops: [t, { ...b, result: "MATCH", reasons: [] }, { ...t, ordinal: 3, hop_index: 3 }, notEvaluable({ ...b, ordinal: 4, hop_index: 4 }, ["VSYS_TARGET_NOT_APPROVED"])] };
    expect(summarise(four)).toBe("3 of 4 hops confirmed by the devices; 1 could not be evaluated (no device was asked). 2 hops beyond the 4-hop cap of one request were not asked.");
    expect(summarise(PATH_VALIDATION_PENDING)).toBe("Waiting for the devices: 1 of 2 hops answered.");
    expect(summarise({ ...PATH_VALIDATION_COMPLETE, result: "NOT_EVALUABLE", hops: [], hops_not_validated: 1 })).toBe("No unit hop of this branch could be asked. 1 hop beyond the 4-hop cap of one request was not asked.");
    expect(countValidation(four)).toEqual({ total: 4, match: 3, mismatch: 0, notEvaluable: 1, pending: 0, notContacted: 1, beyondCap: 2 });
  });

  it("classifies every refusal by status and code, keeping the server's window sentence", () => {
    expect(classifyValidationFailure(refusal(403, { code: "ACTION_REFUSED" })).kind).toBe("restricted");
    const window = classifyValidationFailure(refusal(409, { code: "OUTSIDE_JOB_WINDOW", message: "Device jobs run at 00, 06, 12 and 18.", next_window_start: "2026-11-04T09:00:00Z" }));
    expect(window.kind).toBe("window");
    expect(window.message).toBe("Device jobs run at 00, 06, 12 and 18.");
    expect(windowTime(window.nextWindowStart)).toBe("12:00");
    expect(windowTime("2026-11-04T09:00+03:00[Europe/Istanbul]")).toBe("09:00");
    expect(windowTime(null)).toBeNull();
    expect(classifyValidationFailure(refusal(409, { code: "BUSINESS_HOURS_CONFIRM_REQUIRED" })).kind).toBe("not_confirmed");
    expect(classifyValidationFailure(refusal(409, { code: "TOPOLOGY_NOT_BUILT" })).kind).toBe("not_built");
    expect(classifyValidationFailure(refusal(422, { code: "PSEUDONYM_REFUSED" })).kind).toBe("refused");
    expect(classifyValidationFailure(refusal(404, { code: "BRANCH_NOT_FOUND" })).kind).toBe("branch_gone");
    expect(classifyValidationFailure(refusal(404, { code: "NOT_FOUND" })).kind).toBe("record_gone");
    expect(classifyValidationFailure(refusal(429, {})).kind).toBe("rate_limited");
    expect(classifyValidationFailure(refusal(429, {})).code).toBe("PATH_VALIDATION_RATE_LIMIT");
    expect(classifyValidationFailure(refusal(503, { error: "POLICY_BUSY" })).kind).toBe("busy");
    expect(classifyValidationFailure(refusal(503, { code: "AUDIT_UNAVAILABLE" })).kind).toBe("audit");
    expect(classifyValidationFailure(refusal(400, {})).kind).toBe("invalid");
    expect(classifyValidationFailure(refusal(0, { error: "TIMEOUT" })).kind).toBe("offline");
    expect(classifyValidationFailure(refusal(500, {})).kind).toBe("error");
    expect(classifyValidationFailure(undefined).kind).toBe("error");
  });

  it("maps the record onto the hops: validated, beyond the cap, nothing for another branch or a cloud node", () => {
    const [b1, b2] = PATH_PARTIAL.branches;
    const v: PathValidation = { ...PATH_VALIDATION_COMPLETE, hops_not_validated: 1, hops: [{ ...PATH_VALIDATION_COMPLETE.hops[0] }] };
    expect(hopValidation(v, "b1", b1, b1.hops[0])).toEqual({ kind: "validated", hop: v.hops[0] });
    expect(hopValidation(v, "b1", b1, b1.hops[1])).toEqual({ kind: "beyond_cap" });
    expect(hopValidation(v, "b1", b2, b2.hops[0])).toEqual({ kind: "none" });
    expect(hopValidation(v, "b1", b1, { ...b1.hops[2], node_kind: "CLOUD" })).toEqual({ kind: "none" });
    expect(hopValidation({ ...v, hops_not_validated: 0 }, "b1", b1, b1.hops[1])).toEqual({ kind: "none" });
    expect(hopValidation(null, "b1", b1, b1.hops[0])).toEqual({ kind: "none" });
  });

  it("sends the result's own query back, only the fields that are set", () => {
    expect(requestFromResult(PATH_ALLOWED)).toEqual({ src: "198.51.100.20", dst: "203.0.113.200", protocol: "tcp", port: 443 });
    expect(requestFromResult({ ...PATH_ALLOWED, query: { ...PATH_ALLOWED.query, protocol: "udp", port: 53, ingress_node_id: "FW-TANGO-04/physical/main", ingress_interface_name: "eth1", edge_mode: true } }))
      .toEqual({ src: "198.51.100.20", dst: "203.0.113.200", protocol: "udp", port: 53, ingress_node_id: "FW-TANGO-04/physical/main", ingress_interface_name: "eth1", edge_mode: true });
  });

  it("explains the reason vocabulary in plain words and keeps an unknown code as it is", () => {
    expect(explainValidation("PROTOCOL_NOT_APPROVED")).toContain("TCP only");
    expect(explainValidation("VSYS_TARGET_NOT_APPROVED")).toContain("vsys1");
    expect(explainValidation("ECMP_DEVICE_CHOICE")).toContain("not a mismatch");
    expect(explainValidation("NEXT_HOP_DIFFERS")).toBe("Same interface, different next hop.");
    expect(explainValidation("SOME_FUTURE_CODE")).toBe("SOME_FUTURE_CODE");
    expect(pollDelay(0)).toBe(1_000); expect(pollDelay(3)).toBe(8_000); expect(pollDelay(9)).toBe(10_000);
  });
});

describe("the action and the finished states on the result view", () => {
  it("offers one action for a single branch with the one-line explanation, nothing on the hops yet", () => {
    const start = vi.fn();
    renderResult(PATH_ALLOWED, verification({}, start));
    const panel = screen.getByTestId("path-verify");
    expect(panel).toHaveAttribute("data-phase", "idle");
    expect(within(panel).getByTestId("path-verify-explanation")).toHaveTextContent(VERIFY_EXPLANATION);
    expect(VERIFY_EXPLANATION).toContain("read-only matcher");
    expect(VERIFY_EXPLANATION).toContain("never the device's answer");
    expect(panel).toHaveTextContent("first 4 unit hops");
    expect(within(panel).queryByTestId("path-verify-udp")).toBeNull();
    fireEvent.click(within(panel).getByRole("button", { name: "Verify on devices" }));
    expect(start).toHaveBeenCalledWith(undefined);
    expect(screen.queryAllByTestId(/hop-\d-device/)).toHaveLength(0);
  });

  it("offers one action per branch when the route splits, and warns about UDP before anything is sent", () => {
    const start = vi.fn();
    renderResult({ ...PATH_PARTIAL, query: { ...PATH_PARTIAL.query, protocol: "udp", port: 53 } }, verification({}, start));
    const panel = screen.getByTestId("path-verify");
    expect(within(panel).getByTestId("path-verify-udp")).toHaveTextContent(UDP_NOTICE);
    fireEvent.click(within(panel).getByRole("button", { name: "Verify branch b2 on devices" }));
    expect(start).toHaveBeenCalledWith("b2");
    expect(within(panel).getByRole("button", { name: "Verify branch b1 on devices" })).toBeInTheDocument();
  });

  it("renders a nothing-to-verify preview without a verification prop", () => {
    renderResult(PATH_ALLOWED);
    expect(screen.queryByTestId("path-verify")).toBeNull();
  });

  it("complete with a mismatch: the summary, MATCH and MISMATCH badges beside the neXus verdicts, each check in plain words, no device output", () => {
    renderResult(PATH_ALLOWED, complete(PATH_VALIDATION_COMPLETE));
    const panel = screen.getByTestId("path-verify");
    expect(panel).toHaveAttribute("data-phase", "complete");
    expect(within(panel).getByText("A device disagrees")).toBeInTheDocument();
    const summary = within(panel).getByTestId("path-verify-summary");
    expect(summary).toHaveAttribute("data-result", "MISMATCH");
    expect(summary).toHaveTextContent("1 of 2 hops confirmed by the devices; 1 disagrees with the computed path.");
    expect(summary).toHaveTextContent("NOT_EVALUABLE is never a mismatch");
    expect(summary).toHaveTextContent("pv-00000000-0000-4000-8000-000000000001");
    expect(within(panel).getByRole("button", { name: "Verify again" })).toBeInTheDocument();

    const hop1 = screen.getByTestId("path-hop-1");
    expect(hop1).toHaveAttribute("data-device", "MATCH");
    // the device's word sits beside the neXus verdict pill in the hop header
    const header = hop1.querySelector('[data-fact="hop-verdict"]')!.parentElement!;
    expect(header).toHaveTextContent("Allowed");
    expect(within(header).getByText("Device MATCH")).toHaveAttribute("data-pill", "ok");
    const check1 = within(hop1).getByTestId("hop-1-device");
    expect(check1).toHaveTextContent("Route (Check Point): MATCH");
    expect(check1).toHaveTextContent("Same egress interface and next hop.");
    expect(check1).toHaveTextContent("SAME_INTERFACE_AND_NEXT_HOP");

    const hop2 = screen.getByTestId("path-hop-2");
    expect(hop2).toHaveAttribute("data-device", "MISMATCH");
    expect(within(hop2).getByText("Device MISMATCH")).toBeInTheDocument();
    const check2 = within(hop2).getByTestId("hop-2-device");
    expect(check2).toHaveTextContent("Route (FIB): MATCH");
    expect(check2).toHaveTextContent("Security rule: MATCH");
    expect(check2).toHaveTextContent("NAT rule: MISMATCH");
    expect(check2).toHaveTextContent("The device's NAT rule differs from the computed one.");
    // the record carries states and codes only; nothing that looks like a device answer or a value from it
    for (const raw of ["ip -4 route get", "<response", "fib-lookup", "nexthop", "192.0.2.2 dev"]) expect(check2.textContent).not.toContain(raw);
  });

  it("NOT_EVALUABLE states in plain words: UDP not approved, a vsys not validated, the ECMP device choice, admission refused, and no device contacted", () => {
    const [t, b] = PATH_VALIDATION_COMPLETE.hops;
    const udp: PathValidation = { ...PATH_VALIDATION_COMPLETE, result: "MATCH", hops: [t, notEvaluable(b, ["PROTOCOL_NOT_APPROVED"], [{ check: "PAN_FIB", result: "MATCH", reason: "CONNECTED_SAME_INTERFACE" }])] };
    // a hop whose route matched is MATCH on the server; this fixture keeps the hop NOT_EVALUABLE to show the words only
    const view = renderResult({ ...PATH_ALLOWED, query: { ...PATH_ALLOWED.query, protocol: "udp", port: 53 } }, complete(udp));
    let hop2 = screen.getByTestId("path-hop-2");
    expect(hop2).toHaveAttribute("data-device", "NOT_EVALUABLE");
    expect(within(hop2).getByText("Device NOT_EVALUABLE")).toBeInTheDocument();
    expect(within(hop2).getByTestId("hop-2-device")).toHaveTextContent("UDP: the approved policy-match command is TCP only");
    expect(screen.getByTestId("path-verify-udp")).toBeInTheDocument();
    view.unmount();

    const vsys: PathValidation = { ...PATH_VALIDATION_COMPLETE, result: "MATCH", hops: [t, notEvaluable(b, ["VSYS_TARGET_NOT_APPROVED"])] };
    const vsysView = renderResult(PATH_ALLOWED, complete(vsys));
    expect(screen.getByTestId("path-verify-summary")).toHaveTextContent("1 of 2 hops confirmed by the devices; 1 could not be evaluated (no device was asked).");
    expect(within(screen.getByTestId("path-hop-2")).getByTestId("hop-2-device")).toHaveTextContent("Not asked: Palo Alto: only a firewall's own vsys1 is an approved target; this vsys was not validated.");
    vsysView.unmount();

    const ecmp: PathValidation = { ...PATH_VALIDATION_COMPLETE, result: "MATCH", hops: [notEvaluable(t, ["ECMP_DEVICE_CHOICE"], [{ check: "CP_ROUTE", result: "NOT_EVALUABLE", reason: "ECMP_DEVICE_CHOICE" }]), { ...b, result: "MATCH", reasons: [] }] };
    const ecmpView = renderResult(PATH_ALLOWED, complete(ecmp));
    expect(within(screen.getByTestId("path-hop-1")).getByTestId("hop-1-device")).toHaveTextContent("Route (Check Point): NOT_EVALUABLE Equal-cost routes: the device shows its one hashed choice, so a difference from this branch is expected, not a mismatch.");
    expect(screen.getByTestId("path-verify-summary")).toHaveTextContent("1 of 2 hops confirmed by the devices; 1 could not be evaluated.");
    expect(screen.queryByTestId("path-verify-none-contacted")).toBeNull();
    ecmpView.unmount();

    const refused: PathValidation = { ...PATH_VALIDATION_COMPLETE, result: "NOT_EVALUABLE", hops: [notEvaluable(t, ["CAPABILITY_NOT_EXECUTION_ELIGIBLE"]), notEvaluable(b, ["ADMISSION_REFUSED"])] };
    renderResult(PATH_ALLOWED, complete(refused));
    expect(screen.getByText("Not evaluable")).toBeInTheDocument();
    expect(screen.getByTestId("path-verify-summary")).toHaveTextContent("0 of 2 hops confirmed by the devices; 2 could not be evaluated (no device was asked).");
    expect(screen.getByTestId("path-verify-none-contacted")).toHaveTextContent("No device was contacted");
    expect(within(screen.getByTestId("path-hop-1")).getByTestId("hop-1-device")).toHaveTextContent("Path validation is not an eligible capability for this device; nothing was sent.");
    expect(within(screen.getByTestId("path-hop-2")).getByTestId("hop-2-device")).toHaveTextContent("The device job was not admitted; nothing was sent.");
    hop2 = screen.getByTestId("path-hop-2");
    expect(hop2).toHaveAttribute("data-device", "NOT_EVALUABLE");
  });

  it("hops beyond the 4-hop cap are marked not asked, never as a device verdict; another branch's hops carry nothing", () => {
    const six: PathResult = { ...PATH_PARTIAL, branches: [
      { ...PATH_PARTIAL.branches[0], hops: [PATH_PARTIAL.branches[0].hops[0], PATH_PARTIAL.branches[0].hops[1], { ...tangoHop(), hop_index: 3 }, { ...bravoHop(), hop_index: 4 }, { ...tangoHop(), hop_index: 5 }, { ...bravoHop(), hop_index: 6 }] },
      PATH_PARTIAL.branches[1],
    ] };
    const [t, b] = PATH_VALIDATION_COMPLETE.hops;
    const capped: PathValidation = { ...PATH_VALIDATION_COMPLETE, result: "MATCH", hops_not_validated: 2,
      hops: [t, { ...t, ordinal: 2, hop_index: 2, node_id: "FW-JULIET-06/physical/main" }, { ...t, ordinal: 3, hop_index: 3 }, { ...b, ordinal: 4, hop_index: 4, result: "MATCH", reasons: [] }] };
    renderResult(six, complete(capped, "b1"));
    expect(screen.getByTestId("path-verify-summary")).toHaveTextContent("4 of 4 hops confirmed by the devices. 2 hops beyond the 4-hop cap of one request were not asked.");
    expect(screen.getByTestId("path-verify-summary")).toHaveTextContent("Branch b1 was verified; the other equal-cost branches were not asked");
    const b1 = screen.getByTestId("path-branch-b1");
    expect(within(b1).getByTestId("path-hop-4")).toHaveAttribute("data-device", "MATCH");
    expect(within(b1).getByTestId("path-hop-5")).toHaveAttribute("data-device", "NOT_ASKED");
    expect(within(within(b1).getByTestId("path-hop-5")).getByText("Device not asked")).toBeInTheDocument();
    expect(within(b1).getByTestId("hop-6-device")).toHaveTextContent("Not asked: beyond the 4-hop cap of one request. The computed verdict for this hop is unverified.");
    const b2 = screen.getByTestId("path-branch-b2");
    expect(within(b2).queryAllByTestId(/hop-\d-device/)).toHaveLength(0);
    expect(within(b2).getByTestId("path-hop-1")).not.toHaveAttribute("data-device");
    expect(screen.getByRole("button", { name: "Verify branch b2 on devices" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Verify branch b1 again" })).toBeInTheDocument();
  });

  it("while pending: the progress line, the pending count, the pending badge with its job state, no claim on that hop", () => {
    renderResult(PATH_ALLOWED, verification({ phase: "polling", validationId: PATH_VALIDATION_PENDING.validation_id, validation: PATH_VALIDATION_PENDING, polls: 2, lastPolledAt: "2026-11-04T08:29:30Z" }));
    const progress = screen.getByTestId("path-verify-progress");
    expect(progress).toHaveTextContent("Waiting for the devices: 1 of 2 hops answered.");
    expect(progress).toHaveTextContent("Checked 2 times");
    expect(within(progress).getByText(/^Pending/)).toHaveTextContent("Pending · 1");
    expect(within(progress).getByText(/^Match/)).toHaveTextContent("Match · 1");
    expect(screen.getByText("Waiting for the devices")).toBeInTheDocument();
    const hop2 = screen.getByTestId("path-hop-2");
    expect(hop2).toHaveAttribute("data-device", "PENDING");
    expect(within(hop2).getByText(/^Device pending/)).toHaveTextContent("job executing");
    expect(within(hop2).getByTestId("hop-2-device")).toHaveTextContent("nothing is claimed yet");
    expect(screen.getByRole("button", { name: "Verify again" })).toBeDisabled();
    expect(screen.queryByRole("button", { name: "Verify on devices" })).toBeNull();
  });

  it("stalled: five minutes without every answer is a not-evaluated state with Check again, never a failure", () => {
    const checkAgain = vi.fn();
    renderResult(PATH_ALLOWED, verification({ phase: "stalled", validationId: "pv-x", validation: PATH_VALIDATION_PENDING }, noop, checkAgain));
    const title = screen.getByText("The devices have not all answered yet");
    expect(title.closest("[data-state]")).toHaveAttribute("data-state", "not_evaluated");
    expect(title.closest("[data-state]")).toHaveTextContent("nothing is lost");
    fireEvent.click(screen.getByRole("button", { name: "Check again" }));
    expect(checkAgain).toHaveBeenCalledTimes(1);
  });
});

describe("the honest states of a refused verification (the computed verdict stays)", () => {
  it("403: not permitted, names the roles, no Retry, the result is untouched", () => {
    renderResult(PATH_ALLOWED, failed(refusal(403, { code: "ACTION_REFUSED" })));
    const card = screen.getByText("Verification on devices").closest("[data-state]")!;
    expect(card).toHaveAttribute("data-state", "restricted");
    expect(card).toHaveTextContent("Security Administrator, Operation Administrator or aiview");
    expect(card).toHaveTextContent("nothing was sent to a device");
    expect(screen.queryByRole("button", { name: "Retry" })).toBeNull();
    expect(screen.getByTestId("path-verdict")).toHaveAttribute("data-verdict", "ALLOWED");
    expect(screen.queryAllByTestId(/hop-\d-device/)).toHaveLength(0);
  });

  it("409 OUTSIDE_JOB_WINDOW: the server's sentence, the next window time, nothing written", () => {
    renderResult(PATH_ALLOWED, failed(refusal(409, { code: "OUTSIDE_JOB_WINDOW", message: "Device jobs run only in the scheduled windows.", next_window_start: "2026-11-04T09:00:00Z" })));
    const card = screen.getByText("Outside the job window").closest("[data-state]")!;
    expect(card).toHaveAttribute("data-state", "not_evaluated");
    expect(card).toHaveTextContent("Device jobs run only in the scheduled windows.");
    expect(card).toHaveTextContent("Next window: 12:00");
    expect(card).toHaveTextContent("OUTSIDE_JOB_WINDOW");
    expect(screen.queryByRole("button", { name: "Retry" })).toBeNull();
  });

  it("business hours not confirmed, topology not built, pseudonym refused, branch gone: each in its own words", () => {
    const first = renderResult(PATH_ALLOWED, failed(refusal(409, { code: "BUSINESS_HOURS_CONFIRM_REQUIRED" })));
    expect(screen.getByText("Not started: business hours were not confirmed")).toBeInTheDocument();
    first.unmount();
    const second = renderResult(PATH_ALLOWED, failed(refusal(409, { code: "TOPOLOGY_NOT_BUILT" })));
    expect(screen.getByText("Topology not built").closest("[data-state]")).toHaveTextContent("A verification never builds one");
    second.unmount();
    const third = renderResult(PATH_ALLOWED, failed(refusal(422, { code: "PSEUDONYM_REFUSED" })));
    const refused = screen.getByText("Verification refused: a value was not recognised").closest("[data-state]")!;
    expect(refused).toHaveTextContent("nothing about them is stored");
    expect(refused.textContent).not.toContain("198.51.100.20");
    third.unmount();
    renderResult(PATH_PARTIAL, failed(refusal(404, { code: "BRANCH_NOT_FOUND" })));
    expect(screen.getByText("This branch is not in the recomputed path").closest("[data-state]")).toHaveTextContent("Nothing was sent");
    expect(screen.getByRole("button", { name: "Verify branch b1 on devices" })).toBeInTheDocument();
  });

  it("429, busy, audit, invalid, offline, unknown: each keeps its code and says nothing was sent or claimed", () => {
    const cases: [ApiError, string, string][] = [
      [refusal(429, { code: "PATH_VALIDATION_RATE_LIMIT" }), "Verification limit reached", "6 verifications a minute"],
      [refusal(503, { error: "POLICY_BUSY" }), "Path analysis is busy", "Nothing was sent"],
      [refusal(503, { code: "AUDIT_UNAVAILABLE" }), "Not started: the request record could not be written", "nothing was sent"],
      [refusal(400, { code: "INVALID_REQUEST" }), "The verification request was not accepted", "INVALID_REQUEST"],
      [refusal(0, { error: "TIMEOUT" }), "The request did not complete", "Whether the verification was admitted is not known"],
      [refusal(500, {}), "The verification could not be started", "HTTP 500"],
    ];
    for (const [error, title, text] of cases) {
      const retry = vi.fn();
      const view = renderResult(PATH_ALLOWED, verification({ phase: "failed", failure: classifyValidationFailure(error) }, retry));
      expect(screen.getByText(title).closest("[data-state]")).toHaveTextContent(text);
      const button = screen.queryByRole("button", { name: "Retry" });
      if (button) { fireEvent.click(button); expect(retry).toHaveBeenCalledWith(undefined); }
      view.unmount();
    }
  });

  it("a record that could not be read after submission keeps the hop states so far and offers Check again", () => {
    const checkAgain = vi.fn();
    renderResult(PATH_ALLOWED, verification({ phase: "failed", failure: classifyValidationFailure(refusal(503, {})), validationId: "pv-x", validation: PATH_VALIDATION_PENDING }, noop, checkAgain));
    const card = screen.getByText("The verification record could not be read").closest("[data-state]")!;
    expect(card).toHaveTextContent("HTTP 503");
    expect(screen.getByTestId("path-hop-1")).toHaveAttribute("data-device", "MATCH");
    fireEvent.click(screen.getByRole("button", { name: "Check again" }));
    expect(checkAgain).toHaveBeenCalledTimes(1);
    const gone = renderResult(PATH_ALLOWED, failed(refusal(404, { code: "NOT_FOUND" }), true));
    expect(screen.getByText("The verification record was not found")).toBeInTheDocument();
    gone.unmount();
  });
});

describe("on the screen: submit, poll with backoff, finish, reset", () => {
  async function evaluated(result: PathResult = PATH_ALLOWED) {
    vi.useFakeTimers();
    queryPath.mockResolvedValue(result);
    renderScreen();
    fireEvent.change(screen.getByLabelText(/^Source/), { target: { value: "198.51.100.20" } });
    fireEvent.change(screen.getByLabelText(/^Destination/), { target: { value: "203.0.113.200" } });
    fireEvent.click(screen.getByTestId("path-submit"));
    await flush();
    expect(screen.getByTestId("path-result")).toBeInTheDocument();
  }

  it("sends the result's own query, polls at 1 s then 2 s, and shows the finished verification on the hops", async () => {
    await evaluated();
    validatePath.mockResolvedValue(PATH_VALIDATION_PENDING);
    getPathValidation.mockResolvedValueOnce(PATH_VALIDATION_PENDING).mockResolvedValueOnce(PATH_VALIDATION_COMPLETE);
    fireEvent.click(screen.getByRole("button", { name: "Verify on devices" }));
    await flush();
    expect(validatePath).toHaveBeenCalledWith({ src: "198.51.100.20", dst: "203.0.113.200", protocol: "tcp", port: 443 }, undefined);
    expect(screen.getByTestId("path-verify")).toHaveAttribute("data-phase", "polling");
    expect(getPathValidation).not.toHaveBeenCalled();
    await act(async () => { await vi.advanceTimersByTimeAsync(999); });
    expect(getPathValidation).not.toHaveBeenCalled();
    await act(async () => { await vi.advanceTimersByTimeAsync(1); });
    expect(getPathValidation).toHaveBeenCalledTimes(1);
    expect(getPathValidation).toHaveBeenCalledWith(PATH_VALIDATION_PENDING.validation_id);
    expect(screen.getByTestId("path-verify-progress")).toHaveTextContent("Checked 1 time");
    await act(async () => { await vi.advanceTimersByTimeAsync(1_999); });
    expect(getPathValidation).toHaveBeenCalledTimes(1);
    await act(async () => { await vi.advanceTimersByTimeAsync(1); });
    expect(getPathValidation).toHaveBeenCalledTimes(2);
    expect(screen.getByTestId("path-verify")).toHaveAttribute("data-phase", "complete");
    expect(screen.getByTestId("path-verify-summary")).toHaveTextContent("1 of 2 hops confirmed by the devices; 1 disagrees");
    expect(screen.getByTestId("path-hop-2")).toHaveAttribute("data-device", "MISMATCH");
    await act(async () => { await vi.advanceTimersByTimeAsync(60_000); });
    expect(getPathValidation).toHaveBeenCalledTimes(2);
  });

  it("names the branch when the route splits and finishes at once when the 202 is already complete", async () => {
    await evaluated(PATH_PARTIAL);
    validatePath.mockResolvedValue({ ...PATH_VALIDATION_COMPLETE, result: "MATCH", hops: [{ ...PATH_VALIDATION_COMPLETE.hops[0] }], hops_not_validated: 0 });
    fireEvent.click(screen.getByRole("button", { name: "Verify branch b1 on devices" }));
    await flush();
    expect(validatePath).toHaveBeenCalledWith(expect.objectContaining({ src: "198.51.100.20" }), "b1");
    expect(screen.getByTestId("path-verify")).toHaveAttribute("data-phase", "complete");
    await act(async () => { await vi.advanceTimersByTimeAsync(30_000); });
    expect(getPathValidation).not.toHaveBeenCalled();
    expect(within(screen.getByTestId("path-branch-b1")).getByTestId("path-hop-1")).toHaveAttribute("data-device", "MATCH");
    expect(within(screen.getByTestId("path-branch-b2")).getByTestId("path-hop-1")).not.toHaveAttribute("data-device");
  });

  it("a refused submission is its own state beside the untouched verdict; Retry asks again", async () => {
    await evaluated();
    validatePath.mockRejectedValueOnce(refusal(429, { code: "PATH_VALIDATION_RATE_LIMIT" })).mockResolvedValueOnce(PATH_VALIDATION_COMPLETE);
    fireEvent.click(screen.getByRole("button", { name: "Verify on devices" }));
    await flush();
    expect(screen.getByText("Verification limit reached")).toBeInTheDocument();
    expect(screen.getByTestId("path-verdict")).toHaveAttribute("data-verdict", "ALLOWED");
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    await flush();
    expect(validatePath).toHaveBeenCalledTimes(2);
    expect(screen.getByTestId("path-verify")).toHaveAttribute("data-phase", "complete");
  });

  it("three failed reads in a row stop the polling with Check again, which resumes it", async () => {
    await evaluated();
    validatePath.mockResolvedValue(PATH_VALIDATION_PENDING);
    getPathValidation.mockRejectedValue(refusal(503, {}));
    fireEvent.click(screen.getByRole("button", { name: "Verify on devices" }));
    await flush();
    await act(async () => { await vi.advanceTimersByTimeAsync(1_000 + 2_000 + 4_000); });
    expect(getPathValidation).toHaveBeenCalledTimes(3);
    expect(screen.getByText("The verification record could not be read")).toBeInTheDocument();
    expect(screen.getByTestId("path-hop-1")).toHaveAttribute("data-device", "MATCH");
    await act(async () => { await vi.advanceTimersByTimeAsync(60_000); });
    expect(getPathValidation).toHaveBeenCalledTimes(3);
    getPathValidation.mockResolvedValue(PATH_VALIDATION_COMPLETE);
    fireEvent.click(screen.getByRole("button", { name: "Check again" }));
    await flush();
    expect(getPathValidation).toHaveBeenCalledTimes(4);
    expect(screen.getByTestId("path-verify")).toHaveAttribute("data-phase", "complete");
  });

  it("a 403 on the read stops at once as not permitted", async () => {
    await evaluated();
    validatePath.mockResolvedValue(PATH_VALIDATION_PENDING);
    getPathValidation.mockRejectedValue(refusal(403, { code: "ACTION_REFUSED" }));
    fireEvent.click(screen.getByRole("button", { name: "Verify on devices" }));
    await flush();
    await act(async () => { await vi.advanceTimersByTimeAsync(1_000); });
    expect(getPathValidation).toHaveBeenCalledTimes(1);
    expect(screen.getByText("Verification on devices").closest("[data-state]")).toHaveAttribute("data-state", "restricted");
  });

  it("after five minutes still pending the verification stalls; the jobs are not re-submitted", async () => {
    await evaluated();
    validatePath.mockResolvedValue(PATH_VALIDATION_PENDING);
    getPathValidation.mockResolvedValue(PATH_VALIDATION_PENDING);
    fireEvent.click(screen.getByRole("button", { name: "Verify on devices" }));
    await flush();
    await act(async () => { await vi.advanceTimersByTimeAsync(5 * 60_000 + 11_000); });
    expect(screen.getByTestId("path-verify")).toHaveAttribute("data-phase", "stalled");
    const calls = getPathValidation.mock.calls.length;
    expect(calls).toBeGreaterThanOrEqual(30);
    await act(async () => { await vi.advanceTimersByTimeAsync(60_000); });
    expect(getPathValidation).toHaveBeenCalledTimes(calls);
    expect(validatePath).toHaveBeenCalledTimes(1);
    expect(screen.getByText("The devices have not all answered yet")).toBeInTheDocument();
  });

  it("a new evaluation resets the verification: no device word from one computation beside another", async () => {
    await evaluated();
    validatePath.mockResolvedValue(PATH_VALIDATION_COMPLETE);
    fireEvent.click(screen.getByRole("button", { name: "Verify on devices" }));
    await flush();
    expect(screen.getByTestId("path-hop-2")).toHaveAttribute("data-device", "MISMATCH");
    queryPath.mockResolvedValue({ ...PATH_ALLOWED, computed_at: "2026-11-04T08:40:00Z" });
    fireEvent.click(screen.getByTestId("path-submit"));
    await flush();
    expect(screen.getByTestId("path-verify")).toHaveAttribute("data-phase", "idle");
    expect(screen.getByTestId("path-hop-2")).not.toHaveAttribute("data-device");
    expect(screen.queryAllByTestId(/hop-\d-device/)).toHaveLength(0);
  });
});
