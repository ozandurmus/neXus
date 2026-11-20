import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { AsaConfigRelation } from "../src/auth/adminApi";
import { ASA_UNSAVED_HINT, AsaSavedConfigNote, keywordCountsWords, savedConfigDiffersIds } from "../src/screens/AsaSavedConfigDiff";

const mismatch: AsaConfigRelation = {
  device_id: "dev-00000002", hostname: "FW-TANGO-04", relation: "MISMATCH", lines_differing: 4,
  differing_keywords: { "object-group": 1, "access-list": 3 },
  backup_created_at: "2026-11-19T03:00:00Z", derived_at: "2026-11-19T03:01:00Z",
};
const match: AsaConfigRelation = { ...mismatch, device_id: "dev-00000001", relation: "MATCH", lines_differing: 0, differing_keywords: {} };

describe("ASA saved vs running configuration", () => {
  it("words the keyword counts biggest first", () => {
    expect(keywordCountsWords(mismatch.differing_keywords)).toBe("access-list 3, object-group 1");
    expect(keywordCountsWords(undefined)).toBe("");
  });

  it("the filter set holds MISMATCH devices only", () => {
    expect([...savedConfigDiffersIds([mismatch, match])]).toEqual(["dev-00000002"]);
    expect(savedConfigDiffersIds(null).size).toBe(0);
  });

  it("a MISMATCH shows keywords, both times and the text-only hint, with no button", () => {
    render(<AsaSavedConfigNote relation={mismatch} />);
    const note = screen.getByTestId("asa-saved-config-diff");
    expect(note.textContent).toContain("access-list 3, object-group 1");
    expect(note.textContent).toContain("Backup read");
    expect(note.textContent).toContain("compared");
    expect(note.textContent).toContain(ASA_UNSAVED_HINT);
    expect(note.querySelector("button")).toBeNull();
  });

  it("a MATCH or no relation shows nothing", () => {
    const { container, rerender } = render(<AsaSavedConfigNote relation={match} />);
    expect(container.textContent).toBe("");
    rerender(<AsaSavedConfigNote relation={undefined} />);
    expect(container.textContent).toBe("");
  });
});
