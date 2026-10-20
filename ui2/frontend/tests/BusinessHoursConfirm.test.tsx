import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { requestInventoryCollect, setBusinessHoursConfirm } from "../src/auth/adminApi";
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
afterEach(() => { setBusinessHoursConfirm(null); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

it("resubmits once with the acknowledgement after Continue, and a minute of requests shares that answer", async () => {
  const sent: Sent[] = [];
  server(sent);
  const ask = vi.fn(() => Promise.resolve(true));
  setBusinessHoursConfirm(ask);
  await expect(requestInventoryCollect("device-1")).resolves.toEqual({ job_id: "job-1" });
  expect(ask).toHaveBeenCalledWith(message);
  expect(sent.map(s => s.ack)).toEqual([null, "true"]);
  await requestInventoryCollect("device-2");
  expect(ask).toHaveBeenCalledTimes(1);
  expect(sent.slice(2).map(s => s.ack)).toEqual([null, "true"]);
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
