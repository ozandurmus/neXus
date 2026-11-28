import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";

import { SessionContext, type SessionInfo } from "../src/auth/SessionContext";
import type {
  LogCatalogDetailAnswer,
  LogCatalogFreezeAnswer,
  LogCatalogListAnswer,
  LogDetectionSettings,
} from "../src/auth/adminApi";
import { LogDetectionCatalogPanel } from "../src/components/settings/LogDetectionCatalogPanel";
import { AdministrationScreen } from "../src/screens/AdministrationScreen";
import { m3Theme } from "../src/theme/m3Theme";

/**
 * Template Catalog & P2 log detection admin panel (LOG_DETECTION_ENGINE_DESIGN_2026_11_24.md sections 6.5, 9 and 10).
 * Synthetic masked state only: opaque catalog ids, digests and vocabulary counts, never log lines or unmasked identities.
 */
const api = vi.hoisted(() => ({
  getSettings: vi.fn(),
  saveSettings: vi.fn(),
  listCatalogs: vi.fn(),
  getDetail: vi.fn(),
  startBuild: vi.fn(),
  freeze: vi.fn(),
}));

vi.mock("../src/auth/adminApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../src/auth/adminApi")>();
  return {
    ...actual,
    getLogDetectionSettings: api.getSettings,
    saveLogDetectionSettings: api.saveSettings,
    listLogCatalogs: api.listCatalogs,
    getLogCatalogDetail: api.getDetail,
    startLogCatalogBuild: api.startBuild,
    freezeLogCatalog: api.freeze,
  };
});

const storedSettings: LogDetectionSettings = {
  enabled: true,
  shadow_until: null,
  shadow: false,
  p2_enabled: false,
  p2_runs: false,
  updated_at: "2026-11-25T10:00:00Z",
  updated_by: "recorded",
};

const storedCatalogs: LogCatalogListAnswer = {
  active: "cat-1",
  note: "only a FROZEN version is matched online, and only while the P2 switch is on",
  build: { state: "IDLE" },
  catalogs: [
    {
      catalog_version: "cat-2",
      state: "DRAFT",
      previous_version: null,
      template_count: 148,
      digest: "b".repeat(64),
      created_at: "2026-11-26T08:00:00Z",
      frozen_at: null,
    },
    {
      catalog_version: "cat-1",
      state: "FROZEN",
      previous_version: null,
      template_count: 140,
      digest: "a".repeat(64),
      created_at: "2026-11-10T08:00:00Z",
      frozen_at: "2026-11-10T09:00:00Z",
    },
  ],
};

const detailAnswer: LogCatalogDetailAnswer = {
  catalog_version: "cat-2",
  state: "DRAFT",
  previous_version: "cat-1",
  manifest: {
    catalog_version: "cat-2",
    normaliser_version: "norm-p2-1",
    vocabulary_version: "vocab-1",
    masking_rule_set: "logmask-v8",
    marker_list_version: "markers-p1-1",
    drain: { depth: 4, similarity: 0.4 },
    training_window: { from: "2026-10-28", to: "2026-11-24", time_basis: "DEVICE_LOCAL" },
    chunks: 420,
    lines: 850000,
    unparsed_lines: 12,
    truncated: false,
    truncated_reason: null,
    template_count: 148,
  },
  class_counts: {
    cp_messages: { MARKER: 4, UNCLASSED: 96 },
    pan_system: { VENDOR_SEVERITY: 48 },
  },
  diff: {
    from_version: "cat-1",
    to_version: "cat-2",
    counts: { templates_from: 140, templates_to: 148, added: 10, removed: 2, merged: 1, split: 1, class_changes: 1 },
    normaliser: { from: "norm-p2-1", to: "norm-p2-1" },
    marker_list: { from: "markers-p1-1", to: "markers-p1-1" },
    masking_rule_set: { from: "logmask-v8", to: "logmask-v8" },
    split: [{ template_id: "t-oldsplit0001", source_key: "cp_messages", line_share: 0.0125, into: ["t-newa0001", "t-newb0002"], history: "UNKNOWN_BEFORE" }],
    merged: [{ template_id: "t-merged0003", source_key: "pan_system", line_share: 0.04, from: ["t-olda0001", "t-oldb0002"] }],
    class_changes: [{ template_id: "t-cls0004", class_from: "UNCLASSED", class_to: "MARKER", s_from: 0.3, s_to: 0.8, marker_from: null, marker_to: "hw.edac.ce" }],
  },
};

function mountPanel(session: SessionInfo | null = null) {
  return render(
    <ThemeProvider theme={m3Theme}>
      {session ? (
        <SessionContext.Provider value={session}>
          <LogDetectionCatalogPanel />
        </SessionContext.Provider>
      ) : (
        <LogDetectionCatalogPanel />
      )}
    </ThemeProvider>,
  );
}

describe("LogDetectionCatalogPanel", () => {
  beforeEach(() => {
    api.getSettings.mockReset().mockResolvedValue(storedSettings);
    api.listCatalogs.mockReset().mockResolvedValue(storedCatalogs);
    api.saveSettings.mockReset().mockImplementation(async (patch) => ({
      ...storedSettings,
      ...patch,
      p2_runs: Boolean((patch.enabled ?? storedSettings.enabled) && (patch.p2_enabled ?? storedSettings.p2_enabled)),
    }));
    api.getDetail.mockReset().mockResolvedValue(detailAnswer);
    api.startBuild.mockReset().mockResolvedValue({
      state: "RUNNING",
      from: "2026-10-28",
      to: "2026-11-24",
      started_at: "2026-11-26T12:00:00Z",
    });
    api.freeze.mockReset().mockImplementation(async (version: string): Promise<LogCatalogFreezeAnswer> => ({
      catalog_version: version,
      state: "FROZEN",
      previous_version: "cat-1",
      digest: "b".repeat(64),
      diff: detailAnswer.diff,
    }));
  });

  afterEach(() => {
    window.history.replaceState(null, "", "/");
    vi.unstubAllGlobals();
  });

  it("renders P1 and P2 switches, active catalog status, and DRAFT/FROZEN catalog versions", async () => {
    mountPanel();
    expect(await screen.findByText("Active catalog cat-1")).toBeInTheDocument();
    expect(screen.getByText("P1 active")).toBeInTheDocument();
    expect(screen.getByLabelText(/Enable log detection \(P1/)).toBeChecked();
    expect(screen.getByLabelText(/Enable statistical detection \(P2/)).not.toBeChecked();
    expect(screen.getByTestId("catalog-row-cat-2")).toHaveTextContent("DRAFT");
    expect(screen.getByTestId("catalog-row-cat-2")).toHaveTextContent("148 templates");
    expect(screen.getByTestId("catalog-row-cat-1")).toHaveTextContent("FROZEN");
    expect(screen.getByTestId("catalog-row-cat-1")).toHaveTextContent("ACTIVE");
    // Only a DRAFT catalog offers Freeze; a FROZEN catalog never can be frozen or edited again.
    expect(within(screen.getByTestId("catalog-row-cat-2")).getByRole("button", { name: "Freeze cat-2" })).toBeInTheDocument();
    expect(within(screen.getByTestId("catalog-row-cat-1")).queryByRole("button", { name: /Freeze/ })).toBeNull();
    expect(screen.getByRole("button", { name: "Save settings" })).toBeDisabled();
  });

  it("toggles p2_enabled and saves only the changed fields via PUT /api/v2/config/log-detection", async () => {
    mountPanel();
    const p2Switch = await screen.findByLabelText(/Enable statistical detection \(P2/);
    fireEvent.click(p2Switch);
    fireEvent.click(screen.getByRole("button", { name: "Save settings" }));
    await waitFor(() => expect(api.saveSettings).toHaveBeenCalledWith({ p2_enabled: true }));
    expect(await screen.findByText("Log detection settings saved.")).toBeInTheDocument();
    expect(screen.getByText("P2 active")).toBeInTheDocument();
  });

  it("surfaces the server's 409 refusal when P2 is switched on before the P1 shadow exit check", async () => {
    api.getSettings.mockResolvedValue({ ...storedSettings, shadow: true, shadow_until: "2026-12-08T10:00:00Z" });
    api.saveSettings.mockRejectedValue({
      status: 409,
      body: { code: "LOG_DETECTION_P2_BEFORE_P1_EXIT", message: "P2 cannot start while the P1 shadow period runs." },
    });
    mountPanel();
    const p2Switch = await screen.findByLabelText(/Enable statistical detection \(P2/);
    expect(screen.getByTestId("log-detection-p2-row")).toHaveTextContent(/P1 shadow period runs until 2026-12-08/);
    fireEvent.click(p2Switch);
    fireEvent.click(screen.getByRole("button", { name: "Save settings" }));
    expect(await screen.findByText("P2 cannot start while the P1 shadow period runs.")).toBeInTheDocument();
    expect(screen.getByText("LOG_DETECTION_P2_BEFORE_P1_EXIT")).toBeInTheDocument();
  });

  it("starts an offline DRAFT catalog build with from and to dates and displays build counts", async () => {
    api.listCatalogs
      .mockResolvedValueOnce(storedCatalogs)
      .mockResolvedValueOnce({
        ...storedCatalogs,
        build: { state: "DONE", reason: "OK", catalog_version: "cat-3", templates: 152, chunks_read: 430, chunks_skipped: 2 },
      });
    mountPanel();
    await screen.findByText("Active catalog cat-1");
    fireEvent.change(screen.getByLabelText("Training from"), { target: { value: "2026-10-28" } });
    fireEvent.change(screen.getByLabelText("Training to"), { target: { value: "2026-11-24" } });
    fireEvent.click(screen.getByRole("button", { name: "Build draft catalog" }));

    await waitFor(() => expect(api.startBuild).toHaveBeenCalledWith({ from: "2026-10-28", to: "2026-11-24" }));
    expect(await screen.findByText("Catalog build started for 2026-10-28 to 2026-11-24.")).toBeInTheDocument();
    expect(screen.getByTestId("catalog-build-status")).toHaveTextContent("DONE");
    expect(screen.getByTestId("catalog-build-status")).toHaveTextContent("cat-3");
    expect(screen.getByTestId("catalog-build-status")).toHaveTextContent("152 templates · 430 chunks read · 2 skipped");
  });

  it("inspects a catalog version manifest, class counts and diff, and freezes a DRAFT version", async () => {
    mountPanel();
    await screen.findByText("Active catalog cat-1");
    fireEvent.click(within(screen.getByTestId("catalog-row-cat-2")).getByRole("button", { name: "Inspect" }));

    await waitFor(() => expect(api.getDetail).toHaveBeenCalledWith("cat-2", undefined));
    expect(await screen.findByTestId("catalog-manifest")).toHaveTextContent("Window 2026-10-28 to 2026-11-24 (DEVICE_LOCAL)");
    expect(screen.getByTestId("catalog-manifest")).toHaveTextContent("normaliser norm-p2-1");
    expect(screen.getByTestId("catalog-class-counts")).toHaveTextContent("4 MARKER, 96 UNCLASSED");
    expect(screen.getByTestId("catalog-diff-view")).toHaveTextContent("Diff: cat-1 → cat-2 · templates 140 → 148");
    expect(screen.getByTestId("catalog-diff-view")).toHaveTextContent("added 10");
    expect(screen.getByTestId("catalog-diff-view")).toHaveTextContent("split 1");
    expect(screen.getByTestId("catalog-diff-view")).toHaveTextContent("UNKNOWN_BEFORE");

    // Freeze the DRAFT version
    api.listCatalogs.mockResolvedValueOnce({
      ...storedCatalogs,
      active: "cat-2",
      catalogs: storedCatalogs.catalogs.map((c) => (c.catalog_version === "cat-2" ? { ...c, state: "FROZEN", frozen_at: "2026-11-26T12:05:00Z" } : c)),
    });
    fireEvent.click(within(screen.getByTestId("catalog-row-cat-2")).getByRole("button", { name: "Freeze cat-2" }));
    await waitFor(() => expect(api.freeze).toHaveBeenCalledWith("cat-2"));
    expect(await screen.findByText("Catalog cat-2 frozen and audited.")).toBeInTheDocument();
    expect(screen.getByText("Active catalog cat-2")).toBeInTheDocument();
  });

  it("sends enabled alongside shadow_days when resetting or ending shadow alone so the backend accepts the write", async () => {
    api.getSettings.mockResolvedValue({ ...storedSettings, shadow: true, shadow_until: "2026-12-08T10:00:00Z" });
    api.saveSettings.mockResolvedValue({ ...storedSettings, shadow: false, shadow_until: null });
    mountPanel();
    const shadowInput = await screen.findByLabelText(/Reset shadow days/);
    fireEvent.change(shadowInput, { target: { value: "0" } });
    fireEvent.click(screen.getByRole("button", { name: "Save settings" }));
    await waitFor(() => expect(api.saveSettings).toHaveBeenCalledWith({ enabled: true, shadow_days: 0 }));
    expect(await screen.findByText("Log detection settings saved.")).toBeInTheDocument();
  });

  it("does not show green P2 active when no catalog is frozen or while shadow is active", async () => {
    api.getSettings.mockResolvedValue({ ...storedSettings, p2_enabled: true, p2_runs: true, shadow: false });
    api.listCatalogs.mockResolvedValue({ ...storedCatalogs, active: null, catalogs: [storedCatalogs.catalogs[0]] });
    const { unmount } = mountPanel();
    expect(await screen.findByText("P2 waiting (no frozen catalog)")).toBeInTheDocument();
    expect(screen.queryByText("P2 active")).toBeNull();
    expect(screen.getByTestId("log-detection-p2-row")).toHaveTextContent(/NO_FROZEN_CATALOG/);
    unmount();

    api.getSettings.mockResolvedValue({ ...storedSettings, p2_enabled: true, p2_runs: true, shadow: true, shadow_until: "2026-12-08T10:00:00Z" });
    api.listCatalogs.mockResolvedValue(storedCatalogs);
    mountPanel();
    expect(await screen.findByText("P2 active (shadow)")).toBeInTheDocument();
    expect(screen.queryByText("P2 active")).toBeNull();
  });

  it("clears stale catalog detail on error and ignores out-of-order async responses", async () => {
    let resolveCat1!: (v: LogCatalogDetailAnswer) => void;
    let resolveCat2!: (v: LogCatalogDetailAnswer) => void;
    const cat1Detail: LogCatalogDetailAnswer = {
      ...detailAnswer,
      catalog_version: "cat-1",
      state: "FROZEN",
      manifest: { ...detailAnswer.manifest!, catalog_version: "cat-1", lines: 111111 },
    };
    const cat2Detail: LogCatalogDetailAnswer = {
      ...detailAnswer,
      catalog_version: "cat-2",
      state: "DRAFT",
      manifest: { ...detailAnswer.manifest!, catalog_version: "cat-2", lines: 222222 },
    };
    api.getDetail
      .mockImplementationOnce(() => new Promise<LogCatalogDetailAnswer>((r) => { resolveCat1 = r; }))
      .mockImplementationOnce(() => new Promise<LogCatalogDetailAnswer>((r) => { resolveCat2 = r; }))
      .mockRejectedValueOnce({ status: 500, body: { code: "CATALOG_READ_FAILED", message: "Catalog detail read failed." } });

    mountPanel();
    await screen.findByText("Active catalog cat-1");

    // Start inspecting cat-1, then switch to cat-2 before cat-1 completes; cat-2 finishes first, then cat-1 resolves late.
    fireEvent.click(within(screen.getByTestId("catalog-row-cat-1")).getByRole("button", { name: "Inspect" }));
    fireEvent.click(within(screen.getByTestId("catalog-row-cat-2")).getByRole("button", { name: "Inspect" }));
    resolveCat2(cat2Detail);
    expect(await screen.findByTestId("catalog-manifest")).toHaveTextContent("222222 lines");
    resolveCat1(cat1Detail);
    await waitFor(() => expect(screen.getByTestId("catalog-manifest")).toHaveTextContent("222222 lines"));
    expect(screen.getByTestId("catalog-manifest")).not.toHaveTextContent("111111 lines");

    // Clicking Inspect on cat-1 when it fails must not keep cat-2's stale detail rendered under Catalog cat-1.
    fireEvent.click(within(screen.getByTestId("catalog-row-cat-1")).getByRole("button", { name: "Inspect" }));
    expect(await screen.findByText("Catalog detail read failed.")).toBeInTheDocument();
    expect(screen.queryByTestId("catalog-manifest")).toBeNull();
  });

  it("enforces RBAC gating: renders RestrictedPanel on 403 and read-only view without write grant", async () => {
    api.getSettings.mockRejectedValueOnce({ status: 403, body: { error: "ACTION_REFUSED" } });
    const { unmount } = mountPanel();
    expect(await screen.findByText(/This account can not view or change it/)).toBeInTheDocument();
    unmount();

    api.getSettings.mockResolvedValue(storedSettings);
    mountPanel({
      displayName: "Synthetic viewer",
      roleTokens: [],
      permissions: ["Devices", "Operations"],
      canReadAdministration: false,
      onSignOut: () => {},
    });
    expect(await screen.findByTestId("log-detection-readonly")).toHaveTextContent(/Read-only/);
    expect(screen.getByLabelText(/Enable log detection \(P1/)).toBeDisabled();
    expect(screen.getByLabelText(/Enable statistical detection \(P2/)).toBeDisabled();
    expect(screen.queryByRole("button", { name: "Save settings" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Build draft catalog" })).toBeNull();
    expect(screen.queryByRole("button", { name: /Freeze/ })).toBeNull();
  });

  it("is wired into Administration > Notifications and logging and stays locked without fetching for non-admin sessions", async () => {
    window.history.replaceState(null, "", "/?screen=administration&tab=platform&section=log-detection");
    render(
      <ThemeProvider theme={m3Theme}>
        <SessionContext.Provider value={{ displayName: "Synthetic viewer", roleTokens: [], permissions: ["Devices"], canReadAdministration: false, onSignOut: () => {} }}>
          <AdministrationScreen />
        </SessionContext.Provider>
      </ThemeProvider>,
    );
    expect(screen.getByRole("heading", { level: 2, name: "Notifications and logging" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Template catalog" })).toHaveAttribute("aria-selected", "true");
    expect(await screen.findByText(/This account can not view or change it/)).toBeInTheDocument();
    expect(document.querySelector("section#admin-section-log-detection[data-locked='true']")).not.toBeNull();
    expect(api.getSettings).not.toHaveBeenCalled();
    expect(api.listCatalogs).not.toHaveBeenCalled();
  });

  it("renders the diff of a Compare from response that carries no top-level catalog_version", async () => {
    api.getDetail.mockReset().mockImplementation(async (_version: string, from?: string) =>
      from ? { diff: { ...detailAnswer.diff, from_version: from, to_version: "cat-2" } } : detailAnswer);
    mountPanel();
    await screen.findByTestId("catalog-row-cat-2");
    fireEvent.click(within(screen.getByTestId("catalog-row-cat-2")).getByRole("button", { name: "Inspect" }));
    expect(await screen.findByTestId("catalog-diff-view")).toBeInTheDocument();
    fireEvent.mouseDown(screen.getByRole("combobox", { name: /Compare from/ }));
    fireEvent.click(await screen.findByRole("option", { name: "cat-1" }));
    await waitFor(() => expect(api.getDetail).toHaveBeenCalledWith("cat-2", "cat-1"));
    expect(await screen.findByTestId("catalog-diff-view")).toHaveTextContent("Diff: cat-1 → cat-2");
  });
});
