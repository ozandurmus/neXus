import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { PolicyOfflineTab, offlineResultsCsv } from "../src/screens/PolicyOfflineTab";
import { detectMapping, parsePreview } from "../src/screens/offlineCsv";
import { deleteOfflineAnalysis, getOfflineAnalysis, listOfflineAnalyses, uploadOfflineAnalysis, type OfflineAnalysisResult } from "../src/auth/adminApi";
vi.mock("../src/auth/adminApi", () => ({ deleteOfflineAnalysis: vi.fn(), getOfflineAnalysis: vi.fn(), listOfflineAnalyses: vi.fn(), uploadOfflineAnalysis: vi.fn() }));
const result: OfflineAnalysisResult = {
  id: "a1", policyId: "p1", fileName: "FILE-ALPHA", uploader: "ACTOR-ALPHA", createdAt: "2026-10-09T12:00:00Z", periodStart: "", periodEnd: "",
  rowCount: 3, invalidRows: 1, flowCount: 2, attributedFlows: 1, agreementCount: 0, disagreementCount: 1, comparisonUnknownCount: 1, comparisonEnabled: true,
  comparisonNote: "Disagreement may indicate a stale snapshot or NAT/routing effects.", attributionNote: "Sample flows only.", limitations: "Policy intent only.",
  caution: "Verify before applying; sample may be incomplete.", parseStats: [{ statReason: "invalid IP address", count: 1 }], unknownStats: [{ statReason: "protocol unsupported", count: 1 }], verdictStats: [{ statReason: "ALLOWED", count: 1 }],
  rules: [{ ruleId: "r1", name: "RULE-ALPHA", number: 1, action: "allow", sectionName: "SECTION-ALPHA", flowCount: 1, firstTime: "", lastTime: "",
    distinctSources: 1, distinctDestinations: 1, distinctServices: 1, topObserved: [{ observedSource: "192.0.2.8", count: 1 }], zeroTraffic: "", severity: "LOW", memberStatus: "RESOLVED",
    unseenMembers: [{ memberId: "o2", name: "OBJECT-BRAVO", dimension: "source" }], narrowing: { memberStatus: "RESOLVED", observedSource: ["192.0.2.8/32"], observedDestination: ["198.51.100.8/32"], observedService: ["tcp/443"], coveragePercent: 100, coveredFlows: 1, caution: "Verify before applying; sample may be incomplete." } },
    { ruleId: "r2", name: "RULE-BRAVO", number: 2, action: "deny", sectionName: "SECTION-ALPHA", flowCount: 0, firstTime: "", lastTime: "", distinctSources: 0, distinctDestinations: 0, distinctServices: 0,
      topObserved: [], zeroTraffic: "No traffic in this sample (period UNKNOWN–UNKNOWN, 3 rows).", severity: "LOW", memberStatus: "UNKNOWN", lookupReason: "unresolved object", unseenMembers: [], narrowing: {} }],
};
beforeEach(() => {
  vi.mocked(listOfflineAnalyses).mockResolvedValue({ analyses: [result], canManage: false });
  vi.mocked(getOfflineAnalysis).mockResolvedValue(result); vi.mocked(uploadOfflineAnalysis).mockResolvedValue({ id: "a1" });
  vi.mocked(deleteOfflineAnalysis).mockResolvedValue({ deleted: true });
});
afterEach(() => { vi.clearAllMocks(); vi.restoreAllMocks(); });
async function chooseAnalysis() {
  const select = await screen.findByLabelText("Stored analysis"); fireEvent.mouseDown(select); fireEvent.click(await screen.findByRole("option", { name: /FILE-ALPHA/ }));
  await screen.findByText("Sample summary");
}
function file(text: string) {
  const f = new File([text], "synthetic.csv", { type: "text/csv" });
  vi.spyOn(f, "slice").mockReturnValue({ size: f.size, text: async () => text } as Blob); return f;
}
it("detects common vendor headers without guessing ambiguous columns", () => {
  for (const headers of [["Source IP", "Destination IP", "Destination Port", "Protocol", "Event Count"],
    ["src_ip", "dest_ip", "dest_port", "transport", "_time"], ["srcip", "dstip", "dstport", "proto", "policyid", "srcintf"]]) {
    const mapping = detectMapping(headers); expect(mapping.source).toBe(headers[0]); expect(mapping.destination).toBe(headers[1]); expect(mapping.port).toBe(headers[2]); expect(mapping.protocol).toBe(headers[3]);
  }
  expect(detectMapping(["src", "srcip"]).source).toBeUndefined();
});
it("previews at most 20 rows, handles quoting/BOM, and rejects mapping-unsafe headers", () => {
  const preview = parsePreview('\uFEFFsrc,dst,port,proto,note\r\n192.0.2.8,198.51.100.8,443,tcp,"hello,""world""\nnext"\r\n', true);
  expect(preview.headers[0]).toBe("src"); expect(preview.rows[0][4]).toBe('hello,"world"\nnext');
  expect(parsePreview("a,b\n" + "x,y\n".repeat(30), true).rows).toHaveLength(20);
  expect(() => parsePreview("src,src\nx,y", true)).toThrow(); expect(() => parsePreview('a,b\n"bad', true)).toThrow();
  expect(parsePreview('a,b\nx,y\n"partial', false).rows).toEqual([["x", "y"]]);
});
it("viewer sees stored masked results, uncertainty and export but no upload or delete", async () => {
  const openRule = vi.fn(); render(<PolicyOfflineTab policy="p1" openRule={openRule} />); await chooseAnalysis();
  expect(screen.queryByLabelText("SIEM CSV file")).not.toBeInTheDocument(); expect(screen.queryByRole("button", { name: "Delete stored analysis" })).not.toBeInTheDocument();
  expect(screen.getByText(/invalid IP address: 1/)).toBeInTheDocument(); expect(screen.getByText(/protocol unsupported: 1/)).toBeInTheDocument();
  expect(screen.getByText(/Coverage: 100%/)).toBeInTheDocument(); expect(screen.getByText(/No traffic in this sample/)).toHaveTextContent("LOW");
  expect(screen.getByText(/OBJECT-BRAVO/)).toBeInTheDocument(); expect(screen.getByRole("button", { name: "Export results CSV" })).toBeEnabled();
  fireEvent.click(screen.getByRole("button", { name: "1 · RULE-ALPHA" })); expect(openRule).toHaveBeenCalledWith("r1");
  const csv = offlineResultsCsv({ ...result, fileName: "=synthetic-formula", uploader: " +synthetic-formula" });
  expect(csv).toContain("'=synthetic-formula"); expect(csv).toContain("' +synthetic-formula"); expect(csv).toContain("rules.0.narrowing.coveragePercent"); expect(csv).toContain("parseStats.0.statReason");
});
it("admin confirms mapping, sees progress, uploads once and deletes the stored analysis", async () => {
  vi.mocked(listOfflineAnalyses).mockResolvedValue({ analyses: [result], canManage: true });
  let resolve!: (value: { id: string }) => void;
  vi.mocked(uploadOfflineAnalysis).mockReturnValue(new Promise(r => { resolve = r; }));
  render(<PolicyOfflineTab policy="p1" openRule={vi.fn()} />);
  fireEvent.change(await screen.findByLabelText("SIEM CSV file"), { target: { files: [file("src,dst,dstport,proto\n192.0.2.8,198.51.100.8,443,tcp\n")] } });
  await screen.findByRole("table", { name: "CSV preview, first 20 rows" });
  expect(screen.getByRole("button", { name: "Upload and analyze" })).toBeDisabled();
  fireEvent.click(screen.getByLabelText(/I confirm the mapping/)); fireEvent.click(screen.getByRole("button", { name: "Upload and analyze" }));
  expect(await screen.findByText(/Processing/)).toBeInTheDocument(); expect(uploadOfflineAnalysis).toHaveBeenCalledTimes(1);
  expect(uploadOfflineAnalysis).toHaveBeenCalledWith("p1", expect.any(File), { source: "src", destination: "dst", port: "dstport", protocol: "proto" });
  resolve({ id: "a1" }); await screen.findByText("Sample summary");
  expect(screen.queryByRole("table", { name: "CSV preview, first 20 rows" })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Delete stored analysis" })); await waitFor(() => expect(deleteOfflineAnalysis).toHaveBeenCalledWith("a1"));
  await waitFor(() => expect(screen.queryByText("Sample summary")).not.toBeInTheDocument());
});
it("invalid mapping stays disabled and failed uploads never show a fabricated result", async () => {
  vi.mocked(listOfflineAnalyses).mockResolvedValue({ analyses: [], canManage: true });
  vi.mocked(uploadOfflineAnalysis).mockRejectedValue({ status: 400, body: { error: "CSV_ROW_LIMIT: maximum 2000000 rows" } });
  render(<PolicyOfflineTab policy="p1" openRule={vi.fn()} />);
  const input = await screen.findByLabelText("SIEM CSV file");
  fireEvent.change(input, { target: { files: [file("src,dst,unknown,proto\nx,y,z,tcp\n")] } });
  await screen.findByRole("table", { name: "CSV preview, first 20 rows" }); expect(screen.getByLabelText(/I confirm the mapping/)).toBeDisabled();
  fireEvent.change(input, { target: { files: [file("src,dst,dstport,proto\n192.0.2.8,198.51.100.8,443,tcp\n")] } });
  await waitFor(() => expect(screen.getByLabelText(/I confirm the mapping/)).toBeEnabled());
  fireEvent.click(screen.getByLabelText(/I confirm the mapping/)); fireEvent.click(screen.getByRole("button", { name: "Upload and analyze" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("CSV_ROW_LIMIT"); expect(screen.queryByText("Sample summary")).not.toBeInTheDocument();
});
