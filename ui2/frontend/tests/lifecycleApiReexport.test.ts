import { afterEach, describe, expect, it, vi } from "vitest";
import * as adminApi from "../src/auth/adminApi";
import * as lifecycleApi from "../src/auth/lifecycleApi";

describe("lifecycleApi re-export through adminApi", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("exposes the lifecycle client under adminApi with the same bindings", () => {
    expect(typeof adminApi.getLifecycle).toBe("function");
    expect(typeof adminApi.getLifecycleCatalog).toBe("function");
    expect(adminApi.getLifecycle).toBe(lifecycleApi.getLifecycle);
    expect(adminApi.getLifecycleCatalog).toBe(lifecycleApi.getLifecycleCatalog);
    expect(typeof adminApi.apiCall).toBe("function");
  });

  it("keeps the request path and method of a moved GET through apiCall", async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ entries: [], can_manage: false }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    }));
    vi.stubGlobal("fetch", fetchMock);
    const result = await adminApi.getLifecycleCatalog();
    expect(result.entries).toEqual([]);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("/api/v2/lifecycle/catalog");
    expect(init.method).toBe("GET");
  });
});
