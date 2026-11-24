import { describe, expect, it } from "vitest";
import type { ApiError } from "../src/auth/adminApi";
import { packetBytes, packetError } from "../src/screens/packetanalysis/packetFormat";

const KB = 1024;
const MB = KB * 1024;
const GB = MB * 1024;
const TB = GB * 1024;

describe("packetBytes", () => {
  it("renders zero as plain bytes", () => {
    expect(packetBytes(0)).toBe("0 B");
  });

  it("keeps values below the first unit boundary in bytes", () => {
    expect(packetBytes(1)).toBe("1 B");
    expect(packetBytes(KB - 1)).toBe("1023 B");
  });

  it("switches to KB exactly at the boundary", () => {
    expect(packetBytes(KB)).toBe("1.0 KB");
    expect(packetBytes(1536)).toBe("1.5 KB");
  });

  it("switches to MB, GB and TB at their boundaries", () => {
    expect(packetBytes(MB)).toBe("1.0 MB");
    expect(packetBytes(GB)).toBe("1.0 GB");
    expect(packetBytes(TB)).toBe("1.0 TB");
  });

  it("renders a 2 GB capture in GB", () => {
    expect(packetBytes(2 * GB)).toBe("2.0 GB");
  });

  it("stops at TB for very large values", () => {
    expect(packetBytes(1024 * TB)).toBe("1024.0 TB");
  });

  it("rounds just below a boundary up to the next unit's digits (current behaviour)", () => {
    // 1023.99 KB is rendered with toFixed(1) and is not promoted to MB.
    expect(packetBytes(MB - 1)).toBe("1024.0 KB");
  });

  it("returns UNKNOWN for negative input", () => {
    expect(packetBytes(-1)).toBe("UNKNOWN");
    expect(packetBytes(-GB)).toBe("UNKNOWN");
  });

  it("treats negative zero as zero bytes", () => {
    expect(packetBytes(-0)).toBe("0 B");
  });

  it("returns UNKNOWN for non-finite input", () => {
    expect(packetBytes(Number.NaN)).toBe("UNKNOWN");
    expect(packetBytes(Number.POSITIVE_INFINITY)).toBe("UNKNOWN");
    expect(packetBytes(Number.NEGATIVE_INFINITY)).toBe("UNKNOWN");
  });
});

const FALLBACK = "Could not load the capture.";

function apiError(status: number, body: Record<string, unknown>): ApiError {
  return { status, body };
}

describe("packetError", () => {
  it("falls back for an Error instance without an API status", () => {
    expect(packetError(new Error("network down"), FALLBACK)).toBe(FALLBACK);
  });

  it("falls back for a plain string", () => {
    expect(packetError("boom", FALLBACK)).toBe(FALLBACK);
  });

  it("falls back for an object without message or body", () => {
    expect(packetError({ message: "x" }, FALLBACK)).toBe(FALLBACK);
  });

  it("falls back for null and undefined", () => {
    expect(packetError(null, FALLBACK)).toBe(FALLBACK);
    expect(packetError(undefined, FALLBACK)).toBe(FALLBACK);
  });

  it("returns the role message for 403", () => {
    expect(packetError(apiError(403, { reason: "ignored" }), FALLBACK)).toBe(
      "Your role does not allow this action.",
    );
  });

  it("returns the connection message for status 0", () => {
    expect(packetError(apiError(0, {}), FALLBACK)).toBe(
      "The request did not complete. Check the connection and retry.",
    );
  });

  it("returns the server reason when present", () => {
    expect(packetError(apiError(409, { reason: "Case is closed" }), FALLBACK)).toBe("Case is closed");
  });

  it("uses body.error when body.reason is absent", () => {
    expect(packetError(apiError(400, { error: "Upload too large" }), FALLBACK)).toBe("Upload too large");
  });

  it("prefers body.reason over body.error", () => {
    expect(packetError(apiError(400, { reason: "A", error: "B" }), FALLBACK)).toBe("A");
  });

  it("falls back when the server reason is an empty string (current behaviour)", () => {
    expect(packetError(apiError(400, { reason: "" }), FALLBACK)).toBe(FALLBACK);
  });

  it("falls back when the server reason is not a string", () => {
    expect(packetError(apiError(500, { reason: 42 }), FALLBACK)).toBe(FALLBACK);
  });

  it("falls back for an API error with an empty body", () => {
    expect(packetError(apiError(500, {}), FALLBACK)).toBe(FALLBACK);
  });
});
