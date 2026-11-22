import { render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  getLifecycleAdvisories,
  getPolicyInstallStatus,
  getReportCatalogue,
  listReportRuns,
  resetSharedApiCache,
  type LifecycleAdvisory,
  type PolicyInstallStatusRow,
  type ReportRunView,
} from "../src/auth/adminApi";
import { EstateCasesScreen } from "../src/estate/EstateCasesScreen";
import { LIFECYCLE_ADVISORY_CAP, EstateLifecycleScreen } from "../src/estate/EstateLifecycleScreen";
import { EstatePolicyInstalls } from "../src/estate/EstatePolicyInstalls";
import { POLICY_INSTALLS_SHOWN } from "../src/estate/estatePolicyInstallsModel";
import { REPORT_LIST_CAP } from "../src/estate/estateReportsModel";
import { EstateReportsScreen } from "../src/estate/EstateReportsScreen";
import { CASES_SHOWN } from "../src/estate/estateCasesModel";

// Cap+1 checks: every Estate screen that reuses MoreFooter must print the true remainder ("+1 more <noun>").
vi.mock("../src/auth/adminApi", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../src/auth/adminApi")>()),
  getLifecycleAdvisories: vi.fn(),
  getReportCatalogue: vi.fn(),
  listReportRuns: vi.fn(),
  getPolicyInstallStatus: vi.fn(),
}));

const AS_OF = "2026-11-21T08:00:00.000Z";
const NOW_ISO = new Date(Date.now() - 3_600_000).toISOString();

beforeEach(() => {
  vi.mocked(getLifecycleAdvisories).mockReset();
  vi.mocked(getReportCatalogue).mockReset();
  vi.mocked(listReportRuns).mockReset();
  vi.mocked(getPolicyInstallStatus).mockReset();
  resetSharedApiCache();
});

describe("Estate screens reuse MoreFooter at cap+1", () => {
  it("Lifecycle: 51 synthetic advisories with a cap of 50 print +1 more advisories", async () => {
    const advisories = Array.from({ length: LIFECYCLE_ADVISORY_CAP + 1 }, (_, i) => ({
      advisory_id: `paloalto:SYN-${i}`,
      source: "paloalto",
      title: `Synthetic advisory ${i}`,
      severity: "HIGH",
      cvss_score: 7.5,
      cve_ids: [],
      exploited: false,
      exploited_since: null,
      published_on: "2026-11-01",
      updated_on: "2026-11-10",
      source_url: null,
      parse_state: "PARSED",
      parse_note: null,
      devices: { affected: 0, fixed: 0, not_affected: 1, not_evaluable: 0, pending: 0 },
    })) as LifecycleAdvisory[];
    vi.mocked(getLifecycleAdvisories).mockResolvedValue({ as_of: AS_OF, advisories });
    render(<EstateLifecycleScreen />);

    expect(await screen.findByText("+1 more advisories")).toBeInTheDocument();
    expect(screen.getAllByTestId("estate-lifecycle-row")).toHaveLength(LIFECYCLE_ADVISORY_CAP);
  });

  it("Reports: 9 synthetic runs with a cap of 8 print +1 more runs", async () => {
    vi.mocked(getReportCatalogue).mockResolvedValue({
      templates: [],
      frameworks: [],
      permissions: { masked: true, raw_view: false },
      retention_days: 7,
    } as never);
    const runs = Array.from({ length: REPORT_LIST_CAP + 1 }, (_, i) => ({
      run_id: `run-${i}`,
      template_id: "TPL-SYN",
      title: `Synthetic pack ${i}`,
      state: "SUCCEEDED",
      projection: "MASKED",
      format: "pdf",
      parameters: {},
      data_as_of: { planes: [] },
      plaintext_bytes: null,
      error_code: null,
      requested_at: NOW_ISO,
      finished_at: NOW_ISO,
    })) as unknown as ReportRunView[];
    vi.mocked(listReportRuns).mockResolvedValue({ runs } as never);
    render(<EstateReportsScreen />);

    expect(await screen.findByText("+1 more runs")).toBeInTheDocument();
  });

  it("Cases: 26 synthetic cases with a cap of 25 print +1 more cases", async () => {
    const cases = Array.from({ length: CASES_SHOWN + 1 }, (_, i) => ({
      case_id: `case-syn-${i}`,
      title: `Synthetic capture ${i}`,
      created_at: NOW_ISO,
      expires_at: new Date(Date.now() + 86_400_000).toISOString(),
      state: "ANALYZED",
      total_bytes: 1024,
      capture_count: 1,
      payload_zeroing: false,
      latest_job_id: null,
    }));
    const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes("/api/v2/packet-analysis/cases")) return json({ cases, permissions: {}, limits: { max_file_bytes: 1, max_store_bytes: 1, used_bytes: 0, retention_days: 7 } });
      if (url.includes("/api/v2/support-bundles")) return json({ support_bundles: [] });
      return json({});
    }));
    render(<EstateCasesScreen />);

    await waitFor(() => expect(screen.getByText("+1 more cases")).toBeInTheDocument());
    vi.unstubAllGlobals();
  });

  it("Policy installs: 13 synthetic rows with a cap of 12 print +1 more policy installs", async () => {
    const rows = Array.from({ length: POLICY_INSTALLS_SHOWN + 1 }, (_, i) => ({
      row_id: `row-${i}`,
      device_id: null,
      hostname: `FW-SYN-${i}`,
      context: "synthetic",
      vendor: "paloalto",
      container_id: "c",
      container_name: "Synthetic container",
      package_id: "p",
      package_name: "Synthetic package",
      state: "INSTALLED",
      ha_parity: null,
      policy_name: null,
      installed_at: null,
      last_change_at: null,
    })) as unknown as PolicyInstallStatusRow[];
    vi.mocked(getPolicyInstallStatus).mockResolvedValue({
      counts: {},
      total: POLICY_INSTALLS_SHOWN + 1,
      page: 1,
      page_size: POLICY_INSTALLS_SHOWN + 1,
      masked: true,
      contract_ref: "synthetic",
      rows,
    } as never);
    render(<EstatePolicyInstalls />);

    expect(await screen.findByText("+1 more policy installs")).toBeInTheDocument();
  });
});
