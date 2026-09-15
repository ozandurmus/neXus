import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { AuditScreen } from "../src/screens/AuditScreen";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status });
}

function withTheme(node: React.ReactElement) {
  return <ThemeProvider theme={m3Theme}>{node}</ThemeProvider>;
}

function mockFetch(handler: (url: string) => Response) {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = typeof input === "string" ? input : input.toString();
      return Promise.resolve(handler(url));
    }),
  );
}

const BASE_ENTRY = {
  audit_id: 1,
  occurred_at: "2026-09-15T08:00:00Z",
  table_name: "sessions",
  row_pk: "sess-1",
  operation: "INSERT",
  actor_fingerprint: "fp-1",
  action_id: "ui2.session.create",
  correlation_run_id: null,
};

describe("AuditScreen list", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("fetches GET /api/audit and renders entries, with no export affordance", async () => {
    mockFetch((url) => {
      if (url.startsWith("/api/audit/")) return jsonResponse(404, { error: "NOT_FOUND" });
      return jsonResponse(200, { entries: [BASE_ENTRY], next_cursor: null });
    });
    render(withTheme(<AuditScreen />));

    await waitFor(() => expect(screen.getByText("sessions")).toBeInTheDocument());
    expect(screen.getByText("row sess-1")).toBeInTheDocument();
    expect(screen.getByText("actor fp-1")).toBeInTheDocument();
    expect(screen.getByText("1 row shown")).toBeInTheDocument();
    expect(screen.queryByText(/export/i)).toBeNull();
    expect(screen.queryByText(/download/i)).toBeNull();
  });

  it("renders the server's explained refusal inline for an actor holding neither role token", async () => {
    mockFetch(() =>
      jsonResponse(403, {
        error: "ACTION_REFUSED",
        action_id: "ui2.audit.read_own",
        outcome: "DENIED",
        reason_code: "actor_not_in_required_group",
        decision_id: 501,
      }),
    );
    render(withTheme(<AuditScreen />));

    await waitFor(() => expect(screen.getByText("Audit trail refused")).toBeInTheDocument());
    expect(screen.getByText(/does not hold a role that can read the audit trail/)).toBeInTheDocument();
    // No list, no filters-driven table -- the refusal is the whole body.
    expect(screen.queryByText("No audit rows")).toBeNull();
  });

  it("shows Load more only while a next_cursor exists, and fetches the next page with it verbatim", async () => {
    mockFetch((url) => {
      if (url.startsWith("/api/audit/")) return jsonResponse(404, { error: "NOT_FOUND" });
      if (url.includes("cursor=cursor-abc")) {
        return jsonResponse(200, {
          entries: [{ ...BASE_ENTRY, audit_id: 2, row_pk: "sess-2" }],
          next_cursor: null,
        });
      }
      return jsonResponse(200, { entries: [BASE_ENTRY], next_cursor: "cursor-abc" });
    });
    render(withTheme(<AuditScreen />));

    await waitFor(() => expect(screen.getByRole("button", { name: "Load more" })).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "Load more" }));

    await waitFor(() => expect(screen.getByText("row sess-2")).toBeInTheDocument());
    expect(screen.getByText("row sess-1")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Load more" })).toBeNull();
  });
});

describe("AuditScreen detail", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("selecting a row fetches the detail and renders every field-projection state distinguishably", async () => {
    mockFetch((url) => {
      if (url.startsWith("/api/audit/42")) {
        return jsonResponse(200, {
          audit_id: 42,
          occurred_at: "2026-09-15T08:00:00Z",
          table_name: "sessions",
          row_pk: "sess-42",
          operation: "UPDATE",
          actor_fingerprint: "fp-42",
          action_id: "ui2.session.rotate",
          correlation_run_id: "run-1",
          before_fields: {},
          after_fields: {
            role_token: { state: "PRESENT", value: "security_admin" },
            revoked_at: { state: "NULL" },
            csrf_secret: { state: "REDACTED", tier: 1, reason: "session secret material" },
            legacy_note: { state: "ABSENT" },
            future_column: { state: "UNCLASSIFIED", table_name: "sessions", column_name: "future_column" },
          },
          change_states: { role_token: "CHANGED" },
        });
      }
      return jsonResponse(200, {
        entries: [{ ...BASE_ENTRY, audit_id: 42, row_pk: "sess-42", operation: "UPDATE" }],
        next_cursor: null,
      });
    });
    render(withTheme(<AuditScreen />));

    await waitFor(() => expect(screen.getByText("row sess-42")).toBeInTheDocument());
    fireEvent.click(screen.getByText("row sess-42"));

    await waitFor(() => expect(screen.getByText("security_admin")).toBeInTheDocument());
    expect(screen.getByText("session secret material")).toBeInTheDocument();
    expect(screen.getByText("Tier 1")).toBeInTheDocument();
    expect(screen.getByText("not present in this snapshot")).toBeInTheDocument();
    expect(screen.getByText("sessions.future_column is not yet classified for display")).toBeInTheDocument();
    expect(screen.getByText("CHANGED")).toBeInTheDocument();
    // NULL renders as its own distinct node in addition to the state chip label.
    expect(screen.getAllByText("null").length).toBeGreaterThanOrEqual(2);
  });

  it("shows a not-found state for a 404 detail fetch, never a distinct message for missing vs out-of-scope", async () => {
    mockFetch((url) => {
      if (url.startsWith("/api/audit/99")) return jsonResponse(404, { error: "NOT_FOUND" });
      return jsonResponse(200, {
        entries: [{ ...BASE_ENTRY, audit_id: 99, row_pk: "sess-99", operation: "DELETE" }],
        next_cursor: null,
      });
    });
    render(withTheme(<AuditScreen />));

    await waitFor(() => expect(screen.getByText("row sess-99")).toBeInTheDocument());
    fireEvent.click(screen.getByText("row sess-99"));

    await waitFor(() => expect(screen.getByText("Not found")).toBeInTheDocument());
  });
});
