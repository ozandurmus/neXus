import { describe, expect, it } from "vitest";
import { describeApiError } from "../src/shell/apiErrorText";

describe("describeApiError", () => {
  it("prefers the server body.error string", () => {
    const err = { status: 500, body: { error: "server says no" } };
    expect(describeApiError(err)).toBe("server says no");
  });

  it("falls back to the status when there is no body.error", () => {
    expect(describeApiError({ status: 500, body: {} })).toBe("request failed (status 500)");
  });

  it("returns the bare request-failed text when no status or body is present", () => {
    expect(describeApiError(new Error("network down"))).toBe("request failed");
  });

  it("ignores a non-string body.error", () => {
    expect(describeApiError({ status: 404, body: { error: 42 } })).toBe("request failed (status 404)");
  });
});
