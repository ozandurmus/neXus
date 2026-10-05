import { afterEach, expect, it, vi } from "vitest";
import { getPolicyUsage } from "../src/auth/adminApi";

afterEach(() => vi.unstubAllGlobals());

it("requests object usage with an opaque UID in the query string", async () => {
  const request = vi.fn(async () => new Response(JSON.stringify({ rules: [] })));
  vi.stubGlobal("fetch", request);

  await getPolicyUsage("source & one", "domain one", "0001/a?b&c=+", 2);

  expect(request.mock.calls[0]).toEqual([
    "/api/v2/policy/domains/domain%20one/object-usage?source=source%20%26%20one&page=2&q=&uid=0001%2Fa%3Fb%26c%3D%2B",
    expect.objectContaining({ method: "GET" }),
  ]);
});
