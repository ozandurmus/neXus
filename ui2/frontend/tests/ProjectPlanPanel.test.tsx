import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { ProjectPlanPanel } from "../src/screens/ProjectPlanPanel";
import type { ProjectPlanView } from "../src/auth/adminApi";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status });
}

function withTheme(node: React.ReactElement) {
  return <ThemeProvider theme={m3Theme}>{node}</ThemeProvider>;
}

const BASE_PLAN: ProjectPlanView = {
  schema_version: "1.0",
  generated_at: "2026-09-14T00:00:00Z",
  current_build: "build_1",
  current_track: "track_a",
  progress_contract: "weighted acceptance-criterion completion",
  overall_progress_percent: 42.5,
  current_track_progress_percent: 66.7,
  tracks: [
    {
      id: "track_a",
      title: "Track A",
      theme: "SEE",
      status: "in_progress",
      weight: 2,
      progress_percent: 66.7,
      done_features: 1,
      feature_count: 2,
      features: [
        { id: "f1", title: "Feature One", status: "done", introduced: "0.5", target: null, weight: 1, summary: "s", why: "w", evidence: null, progress_percent: 100 },
        { id: "f2", title: "Feature Two", status: "in_progress", introduced: "0.6", target: null, weight: 1, summary: "s2", why: "w2", evidence: null, progress_percent: 33.3 },
      ],
    },
  ],
  now_next: {
    now: { build: "build_1", title: "Build One", status: "in_progress", goal: "Do the thing" },
    next: { build: "build_2", title: "Build Two", status: "planned" },
    upcoming: [{ build: "build_3", title: "Build Three", status: "blocked" }],
  },
  roadmap_notes: ["Note about scope."],
  backlog: [
    { id: "b1", category: "Security", title: "Security debt item", status: "planned", priority: "P0", target: "soon", note: "n1" },
    { id: "b2", category: "UX", title: "UX debt item", status: "in_progress", priority: "P2", target: "later", note: null },
    { id: "b3", category: "Architecture", title: "Architecture debt item", status: "planned", priority: "P3", target: null, note: null },
    { id: "b4", category: "Architecture", title: "Done architecture item", status: "done", priority: "P3", target: null, note: null },
  ],
  backlog_counts: { planned: 2, in_progress: 1, done: 1 },
  completed_features: [
    { id: "f1", title: "Feature One", status: "done", introduced: "0.5", target: null, weight: 1, summary: "Feature one summary", why: "Feature one why", evidence: "Evidence one", progress_percent: 100 },
  ],
  build_history: [
    { build: "build_1", status: "in_progress", title: "Build One", summary: "Summary one", detail: null },
  ],
  archived_build_count: 5,
  metadata_warnings: ["roadmap.json is missing or unreadable; using an empty default."],
};

function renderWithPlan(plan: ProjectPlanView) {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, plan)));
  return render(withTheme(<ProjectPlanPanel />));
}

describe("ProjectPlanPanel hero numbers", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("shows the declared roadmap completion, current track completion, and open backlog count", async () => {
    renderWithPlan(BASE_PLAN);

    await waitFor(() => expect(screen.getByLabelText("Declared roadmap completion percent")).toBeInTheDocument());
    expect(screen.getByLabelText("Declared roadmap completion percent")).toHaveTextContent("42.5%");
    expect(screen.getByLabelText("Current track completion percent")).toHaveTextContent("66.7%");

    // Only b4 is closed; the three planned/in-progress rows remain open.
    expect(screen.getByLabelText("Open backlog count")).toHaveTextContent("3");
  });

  it("captions the roadmap completion bar as acceptance-criterion completion, not a time estimate", async () => {
    renderWithPlan(BASE_PLAN);
    await waitFor(() => expect(screen.getByText(/acceptance-criterion completion, not a time estimate/)).toBeInTheDocument());
  });

  it("shows one chip per backlog status", async () => {
    renderWithPlan(BASE_PLAN);
    await waitFor(() => expect(screen.getByLabelText("Open backlog count")).toBeInTheDocument());
    const openBacklogCard = screen.getByLabelText("Open backlog count").closest(".MuiCard-root") as HTMLElement;
    expect(within(openBacklogCard).getByText("Planned")).toBeInTheDocument();
    expect(within(openBacklogCard).getByText("In Progress")).toBeInTheDocument();
    expect(within(openBacklogCard).getByText("Done")).toBeInTheDocument();
  });
});

describe("ProjectPlanPanel backlog grouping and expansion rule", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("expands a category by default when it holds a P0 row", async () => {
    renderWithPlan(BASE_PLAN);
    await waitFor(() => expect(screen.getByText("Security")).toBeInTheDocument());
    expect(screen.getByText("Security debt item")).toBeInTheDocument();
  });

  it("expands a category by default when it holds an in_progress row", async () => {
    renderWithPlan(BASE_PLAN);
    await waitFor(() => expect(screen.getByText("UX")).toBeInTheDocument());
    expect(screen.getByText("UX debt item")).toBeInTheDocument();
  });

  it("collapses a category by default when it holds neither a P0 nor an in_progress row, and expands on click", async () => {
    renderWithPlan(BASE_PLAN);
    await waitFor(() => expect(screen.getByLabelText("Architecture")).toBeInTheDocument());
    expect(screen.queryByText("Architecture debt item")).toBeNull();

    fireEvent.click(screen.getByLabelText("Architecture"));

    await waitFor(() => expect(screen.getByText("Architecture debt item")).toBeInTheDocument());
    expect(screen.getByText("Done architecture item")).toBeInTheDocument();
  });

  it("collapses an expanded-by-default category back on a second click", async () => {
    renderWithPlan(BASE_PLAN);
    await waitFor(() => expect(screen.getByText("Security debt item")).toBeInTheDocument());

    fireEvent.click(screen.getByLabelText("Security"));

    await waitFor(() => expect(screen.queryByText("Security debt item")).toBeNull());
  });
});

describe("ProjectPlanPanel warning block", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("shows the metadata warning block when warnings are present", async () => {
    renderWithPlan(BASE_PLAN);
    await waitFor(() => expect(screen.getByText("Roadmap metadata warning")).toBeInTheDocument());
    expect(screen.getByText("roadmap.json is missing or unreadable; using an empty default.")).toBeInTheDocument();
  });

  it("shows the roadmap notes regardless of the warning block", async () => {
    renderWithPlan(BASE_PLAN);
    await waitFor(() => expect(screen.getByText("Note about scope.")).toBeInTheDocument());
  });

  it("shows no warning block when there are no metadata warnings", async () => {
    renderWithPlan({ ...BASE_PLAN, metadata_warnings: [] });
    await waitFor(() => expect(screen.getByText("Note about scope.")).toBeInTheDocument());
    expect(screen.queryByText("Roadmap metadata warning")).toBeNull();
  });
});

describe("ProjectPlanPanel other sections", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("marks the current track and shows its feature map on expansion", async () => {
    renderWithPlan(BASE_PLAN);
    await waitFor(() => expect(screen.getByText("Track A")).toBeInTheDocument());
    expect(screen.getByText("Current")).toBeInTheDocument();
    // "Feature One" also appears once already, in the always-visible
    // Completed features section below -- the track's own feature map only
    // adds a second occurrence once expanded.
    expect(screen.getAllByText("Feature One")).toHaveLength(1);
    expect(screen.queryByText("Feature Two")).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "Show feature map" }));

    await waitFor(() => expect(screen.getAllByText("Feature One")).toHaveLength(2));
    expect(screen.getByText("Feature Two")).toBeInTheDocument();
  });

  it("shows completed features with summary, why it matters and validated evidence", async () => {
    renderWithPlan(BASE_PLAN);
    await waitFor(() => expect(screen.getByText("Feature one summary")).toBeInTheDocument());
    expect(screen.getByText("Feature one why")).toBeInTheDocument();
    expect(screen.getByText("Evidence one")).toBeInTheDocument();
  });

  it("shows build history with the archived count", async () => {
    renderWithPlan(BASE_PLAN);
    await waitFor(() => expect(screen.getByText("Summary one")).toBeInTheDocument());
    expect(screen.getByText("5 builds archived (all historical lines)")).toBeInTheDocument();
  });

  it("shows an error state with a retry action when the fetch fails", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(500, { error: "SOMETHING_WRONG" })));
    render(withTheme(<ProjectPlanPanel />));
    await waitFor(() => expect(screen.getByText("SOMETHING_WRONG")).toBeInTheDocument());
    expect(screen.getByRole("button", { name: "Retry" })).toBeInTheDocument();
  });
});


describe("Java product provenance", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("separates converted lessons, preserves outstanding validation and refreshes the source", async () => {
    renderWithPlan({ ...BASE_PLAN,
      current_product_build: "java-build",
      source_metadata: { revision: "snapshot-1", reviewed_at: "2026-09-15", reviewed_current_build: "java-build", freshness: "STALE", update_policy: "Reviewed read-only snapshot" },
      backlog: [{ ...BASE_PLAN.backlog[0], status: "automated_validated", classification: "java_debt" }],
      converted_lessons: [{ id: "lesson", title: "Historical identity lesson", source_id: "python-source", source_status: "done", java_application: "Verify opaque identity in Java", target: "java-debt" }],
    });
    await waitFor(() => expect(screen.getByText(/Source revision: snapshot-1/)).toBeInTheDocument());
    expect(screen.getByText(/Freshness: STALE/)).toBeInTheDocument();
    expect(screen.getByText(/Deployed software version: UNKNOWN/)).toBeInTheDocument();
    expect(screen.getByText("Converted Python lessons — historical / derived")).toBeInTheDocument();
    expect(screen.getByText("Verify opaque identity in Java")).toBeInTheDocument();
    expect(screen.getByLabelText("Open backlog count")).toHaveTextContent("1");
    fireEvent.click(screen.getByRole("button", { name: "Refresh project plan" }));
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(2));
  });
});
