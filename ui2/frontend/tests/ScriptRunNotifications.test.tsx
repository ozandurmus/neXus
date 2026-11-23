import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ThemeProvider } from "@mui/material/styles";
import { afterEach, describe, expect, it, vi } from "vitest";

import { SessionContext, type SessionInfo } from "../src/auth/SessionContext";
import { resetSharedApiCache, type ScriptRunSummary, type ScriptSummary } from "../src/auth/adminApi";
import { ScriptExecutionPanel } from "../src/screens/ScriptExecutionPanel";
import { describeRunNotification, notifySendsMail } from "../src/screens/ScriptFormHelpers";
import { m3Theme } from "../src/theme/m3Theme";

// Script Execution slice 3 (V264): the per-script "mail on failure only" option and the run-end notification outcome
// in the run history row. Synthetic values only.

const SECURITY_ADMIN_SESSION: SessionInfo = {
  displayName: "secadmin",
  roleTokens: ["role:security_admin"],
  canReadAdministration: true,
  permissions: ["Devices", "Config", "Compliance", "Operations", "Policy", "Admin"],
  onSignOut: () => {},
};

const RUN_AS_OPTIONS = ["role:operator", "role:viewer", "role:replay_viewer"] as const;

const MAIL_SCRIPT: ScriptSummary = {
  script_id: "scr-mail",
  name: "nightly-export",
  kind: "sh",
  current_version: 1,
  sha256: "9999888877776666555544443333222211110000ffffeeeeddddccccbbbbaaaa",
  size_bytes: 128,
  secret_findings: { count: 0, line_numbers: [], findings: [] },
  enabled: true,
  schedule: "0 2 * * *",
  next_five_fires: [],
  timeout_s: 300,
  egress: [],
  egress_device_targets: [],
  credential_refs: [],
  notify: "smtp",
  notify_mail_on_failure_only: false,
  run_as: "role:operator",
  created_by_actor_fingerprint: "USR-0a1b2c3d4e",
  created_at: "2026-11-23T07:00:00Z",
  updated_at: "2026-11-23T07:00:00Z",
  warnings: [],
  versions: [],
};

const BASE_RUN: ScriptRunSummary = {
  job_id: "run-301",
  run_id: "run-301",
  script_id: "scr-mail",
  version: 1,
  state: "FAILED",
  outcome: "FAILURE",
  trigger_kind: "SCHEDULED",
  scheduled_slot: null,
  run_as: "role:operator",
  requested_by: "system:script_scheduler",
  requested_at: "2026-11-23T08:00:00Z",
  started_at: "2026-11-23T08:00:01Z",
  finished_at: "2026-11-23T08:00:05Z",
  duration_ms: 4000,
  exit_code: 2,
  stdout_bytes: 10,
  stderr_bytes: 0,
  stdout_truncated: false,
  stderr_truncated: false,
  has_output: true,
  output_sha256: null,
  output_size_bytes: 10,
  output_tail: null,
  egress: [],
  egress_device_targets: [],
  credential_refs_used: [],
  failure_reason: null,
  notification: null,
};

const SENT_RUN: ScriptRunSummary = {
  ...BASE_RUN,
  notification: { syslog: "NOT_REQUESTED", syslog_code: null, smtp: "SENT", smtp_code: null, notified_at: "2026-11-23T08:01:00Z" },
};
const FAILED_RUN: ScriptRunSummary = {
  ...BASE_RUN,
  job_id: "run-302",
  run_id: "run-302",
  notification: {
    syslog: "SKIPPED",
    syslog_code: "syslog_not_configured",
    smtp: "FAILED",
    smtp_code: "send_failed",
    notified_at: "2026-11-23T08:02:00Z",
  },
};
const UNRECORDED_RUN: ScriptRunSummary = { ...BASE_RUN, job_id: "run-303", run_id: "run-303" };

afterEach(() => {
  vi.unstubAllGlobals();
  resetSharedApiCache();
  window.history.replaceState(null, "", "/");
});

describe("describeRunNotification", () => {
  it("never shows a missing record as sent, and colours a failed send red", () => {
    expect(describeRunNotification(UNRECORDED_RUN)).toMatchObject({ tone: "unknown", word: "Not recorded" });
    expect(describeRunNotification({ ...UNRECORDED_RUN, finished_at: null, state: "RUNNING", outcome: null })).toMatchObject({
      tone: "neutral",
      word: "—",
    });
    expect(describeRunNotification(SENT_RUN)).toMatchObject({ tone: "ok", word: "Mail sent" });
    const failed = describeRunNotification(FAILED_RUN);
    expect(failed.tone).toBe("bad");
    expect(failed.word).toBe("Syslog skipped · Mail failed");
    expect(failed.title).toContain("syslog is not configured in Settings > Notifications");
    expect(failed.title).toContain("the send failed");
    expect(
      describeRunNotification({
        ...BASE_RUN,
        outcome: "SUCCESS",
        notification: { syslog: "SENT", syslog_code: null, smtp: "SKIPPED", smtp_code: "failure_only", notified_at: "2026-11-23T08:03:00Z" },
      }),
    ).toMatchObject({ tone: "ok", word: "Syslog sent · Mail skipped" });
    expect(
      describeRunNotification({
        ...BASE_RUN,
        notification: { syslog: "NOT_REQUESTED", syslog_code: null, smtp: "NOT_REQUESTED", smtp_code: null, notified_at: "2026-11-23T08:03:00Z" },
      }),
    ).toMatchObject({ tone: "neutral", word: "Not requested" });
    expect(
      describeRunNotification({
        ...BASE_RUN,
        notification: { syslog: "DELIVERED", syslog_code: null, smtp: "SENT", smtp_code: null, notified_at: "2026-11-23T08:03:00Z" },
      }),
    ).toMatchObject({ tone: "unknown", word: "UNKNOWN" });
    expect(notifySendsMail("both")).toBe(true);
    expect(notifySendsMail("syslog")).toBe(false);
  });
});

describe("ScriptExecutionPanel notification option and run history outcome", () => {
  it("saves mail on failure only with a mail channel, disables it without one, and shows each run's notification outcome", async () => {
    let patchBody: Record<string, unknown> | null = null;
    let activeScript: ScriptSummary = MAIL_SCRIPT;
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        const method = init?.method ?? "GET";
        const ok = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200 }));
        if (url === "/session/status") return ok({ csrf_token: "csrf-1" });
        if (url === "/api/v2/scripts" && method === "GET") {
          return ok({
            scripts: [activeScript],
            can_write: true,
            can_run: true,
            can_read_output: true,
            runner_available: true,
            run_as_options: RUN_AS_OPTIONS,
            schedule_zone: "Europe/Istanbul",
          });
        }
        if (url === `/api/v2/scripts/${MAIL_SCRIPT.script_id}` && method === "GET") {
          return ok({ ...activeScript, can_write: true, can_run: true, can_read_output: true, runner_available: true, run_as_options: RUN_AS_OPTIONS });
        }
        if (url === `/api/v2/scripts/${MAIL_SCRIPT.script_id}/runs` && method === "GET") {
          return ok({ script_id: MAIL_SCRIPT.script_id, runs: [SENT_RUN, FAILED_RUN, UNRECORDED_RUN] });
        }
        if (url === "/credentials") return ok({ credentials: [] });
        if (url === `/api/v2/scripts/${MAIL_SCRIPT.script_id}` && method === "PATCH") {
          patchBody = JSON.parse(String(init?.body ?? "{}")) as Record<string, unknown>;
          activeScript = { ...activeScript, notify_mail_on_failure_only: true };
          return ok(activeScript);
        }
        return ok({});
      }),
    );

    render(
      <ThemeProvider theme={m3Theme}>
        <SessionContext.Provider value={SECURITY_ADMIN_SESSION}>
          <ScriptExecutionPanel />
        </SessionContext.Provider>
      </ThemeProvider>,
    );
    await screen.findByRole("table", { name: "Scripts table" });
    fireEvent.click(screen.getByRole("button", { name: "nightly-export" }));
    const detailPanel = await screen.findByTestId("script-detail-panel");

    // Run history: one outcome cell per run; a finished run without a record is never shown as sent.
    const runsTable = await screen.findByRole("table", { name: "Script runs table" });
    expect(within(runsTable).getByRole("columnheader", { name: "Notified" })).toBeInTheDocument();
    const cells = within(runsTable).getAllByTestId("script-run-notified");
    expect(cells.map((c) => c.textContent)).toEqual(["Mail sent", "Syslog skipped · Mail failed", "Not recorded"]);

    // The option is offered with a mail channel and saved with the settings.
    const editor = within(detailPanel).getByTestId("script-settings-editor");
    const option = within(editor).getByRole("checkbox", { name: "Mail on failure only" });
    expect(option).not.toBeDisabled();
    expect(option).not.toBeChecked();
    fireEvent.click(option);
    fireEvent.click(within(editor).getByRole("button", { name: "Save settings" }));
    await waitFor(() => expect(patchBody).not.toBeNull());
    expect(patchBody).toMatchObject({ notify: "smtp", notify_mail_on_failure_only: true });

    // Without a mail channel the option does not apply.
    fireEvent.mouseDown(within(editor).getByRole("combobox", { name: "Notify" }));
    const notifyMenu = await screen.findByRole("listbox");
    fireEvent.click(within(notifyMenu).getByRole("option", { name: "Syslog" }));
    expect(within(editor).getByRole("checkbox", { name: "Mail on failure only" })).toBeDisabled();
    expect(within(editor).getByText("Mail on failure only (needs SMTP or Both)")).toBeInTheDocument();
  });
});
