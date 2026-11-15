import { render, screen, within } from "@testing-library/react";
import { expect, it } from "vitest";
import type { ClusterMemberDiff } from "../src/auth/adminApi";
import { ClusterMemberDiffView } from "../src/screens/ClusterMemberDiffPanel";

const labels = { "dev-a": "FW-ALPHA-M1", "dev-b": "FW-ALPHA-M2" };
const base: ClusterMemberDiff = {
  cluster_member_ref: "CLS-ALPHA-01",
  members: [{ device_id: "dev-a" }, { device_id: "dev-b" }],
  comparable: true,
  reason: null,
  summary: { same: 1, differs: 1, unknown: 1 },
  fields: [
    { key: "model", label: "Model", note: null, status: "SAME", values: { "dev-a": "FW-9000", "dev-b": "FW-9000" }, detail: [] },
    { key: "interfaces", label: "Interfaces", note: null, status: "DIFFERS", values: { "dev-a": "2 interfaces", "dev-b": "2 interfaces" }, detail: ["Physical/eth1", "Physical/eth2"] },
    { key: "policy_installed", label: "Installed policy", note: null, status: "UNKNOWN", values: { "dev-a": "POLICY-1", "dev-b": null }, detail: [] },
  ],
};
const row = (key: string) => document.querySelector(`[data-member-diff-field="${key}"]`) as HTMLElement;

it("shows same, differs and unknown per field, with what is not shared and Unknown for a missing value", () => {
  render(<ClusterMemberDiffView diff={base} labels={labels} />);
  expect(row("model")).toHaveAttribute("data-status", "SAME");
  expect(within(row("model")).getByText("Same")).toBeInTheDocument();
  expect(within(row("interfaces")).getByText("Differs")).toBeInTheDocument();
  expect(within(row("interfaces")).getByText(/Not on every member: Physical\/eth1, Physical\/eth2/)).toBeInTheDocument();
  expect(within(row("policy_installed")).getByText("Unknown (not stored)")).toBeInTheDocument();
  expect(within(row("policy_installed")).getByText("Unknown")).toBeInTheDocument();
  expect(screen.getByText("FW-ALPHA-M2")).toBeInTheDocument();
  expect(document.querySelector("[data-member-diff-summary]")).toHaveTextContent("1 differ · 1 same · 1 unknown");
});

it("says why an incomplete pair is not compared and shows no table", () => {
  render(<ClusterMemberDiffView diff={{ ...base, comparable: false, reason: "PAIR_INCOMPLETE", fields: [], members: [{ device_id: "dev-a" }] }} labels={labels} />);
  expect(screen.getByText("Members cannot be compared")).toBeInTheDocument();
  expect(screen.queryByRole("table")).toBeNull();
});
