import { afterEach, describe, expect, it, vi } from "vitest";
import { addDeviceSingle, listDevices } from "../src/auth/adminApi";

describe("admin API request timeouts", () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it("aborts a pending GET and rejects after 20 seconds", async () => {
    vi.useFakeTimers();
    let signal: AbortSignal | undefined;
    vi.stubGlobal("fetch", vi.fn((_input: RequestInfo | URL, init?: RequestInit) => {
      signal = init?.signal as AbortSignal;
      return new Promise<Response>(() => {});
    }));

    const pending = listDevices();
    const rejection = expect(pending).rejects.toEqual({ status: 0, body: { error: "TIMEOUT" } });
    await vi.advanceTimersByTimeAsync(20_000);
    await rejection;
    expect(signal?.aborted).toBe(true);
  });

  it("gives POST requests 60 seconds", async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === "/session/status") return Promise.resolve(new Response(JSON.stringify({ csrf_token: "synthetic-token" })));
      return new Promise<Response>(() => {
        expect(init?.signal).toBeInstanceOf(AbortSignal);
      });
    });
    vi.stubGlobal("fetch", fetchMock);

    const pending = addDeviceSingle("192.0.2.10", "gateway", "check_point", "cred-ref");
    const rejection = expect(pending).rejects.toEqual({ status: 0, body: { error: "TIMEOUT" } });
    await vi.advanceTimersByTimeAsync(59_999);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(1);
    await rejection;
  });

  it("does not affect a request that resolves in time", async () => {
    vi.useFakeTimers();
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(new Response(JSON.stringify({ devices: [] })))));
    await expect(listDevices()).resolves.toEqual({ devices: [] });
    await vi.advanceTimersByTimeAsync(20_000);
  });
});
