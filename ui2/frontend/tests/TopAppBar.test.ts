import { describe, expect, it } from "vitest";

import { roleLabel } from "../src/shell/TopAppBar";

describe("roleLabel", () => {
  it("turns session role tokens into readable labels", () => {
    expect(roleLabel(["role:viewer", "role:onboarding_admin"])).toBe("Viewer, Onboarding Admin");
    expect(roleLabel([])).toBe("No role assigned");
  });
});
