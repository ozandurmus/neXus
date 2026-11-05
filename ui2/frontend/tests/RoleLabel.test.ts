import { describe, expect, it } from "vitest";
import { primaryRoleLabel, roleTokenLabel } from "../src/shell/roleLabel";

/** B7 (UI review 2026-11-05): aiview read "Compliance Admin" in the top bar; the inspection persona's own word wins. */
describe("the role words under the signed-in name", () => {
  it("words the aiview persona as itself, even when the session also carries an administrator token", () => {
    expect(primaryRoleLabel(["role:compliance_admin", "role:replay_viewer"])).toBe("aiview · read-only");
    expect(primaryRoleLabel(["role:replay_viewer"])).toBe("aiview · read-only");
    expect(roleTokenLabel("role:replay_viewer")).toBe("aiview · read-only");
  });
  it("keeps the ordinary labels, highest first, and says No Role when there is none", () => {
    expect(primaryRoleLabel(["role:viewer", "role:security_admin"])).toBe("Security Admin");
    expect(primaryRoleLabel(["role:viewer"])).toBe("Viewer");
    expect(primaryRoleLabel([])).toBe("No Role");
    expect(roleTokenLabel("role:something_new")).toBe("Something New");
  });
});
