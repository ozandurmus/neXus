import { afterEach, expect, it, vi } from "vitest";
import { getPolicyRule } from "../src/auth/adminApi";

afterEach(() => vi.unstubAllGlobals());

it("requests rule details with an opaque rule ID in the query string", async () => {
  const request = vi.fn(async () => new Response(JSON.stringify({ rule: {} })));
  vi.stubGlobal("fetch", request);

  await getPolicyRule("policy one", "0001/a?b&c=+", 30);

  expect(request.mock.calls[0]).toEqual([
    "/api/v2/policy/policies/policy%20one/rules?ruleId=0001%2Fa%3Fb%26c%3D%2B&days=30",
    expect.objectContaining({ method: "GET" }),
  ]);
});
