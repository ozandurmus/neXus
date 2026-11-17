import { describe, expect, it } from "vitest";
import { refusalText } from "../src/screens/BackupScreen";

describe("refusalText", () => {
  it("shows the server code and reason of an ApiError", () => {
    expect(refusalText({ status: 409, body: { error: "ADMISSION_REFUSED", code: "CAPABILITY_NOT_EXECUTION_ELIGIBLE", reason: "a step is UNKNOWN" } }))
      .toBe("CAPABILITY_NOT_EXECUTION_ELIGIBLE: a step is UNKNOWN");
  });
  it("keeps an Error message and never prints [object Object]", () => {
    expect(refusalText(new Error("boom"))).toBe("boom");
    expect(refusalText({ status: 500, body: {} })).not.toBe("");
  });
});
