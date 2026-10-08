import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { JobButton, JobWindowContext, JobWindowProvider, windowView } from "../src/shell/JobWindow";
import { M3Button } from "../src/shell/M3Widgets";
import { getJobWindow } from "../src/auth/adminApi";
vi.mock("../src/auth/adminApi", () => ({ getJobWindow: vi.fn() }));
afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); vi.resetAllMocks(); });
const status = { open: true, server_time: "2026-10-08T09:59:59Z", window_end: "2026-10-08T10:00:00Z", next_window_start: "2026-10-08T18:00+03:00[Europe/Istanbul]" };
it("closes exactly at the boundary using server time", () => {
  expect(windowView(status, 999).open).toBe(true);
  expect(windowView(status, 1000)).toEqual({ open: false, label: "Next window: 18:00" });
});
it("disables manual start buttons but preserves internal actions", () => {
  const start = vi.fn();
  render(<JobWindowContext.Provider value={{ open: false, label: "Next window: 12:00" }}>
    <JobButton onClick={start}>Run readiness</JobButton>
    <M3Button deviceJob emphasis="filled" onClick={start}>Start failover</M3Button>
    <M3Button emphasis="text">Refresh history</M3Button>
  </JobWindowContext.Provider>);
  const readiness = screen.getByRole("button", { name: "Run readiness · Next window: 12:00" });
  expect(readiness).toBeDisabled();
  fireEvent.click(readiness);
  expect(start).not.toHaveBeenCalled();
  expect(screen.getByRole("button", { name: "Start failover · Next window: 12:00" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "Refresh history" })).toBeEnabled();
});
it("fails closed while loading or when the policy endpoint is unavailable", async () => {
  vi.mocked(getJobWindow).mockRejectedValue(new Error("unavailable"));
  render(<JobWindowProvider><JobButton>Collect</JobButton></JobWindowProvider>);
  expect(screen.getByRole("button")).toBeDisabled();
  await waitFor(() => expect(getJobWindow).toHaveBeenCalled());
  expect(screen.getByRole("button")).toHaveTextContent("Window status unavailable");
});
it("expires an open sample even before the next API poll", async () => {
  vi.useFakeTimers();
  const clock = vi.spyOn(performance, "now").mockReturnValue(0);
  vi.mocked(getJobWindow).mockResolvedValue(status);
  render(<JobWindowProvider><JobButton>Collect</JobButton></JobWindowProvider>);
  await act(async () => { await Promise.resolve(); });
  expect(screen.getByRole("button")).toBeEnabled();
  clock.mockReturnValue(2000);
  await act(async () => { vi.advanceTimersByTime(2000); });
  expect(screen.getByRole("button")).toBeDisabled();
});
