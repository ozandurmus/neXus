import { expect, it } from "vitest";
import {
  ALIGNMENT, INVENTORY, CONFIG_DEVICES, CONFIG_SETTINGS, COMPLIANCE_FINDINGS,
  OPERATIONS_JOBS, OPERATIONS_READINESS, ADMIN_DEVICES,
} from "../src/preview/previewData";

it("uses AIView names throughout the shared preview data", () => {
  const names = [
    ...ALIGNMENT.map((row) => row.device),
    ...INVENTORY.map((row) => row.name),
    ...CONFIG_DEVICES.map((row) => row.name),
    ...CONFIG_SETTINGS.map((row) => row.device),
    ...COMPLIANCE_FINDINGS.map((row) => row.device),
    ...OPERATIONS_JOBS.map((row) => row.target),
    ...OPERATIONS_READINESS.map((row) => row.cluster),
    ...ADMIN_DEVICES.map((row) => row.name),
  ];
  for (const name of names) {
    expect(name).toMatch(/^(?:FW-[A-Z]+-\d{2}(?:-M[12])?|CLS-[A-Z]+-\d{2}|VS-[A-Z]+-\d{2}-\d{2})$/);
  }
  expect(JSON.stringify({ ALIGNMENT, INVENTORY, CONFIG_DEVICES, CONFIG_SETTINGS, COMPLIANCE_FINDINGS, OPERATIONS_JOBS, OPERATIONS_READINESS, ADMIN_DEVICES }))
    .not.toMatch(/\b(?:fw|pan|cp|vsx|pano)-[a-z]|\b(?:ist|izm|ank|brs)\b/);
});

it("keeps the executive home preview to counts: no device or cluster name, no address (UI review 2026-11-05, C1)", async () => {
  const { EXEC_HOME } = await import("../src/preview/previewData");
  const text = JSON.stringify(EXEC_HOME);
  expect(text).not.toMatch(/\b(?:FW|CLS|VS)-[A-Z]+-\d{2}\b/);
  expect(text).not.toMatch(/\b\d{1,3}(?:\.\d{1,3}){3}\b/);
});
