import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { newBusinessHoursAckScope, requestInventoryCollect, runCpFailoverReadiness, setBusinessHoursConfirm, startCpFailover,
  type CpFailoverUnit } from "../src/auth/adminApi";
import { BusinessHoursConfirmProvider } from "../src/shell/BusinessHoursConfirm";

// PO decision 2026-10-20: an administrator's manual device job inside business hours is confirmed, then resubmitted
// with the acknowledgement header; nothing is admitted before the confirmation.
const message = "Business hours (08:00–20:00 GMT+3). This sends commands to the device. Continue?";
const json = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status }));
type Sent = { url: string; ack: string | null };
function server(sent: Sent[]) {
  vi.stubGlobal("fetch", vi.fn((url: string, init?: RequestInit) => {
    if (url === "/session/status") return json({ csrf_token: "csrf-synthetic" });
    const ack = (init?.headers as Record<string, string> | undefined)?.["X-Nexus-Business-Hours-Ack"] ?? null;
    sent.push({ url, ack });
    return ack === "true" ? json({ job_id: "job-1" }, 202)
      : json({ code: "BUSINESS_HOURS_CONFIRM_REQUIRED", message, ack_header: "X-Nexus-Business-Hours-Ack" }, 409);
  }));
}
const UNIT = { clusterId: "cluster-1", unitId: "unit-1", vendor: "check_point" } as unknown as CpFailoverUnit;
const BINDING = { requestId: "request-1", revision: 1, executionNonce: "nonce-1", warningConfirmed: true } as const;
afterEach(() => { setBusinessHoursConfirm(null); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

it("resubmits only the confirmed request once with the acknowledgement; the next request asks again", async () => {
  const sent: Sent[] = [];
  server(sent);
  const ask = vi.fn(() => Promise.resolve(true));
  setBusinessHoursConfirm(ask);
  await expect(requestInventoryCollect("device-1")).resolves.toEqual({ job_id: "job-1" });
  expect(ask).toHaveBeenCalledWith(message);
  expect(sent.map(s => s.ack)).toEqual([null, "true"]);
  await requestInventoryCollect("device-2");
  expect(ask).toHaveBeenCalledTimes(2);
  expect(sent.slice(2).map(s => s.ack)).toEqual([null, "true"]);
});

// Argon review 2026-10-20 P1-2: a confirmation never carries over to another action, a failover start least of all.
it("a failover start right after a confirmed read job asks again and is never auto-acknowledged", async () => {
  const sent: Sent[] = [];
  server(sent);
  const answers = [true, false];
  const ask = vi.fn(() => Promise.resolve(answers.shift()!));
  setBusinessHoursConfirm(ask);
  await requestInventoryCollect("device-1");
  await expect(startCpFailover(UNIT, BINDING)).rejects.toMatchObject({ status: 409 });
  expect(ask).toHaveBeenCalledTimes(2);
  const starts = sent.filter(s => s.url.endsWith("/runs"));
  expect(starts.map(s => s.ack)).toEqual([null]);
});

it("concurrent unrelated requests each get their own answer, one dialog at a time", async () => {
  const sent: Sent[] = [];
  server(sent);
  const answers = [true, false];
  let open = 0, maxOpen = 0;
  setBusinessHoursConfirm(() => {
    open++; maxOpen = Math.max(maxOpen, open);
    return new Promise<boolean>(resolve => setTimeout(() => { open--; resolve(answers.shift()!); }, 5));
  });
  const results = await Promise.allSettled([requestInventoryCollect("device-1"), startCpFailover(UNIT, BINDING)]);
  expect(results.map(r => r.status)).toEqual(["fulfilled", "rejected"]);
  expect(maxOpen).toBe(1);
  expect(sent.filter(s => s.url.endsWith("/runs")).map(s => s.ack)).toEqual([null]);
});

it("one bulk action's scope shares one Continue among its own requests and no others", async () => {
  const sent: Sent[] = [];
  server(sent);
  const ask = vi.fn(() => Promise.resolve(true));
  setBusinessHoursConfirm(ask);
  const scope = newBusinessHoursAckScope();
  await runCpFailoverReadiness({ clusterId: "cluster-1", unitId: "unit-1", vendor: "check_point" }, scope);
  await runCpFailoverReadiness({ clusterId: "cluster-2", unitId: "unit-2", vendor: "check_point" }, scope);
  expect(ask).toHaveBeenCalledTimes(1);
  // Every request still goes out first without the header; the scope only answers the server's question.
  expect(sent.map(s => s.ack)).toEqual([null, "true", null, "true"]);
  // A request outside the scope -- another readiness run or a failover start -- asks again.
  await runCpFailoverReadiness({ clusterId: "cluster-3", unitId: "unit-3", vendor: "check_point" });
  await startCpFailover(UNIT, BINDING);
  expect(ask).toHaveBeenCalledTimes(3);
});

it("a cancelled bulk confirmation refuses that bulk action's later requests without asking again", async () => {
  const sent: Sent[] = [];
  server(sent);
  const ask = vi.fn(() => Promise.resolve(false));
  setBusinessHoursConfirm(ask);
  const scope = newBusinessHoursAckScope();
  for (const unitId of ["unit-1", "unit-2"])
    await expect(runCpFailoverReadiness({ clusterId: "cluster-1", unitId, vendor: "check_point" }, scope))
      .rejects.toMatchObject({ status: 409 });
  expect(ask).toHaveBeenCalledTimes(1);
  expect(sent.map(s => s.ack)).toEqual([null, null]);
});

it("Cancel admits nothing and reports a plain reason", async () => {
  const sent: Sent[] = [];
  server(sent);
  setBusinessHoursConfirm(() => Promise.resolve(false));
  await expect(requestInventoryCollect("device-1")).rejects.toMatchObject({
    status: 409, body: { code: "BUSINESS_HOURS_CONFIRM_REQUIRED", message: "Not started: business hours were not confirmed." } });
  expect(sent).toHaveLength(1);
});

it("without a registered dialog the refusal is reported, never silently acknowledged", async () => {
  const sent: Sent[] = [];
  server(sent);
  await expect(requestInventoryCollect("device-1")).rejects.toMatchObject({ status: 409 });
  expect(sent.map(s => s.ack)).toEqual([null]);
});

it("the dialog shows the server's words and Continue resubmits", async () => {
  const sent: Sent[] = [];
  server(sent);
  render(<BusinessHoursConfirmProvider><span>screen</span></BusinessHoursConfirmProvider>);
  const pending = requestInventoryCollect("device-1");
  const dialog = await screen.findByRole("dialog", { name: "Business hours" });
  expect(dialog).toHaveTextContent(message);
  fireEvent.click(screen.getByRole("button", { name: "Continue" }));
  await expect(pending).resolves.toEqual({ job_id: "job-1" });
  expect(sent.map(s => s.ack)).toEqual([null, "true"]);
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
});
