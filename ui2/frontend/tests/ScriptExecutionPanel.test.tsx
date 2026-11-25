import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ThemeProvider } from "@mui/material/styles";
import { afterEach, describe, expect, it, vi } from "vitest";

import { SessionContext, type SessionInfo } from "../src/auth/SessionContext";
import {
  resetSharedApiCache,
  type CredentialView,
  type ScriptRunSummary,
  type ScriptSummary,
} from "../src/auth/adminApi";
import { tabFromUrl } from "../src/screens/OperationsScreen";
import {
  SCRIPT_KINDS,
  ScriptExecutionPanel,
  extractAliasWarnings,
  hasRunCapability,
  inferScriptKindFromExtension,
  parseEgressInput,
  resolveRunAsOptions,
  resolveScheduleZone,
} from "../src/screens/ScriptExecutionPanel";
import { m3Theme } from "../src/theme/m3Theme";

const SECURITY_ADMIN_SESSION: SessionInfo = {
  displayName: "secadmin",
  roleTokens: ["role:security_admin"],
  canReadAdministration: true,
  permissions: ["Devices", "Config", "Compliance", "Operations", "Policy", "Admin"],
  onSignOut: () => {},
};

const OPERATOR_SESSION: SessionInfo = {
  displayName: "netops",
  roleTokens: ["role:operator"],
  canReadAdministration: false,
  permissions: ["Devices", "Config", "Compliance", "Operations"],
  onSignOut: () => {},
};

const VIEWER_SESSION: SessionInfo = {
  displayName: "auditor",
  roleTokens: ["role:viewer"],
  canReadAdministration: false,
  permissions: ["Devices", "Config", "Compliance", "Operations"],
  onSignOut: () => {},
};

const AIVIEW_SESSION: SessionInfo = {
  displayName: "aiview",
  roleTokens: ["role:viewer", "role:replay_viewer"],
  canReadAdministration: false,
  permissions: ["Devices", "Config", "Compliance", "Policy", "PacketAnalysis"],
  onSignOut: () => {},
};

const RUN_AS_OPTIONS = ["role:operator", "role:viewer", "role:replay_viewer"] as const;

const SAMPLE_CREDENTIALS: CredentialView[] = [
  {
    credential_id: "cred-1",
    credential_reference_id: "cred-ref-alpha",
    display_name: "Ops SSH",
    kind: "ssh_password",
    username: "ops_reader",
    allows_check_point: true,
    allows_palo_alto: true,
    created_at: "2026-11-10T08:00:00Z",
    secret_set_at: "2026-11-10T08:00:00Z",
  },
  {
    credential_id: "cred-2",
    credential_reference_id: "cred-ref-bravo",
    display_name: "Ops SSH",
    kind: "api_password",
    username: "api_sync",
    allows_check_point: false,
    allows_palo_alto: true,
    created_at: "2026-11-11T09:00:00Z",
    secret_set_at: "2026-11-11T09:00:00Z",
  },
];

const NEXT_FIVE_FIRES = [
  "2026-11-18T23:00:00Z",
  "2026-11-19T23:00:00Z",
  "2026-11-20T23:00:00Z",
  "2026-11-21T23:00:00Z",
  "2026-11-22T23:00:00Z",
];

const ALIAS_COLLISION_WARNING =
  "Credential reference 'cred-ref-bravo' maps to the same variable name as an earlier one (NEXUS_CRED_OPS_SSH_*); it is available as NEXUS_CRED_OPS_SSH_2_USERNAME / _SECRET.";

const WARNING_SCRIPT: ScriptSummary = {
  script_id: "scr-1",
  name: "dr-policy-audit",
  kind: "py",
  current_version: 2,
  sha256: "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90",
  size_bytes: 512,
  secret_findings: {
    count: 2,
    line_numbers: [2, 4],
    findings: [
      { line: 2, pattern_class: "CREDENTIAL_LITERAL" },
      { line: 4, pattern_class: "PRIVATE_KEY_MATERIAL" },
    ],
  },
  enabled: true,
  schedule: "0 2 * * *",
  next_five_fires: NEXT_FIVE_FIRES,
  timeout_s: 600,
  egress: ["192.0.2.10:443", "203.0.113.50:8443"],
  egress_device_targets: ["DEV-TANGO-04"],
  credential_refs: ["cred-ref-alpha"],
  notify: "both",
  run_as: "role:operator",
  created_by_actor_fingerprint: "USR-0a1b2c3d4e",
  created_at: "2026-11-18T08:00:00Z",
  updated_at: "2026-11-18T09:30:00Z",
  warnings: [
    "Secret-pattern match(es) detected on line(s) [2, 4] (CREDENTIAL_LITERAL, PRIVATE_KEY_MATERIAL); the script body is stored encrypted at rest, but declared credential-store references (credential_refs) are recommended instead of inline credentials.",
    "Declared egress targets enrolled device management address(es) [DEV-TANGO-04]; this is a direct path outside neXus command gates and will be recorded on each run.",
  ],
  versions: [
    {
      version: 2,
      sha256: "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90",
      size_bytes: 512,
      secret_findings: {
        count: 2,
        line_numbers: [2, 4],
        findings: [
          { line: 2, pattern_class: "CREDENTIAL_LITERAL" },
          { line: 4, pattern_class: "PRIVATE_KEY_MATERIAL" },
        ],
      },
      credential_refs: ["cred-ref-alpha"],
      egress_device_targets: ["DEV-TANGO-04"],
      created_by: "USR-0a1b2c3d4e",
      created_at: "2026-11-18T09:30:00Z",
    },
    {
      version: 1,
      sha256: "1111222233334444555566667777888899990000aaaabbbbccccddddeeeeffff",
      size_bytes: 256,
      secret_findings: { count: 0, line_numbers: [], findings: [] },
      credential_refs: [],
      egress_device_targets: [],
      created_by: "USR-0a1b2c3d4e",
      created_at: "2026-11-18T08:00:00Z",
    },
  ],
};

const CLEAN_SCRIPT: ScriptSummary = {
  script_id: "scr-2",
  name: "nightly-health-export",
  kind: "sh",
  current_version: 1,
  sha256: "9999888877776666555544443333222211110000ffffeeeeddddccccbbbbaaaa",
  size_bytes: 128,
  secret_findings: { count: 0, line_numbers: [], findings: [] },
  enabled: false,
  schedule: null,
  next_five_fires: [],
  timeout_s: 300,
  egress: [],
  egress_device_targets: [],
  credential_refs: [],
  notify: "none",
  run_as: "role:operator",
  created_by_actor_fingerprint: "USR-0a1b2c3d4e",
  created_at: "2026-11-18T07:00:00Z",
  updated_at: "2026-11-18T07:00:00Z",
  warnings: [],
  versions: [
    {
      version: 1,
      sha256: "9999888877776666555544443333222211110000ffffeeeeddddccccbbbbaaaa",
      size_bytes: 128,
      secret_findings: { count: 0, line_numbers: [], findings: [] },
      credential_refs: [],
      egress_device_targets: [],
      created_by: "USR-0a1b2c3d4e",
      created_at: "2026-11-18T07:00:00Z",
    },
  ],
};

const SAMPLE_RUN: ScriptRunSummary = {
  job_id: "run-101",
  run_id: "run-101",
  script_id: "scr-1",
  version: 2,
  state: "COMPLETED",
  outcome: "SUCCESS",
  trigger_kind: "MANUAL",
  scheduled_slot: null,
  run_as: "role:operator",
  requested_by: "USR-0a1b2c3d4e",
  requested_at: "2026-11-18T11:00:00Z",
  started_at: "2026-11-18T11:00:02Z",
  finished_at: "2026-11-18T11:00:04Z",
  duration_ms: 2100,
  exit_code: 0,
  stdout_bytes: 64,
  stderr_bytes: 0,
  stdout_truncated: false,
  stderr_truncated: false,
  has_output: true,
  output_sha256: "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
  output_size_bytes: 64,
  output_tail: "[stdout] checked FW-TANGO-04 (192.0.2.10): OK\n",
  egress: ["192.0.2.10:443"],
  egress_device_targets: ["DEV-TANGO-04"],
  credential_refs_used: ["cred-ref-alpha"],
  failure_reason: null,
};

function renderWithSession(session: SessionInfo | null) {
  return render(
    <ThemeProvider theme={m3Theme}>
      <SessionContext.Provider value={session}>
        <ScriptExecutionPanel />
      </SessionContext.Provider>
    </ThemeProvider>,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
  resetSharedApiCache();
  window.history.replaceState(null, "", "/");
});

describe("ScriptExecutionPanel helpers and Operations routing", () => {
  it("maps ?tab=script-execution to tab index 5 on OperationsScreen", () => {
    expect(tabFromUrl("script-execution")).toEqual({ tab: 5, pill: null });
  });

  it("accepts the 9 runnable Linux execution-image kinds, refuses .ps1, and flags .bat/.cmd separately", () => {
    expect(SCRIPT_KINDS).toEqual(["sh", "bash", "py", "jar", "js", "mjs", "pl", "rb", "bin"]);
    expect(SCRIPT_KINDS).not.toContain("ps1");

    for (const ext of ["sh", "bash", "py", "jar", "js", "mjs", "pl", "rb", "bin"]) {
      expect(inferScriptKindFromExtension(`job.${ext}`)).toEqual({
        kind: ext,
        isBatOrCmd: false,
        isPs1: false,
        unsupportedExtension: null,
      });
    }
    expect(inferScriptKindFromExtension("winstyle.PS1")).toEqual({
      kind: null,
      isBatOrCmd: false,
      isPs1: true,
      unsupportedExtension: "ps1",
    });
    expect(inferScriptKindFromExtension("legacy.BAT")).toEqual({
      kind: null,
      isBatOrCmd: true,
      isPs1: false,
      unsupportedExtension: "bat",
    });
    expect(inferScriptKindFromExtension("C:\\scripts\\deploy.cmd")).toEqual({
      kind: null,
      isBatOrCmd: true,
      isPs1: false,
      unsupportedExtension: "cmd",
    });
    expect(inferScriptKindFromExtension("notes.txt")).toEqual({
      kind: null,
      isBatOrCmd: false,
      isPs1: false,
      unsupportedExtension: "txt",
    });
  });

  it("evaluates run capability strictly from can_run && runner_available && !masked, plus run_as_options, schedule_zone, and alias warnings", () => {
    expect(parseEgressInput("192.0.2.10:443, 203.0.113.50:8443\n192.0.2.10:443")).toEqual([
      "192.0.2.10:443",
      "203.0.113.50:8443",
    ]);
    expect(hasRunCapability({ scripts: [], masked: false })).toBe(false);
    expect(hasRunCapability({ scripts: [], masked: false, runner_available: true, can_run: false })).toBe(false);
    expect(hasRunCapability({ scripts: [], masked: false, runner_available: false, can_run: true })).toBe(false);
    expect(hasRunCapability({ scripts: [], masked: false, runner_available: true, can_run: true })).toBe(true);
    expect(hasRunCapability({ scripts: [], masked: true, runner_available: true, can_run: true })).toBe(false);

    expect(resolveRunAsOptions({ scripts: [], masked: false, run_as_options: ["role:viewer"] })).toEqual(["role:viewer"]);
    expect(resolveScheduleZone({ scripts: [], masked: false, schedule_zone: "Europe/Istanbul" })).toBe("Europe/Istanbul");
    expect(extractAliasWarnings(["Other warning", ALIAS_COLLISION_WARNING])).toEqual([ALIAS_COLLISION_WARNING]);
  });
});

describe("ScriptExecutionPanel list, empty, error, restricted, and runner availability states", () => {
  it("renders empty state and offline runner banner when runner_available is false", async () => {
    const calls: string[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((input: RequestInfo | URL) => {
        const url = String(input);
        calls.push(url);
        if (url === "/api/v2/scripts") {
          return Promise.resolve(
            new Response(
              JSON.stringify({
                scripts: [],
                can_write: true,
                can_run: true,
                can_read_output: true,
                runner_available: false,
                run_as_options: RUN_AS_OPTIONS,
                schedule_zone: "Europe/Istanbul",
              }),
              { status: 200 },
            ),
          );
        }
        if (url === "/credentials") {
          return Promise.resolve(new Response(JSON.stringify({ credentials: SAMPLE_CREDENTIALS }), { status: 200 }));
        }
        return Promise.resolve(new Response("{}", { status: 404 }));
      }),
    );

    renderWithSession(SECURITY_ADMIN_SESSION);
    expect(await screen.findByText("No scripts registered yet")).toBeInTheDocument();
    const runnerSection = screen.getByTestId("script-runner-disabled-section");
    expect(runnerSection).toHaveTextContent(/Script runner unavailable/i);
    expect(within(runnerSection).getByRole("button", { name: "Run now" })).toBeDisabled();
    expect(calls.some((u) => u.includes("/run"))).toBe(false);
  });

  it("hides the offline runner banner when runner_available is true", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((input: RequestInfo | URL) => {
        const url = String(input);
        if (url === "/api/v2/scripts") {
          return Promise.resolve(
            new Response(
              JSON.stringify({
                scripts: [CLEAN_SCRIPT],
                can_write: false,
                can_run: true,
                can_read_output: true,
                runner_available: true,
                run_as_options: RUN_AS_OPTIONS,
                schedule_zone: "Europe/Istanbul",
              }),
              { status: 200 },
            ),
          );
        }
        return Promise.resolve(new Response("{}", { status: 200 }));
      }),
    );

    renderWithSession(OPERATOR_SESSION);
    await screen.findByRole("table", { name: "Scripts table" });
    expect(screen.queryByTestId("script-runner-disabled-section")).toBeNull();
  });

  it("renders error state with Retry on 503 and RestrictedPanel on 403", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response(JSON.stringify({ message: "Database offline" }), { status: 503 })),
    );
    const first = renderWithSession(OPERATOR_SESSION);
    expect(await screen.findByText("Could not load scripts.")).toBeInTheDocument();
    expect(screen.getByText("Database offline")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Retry" })).toBeInTheDocument();
    first.unmount();

    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response(JSON.stringify({ error: "ACTION_REFUSED" }), { status: 403 })),
    );
    renderWithSession(OPERATOR_SESSION);
    expect(await screen.findByText(/This account can not view or change it/)).toBeInTheDocument();
  });

  it("renders list columns, warning badges (including alias collisions), and filters without exposing any stored-body view action", async () => {
    const aliasScript: ScriptSummary = {
      ...CLEAN_SCRIPT,
      script_id: "scr-alias",
      name: "cred-alias-sync",
      warnings: [ALIAS_COLLISION_WARNING],
    };

    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((input: RequestInfo | URL) => {
        const url = String(input);
        if (url === "/api/v2/scripts") {
          return Promise.resolve(
            new Response(
              JSON.stringify({
                scripts: [WARNING_SCRIPT, CLEAN_SCRIPT, aliasScript],
                can_write: true,
                can_run: true,
                can_read_output: true,
                runner_available: true,
                run_as_options: RUN_AS_OPTIONS,
                schedule_zone: "Europe/Istanbul",
              }),
              { status: 200 },
            ),
          );
        }
        if (url === "/credentials") {
          return Promise.resolve(new Response(JSON.stringify({ credentials: SAMPLE_CREDENTIALS }), { status: 200 }));
        }
        return Promise.resolve(new Response("{}", { status: 200 }));
      }),
    );

    renderWithSession(SECURITY_ADMIN_SESSION);
    const table = await screen.findByRole("table", { name: "Scripts table" });

    const row1 = within(table).getByText("dr-policy-audit").closest("tr")!;
    expect(within(row1).getByText("py")).toBeInTheDocument();
    expect(within(row1).getByText("v2")).toBeInTheDocument();
    expect(within(row1).getByText("0 2 * * *")).toBeInTheDocument();
    expect(within(row1).getByText("Enabled")).toBeInTheDocument();
    expect(within(row1).getByText("(+4)")).toBeInTheDocument();

    const badge1 = within(row1).getByTestId("warnings-badge-scr-1");
    expect(badge1).toHaveTextContent(/Secret findings.*2/);
    expect(badge1).toHaveTextContent(/Device egress.*1/);

    const row3 = within(table).getByText("cred-alias-sync").closest("tr")!;
    const badge3 = within(row3).getByTestId("warnings-badge-scr-alias");
    expect(badge3).toHaveTextContent(/Alias collision.*1/);

    // No view-code or download-script action exists anywhere on the list view
    expect(screen.queryByRole("button", { name: /view code|show code|download/i })).toBeNull();

    // Search filter narrows rows
    fireEvent.change(screen.getByLabelText("Search scripts"), { target: { value: "no-such-script" } });
    expect(await screen.findByText("No scripts match the current filters.")).toBeInTheDocument();
  });
});

describe("ScriptExecutionPanel list Last run column", () => {
  it("shows the last finished run outcome with its age, positive look only for SUCCESS, and never run / unknown tokens neutrally", async () => {
    const ago = (minutes: number) => new Date(Date.now() - minutes * 60_000).toISOString();
    const rows: ScriptSummary[] = [
      { ...CLEAN_SCRIPT, script_id: "scr-ok", name: "row-success", last_run_outcome: "SUCCESS", last_run_at: ago(5) },
      { ...CLEAN_SCRIPT, script_id: "scr-bad", name: "row-failure", last_run_outcome: "FAILURE", last_run_at: ago(120) },
      { ...CLEAN_SCRIPT, script_id: "scr-to", name: "row-timeout", last_run_outcome: "TIMED_OUT", last_run_at: ago(60 * 24 * 3) },
      { ...CLEAN_SCRIPT, script_id: "scr-never", name: "row-never", last_run_outcome: null, last_run_at: null },
      { ...CLEAN_SCRIPT, script_id: "scr-odd", name: "row-odd", last_run_outcome: "SOMETHING_NEW", last_run_at: ago(10) },
    ];
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((input: RequestInfo | URL) => {
        const url = String(input);
        if (url === "/api/v2/scripts") {
          return Promise.resolve(
            new Response(
              JSON.stringify({
                scripts: rows,
                can_write: true,
                can_run: true,
                can_read_output: true,
                runner_available: true,
                run_as_options: RUN_AS_OPTIONS,
                schedule_zone: "Europe/Istanbul",
              }),
              { status: 200 },
            ),
          );
        }
        if (url === "/credentials") {
          return Promise.resolve(new Response(JSON.stringify({ credentials: SAMPLE_CREDENTIALS }), { status: 200 }));
        }
        return Promise.resolve(new Response("{}", { status: 200 }));
      }),
    );

    renderWithSession(SECURITY_ADMIN_SESSION);
    const table = await screen.findByRole("table", { name: "Scripts table" });
    expect(within(table).getByRole("columnheader", { name: "Last run" })).toBeInTheDocument();

    const cell = (id: string) => within(table).getByTestId(`last-run-${id}`);
    const pillRole = (id: string) => cell(id).querySelector("[data-pill]")!.getAttribute("data-pill");

    expect(cell("scr-ok")).toHaveTextContent("SUCCESS");
    expect(cell("scr-ok")).toHaveTextContent("5 min ago");
    expect(pillRole("scr-ok")).toBe("ok");

    expect(cell("scr-bad")).toHaveTextContent("FAILURE");
    expect(cell("scr-bad")).toHaveTextContent("2 h ago");
    expect(pillRole("scr-bad")).toBe("bad");

    expect(cell("scr-to")).toHaveTextContent("TIMED_OUT");
    expect(cell("scr-to")).toHaveTextContent("3 d ago");
    expect(pillRole("scr-to")).toBe("bad");

    expect(cell("scr-never")).toHaveTextContent("never run");
    expect(cell("scr-never")).not.toHaveTextContent("ago");
    expect(pillRole("scr-never")).toBe("neutral");

    expect(cell("scr-odd")).toHaveTextContent("SOMETHING_NEW");
    expect(cell("scr-odd")).toHaveTextContent("10 min ago");
    expect(pillRole("scr-odd")).toBe("neutral");

    const okPills = Array.from(table.querySelectorAll('[data-fact^="last-run"][data-pill="ok"]'));
    expect(okPills).toHaveLength(1);
  });
});

describe("Create script, .ps1 and .bat/.cmd refusals, run_as select, new version upload, and post-save warnings", () => {
  it("removes .ps1 from accepted kinds, shows the technical refusal reason for .ps1, and fixes .bat/.cmd so it never suggests .ps1", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((input: RequestInfo | URL) => {
        const url = String(input);
        if (url === "/api/v2/scripts") {
          return Promise.resolve(
            new Response(
              JSON.stringify({
                scripts: [],
                can_write: true,
                can_run: true,
                can_read_output: true,
                runner_available: true,
                run_as_options: RUN_AS_OPTIONS,
                schedule_zone: "Europe/Istanbul",
              }),
              { status: 200 },
            ),
          );
        }
        if (url === "/credentials") {
          return Promise.resolve(new Response(JSON.stringify({ credentials: SAMPLE_CREDENTIALS }), { status: 200 }));
        }
        return Promise.resolve(new Response("{}", { status: 200 }));
      }),
    );

    renderWithSession(SECURITY_ADMIN_SESSION);
    await screen.findByText("No scripts registered yet");

    fireEvent.click(screen.getAllByRole("button", { name: "New script" })[0]);
    const dialog = await screen.findByRole("dialog", { name: "New script" });

    // Kind combobox must not list PowerShell / .ps1
    fireEvent.mouseDown(within(dialog).getByRole("combobox", { name: "Kind" }));
    const kindListbox = await screen.findByRole("listbox");
    expect(within(kindListbox).queryByText(/ps1|PowerShell/i)).toBeNull();
    fireEvent.click(within(kindListbox).getByRole("option", { name: /Bash 5/i }));

    // Write-only notice is shown explicitly before save
    expect(within(dialog).getByTestId("write-only-script-notice")).toHaveTextContent(
      /sent once, encrypted at rest, and never shown again after save/i,
    );

    // Code editor mode: .ps1 filename shows technical refusal (PowerShell not in execution image) and disables Save
    fireEvent.change(within(dialog).getByLabelText(/File name/i), { target: { value: "winstyle.ps1" } });
    fireEvent.change(within(dialog).getByLabelText(/Script code/i), { target: { value: "Write-Output 'hi'" } });
    const ps1Refusal = within(dialog).getByTestId("ps1-refusal");
    expect(ps1Refusal).toHaveTextContent(/PowerShell 7 \(pwsh\) is not installed in the script execution image/i);
    expect(within(dialog).getByRole("button", { name: "Save script" })).toBeDisabled();

    // Code editor mode: .bat filename shows Linux execution image refusal and NEVER suggests .ps1
    fireEvent.change(within(dialog).getByLabelText(/File name/i), { target: { value: "legacy.bat" } });
    const batRefusal = within(dialog).getByTestId("bat-cmd-refusal");
    expect(batRefusal).toHaveTextContent(/cannot run on the Linux execution image/i);
    expect(batRefusal.textContent).not.toMatch(/\.ps1|powershell/i);
    expect(within(dialog).getByRole("button", { name: "Save script" })).toBeDisabled();

    // File upload mode: .ps1 and .cmd files are both refused cleanly
    fireEvent.click(within(dialog).getByRole("radio", { name: "File upload" }));
    const fileInput = within(dialog).getByLabelText("Script file") as HTMLInputElement;

    const ps1File = new File(["Write-Output 'x'\n"], "audit.ps1", { type: "text/plain" });
    fireEvent.change(fileInput, { target: { files: [ps1File] } });
    expect(await within(dialog).findByTestId("ps1-refusal")).toHaveTextContent(
      /not installed in the script execution image/i,
    );
    expect(within(dialog).getByRole("button", { name: "Save script" })).toBeDisabled();

    const cmdFile = new File(["echo test\r\n"], "deploy.cmd", { type: "text/plain" });
    fireEvent.change(fileInput, { target: { files: [cmdFile] } });
    const cmdRefusal = await within(dialog).findByTestId("bat-cmd-refusal");
    expect(cmdRefusal).toHaveTextContent(/cannot run on the Linux execution image/i);
    expect(cmdRefusal.textContent).not.toMatch(/\.ps1|powershell/i);
    expect(within(dialog).getByRole("button", { name: "Save script" })).toBeDisabled();
  });

  it("creates a script with run_as select from run_as_options (no free text), clears code from memory, and renders post-save warnings", async () => {
    let postedBody: Record<string, unknown> | null = null;
    let listItems: ScriptSummary[] = [];

    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        const method = init?.method ?? "GET";
        if (url === "/session/status") {
          return Promise.resolve(new Response(JSON.stringify({ csrf_token: "csrf-1" }), { status: 200 }));
        }
        if (url === "/api/v2/scripts" && method === "GET") {
          return Promise.resolve(
            new Response(
              JSON.stringify({
                scripts: listItems,
                can_write: true,
                can_run: true,
                can_read_output: true,
                runner_available: true,
                run_as_options: RUN_AS_OPTIONS,
                schedule_zone: "Europe/Istanbul",
              }),
              { status: 200 },
            ),
          );
        }
        if (url === "/credentials") {
          return Promise.resolve(new Response(JSON.stringify({ credentials: SAMPLE_CREDENTIALS }), { status: 200 }));
        }
        if (url === `/api/v2/scripts/${WARNING_SCRIPT.script_id}/runs`) {
          return Promise.resolve(new Response(JSON.stringify({ script_id: WARNING_SCRIPT.script_id, runs: [] }), { status: 200 }));
        }
        if (url === "/api/v2/scripts" && method === "POST") {
          postedBody = JSON.parse(String(init?.body ?? "{}")) as Record<string, unknown>;
          listItems = [WARNING_SCRIPT];
          return Promise.resolve(new Response(JSON.stringify(WARNING_SCRIPT), { status: 201 }));
        }
        return Promise.resolve(new Response("{}", { status: 200 }));
      }),
    );

    renderWithSession(SECURITY_ADMIN_SESSION);
    await screen.findByText("No scripts registered yet");

    fireEvent.click(screen.getAllByRole("button", { name: "New script" })[0]);
    const dialog = await screen.findByRole("dialog", { name: "New script" });

    // run_as is a select combobox from run_as_options, never a free-text input and never security_admin
    expect(within(dialog).queryByRole("textbox", { name: /Run as/i })).toBeNull();
    const runAsCombo = within(dialog).getByRole("combobox", { name: "Run as" });
    fireEvent.mouseDown(runAsCombo);
    const runAsListbox = await screen.findByRole("listbox");
    expect(within(runAsListbox).queryByText(/security_admin|Security Admin/i)).toBeNull();
    fireEvent.click(within(runAsListbox).getByRole("option", { name: /Viewer \(role:viewer\)/ }));

    const samplePyBody = "#!/usr/bin/env " + "pyth" + "on3\nprint('audit')\n";
    fireEvent.change(within(dialog).getByLabelText(/Script name/i), { target: { value: "dr-policy-audit" } });
    fireEvent.change(within(dialog).getByLabelText(/File name/i), { target: { value: "dr-policy-audit.py" } });
    fireEvent.change(within(dialog).getByLabelText(/Script code/i), {
      target: { value: samplePyBody },
    });
    fireEvent.change(within(dialog).getByLabelText("Schedule (five-field cron)"), { target: { value: "0 2 * * *" } });
    fireEvent.change(within(dialog).getByLabelText(/Egress/i), {
      target: { value: "192.0.2.10:443, 203.0.113.50:8443" },
    });
    fireEvent.click(within(dialog).getAllByRole("checkbox", { name: /Ops SSH/i })[0]);

    fireEvent.click(within(dialog).getByRole("button", { name: "Save script" }));

    const warningsCard = await screen.findByTestId("script-post-save-warnings");
    expect(postedBody).toMatchObject({
      name: "dr-policy-audit",
      file_name: "dr-policy-audit.py",
      kind: "py",
      body: samplePyBody,
      schedule: "0 2 * * *",
      egress: ["192.0.2.10:443", "203.0.113.50:8443"],
      credential_refs: ["cred-ref-alpha"],
      run_as: "role:viewer",
    });

    // Submitted script text is never shown anywhere on the page after save
    expect(screen.queryByText(/print\('audit'\)/)).toBeNull();

    // Warnings after save: line numbers without values, device egress outside neXus command gates
    expect(warningsCard).toHaveTextContent("Line 2: CREDENTIAL_LITERAL");
    expect(warningsCard).toHaveTextContent("Line 4: PRIVATE_KEY_MATERIAL");
    expect(warningsCard).toHaveTextContent("direct path outside neXus command gates");
    expect(warningsCard).toHaveTextContent("DEV-TANGO-04");
  });

  it("uploads a file via Base64 on create (.sh) and uploads an immutable new version via POST /api/v2/scripts/:id/versions", async () => {
    let createPayload: Record<string, unknown> | null = null;
    let versionPayload: Record<string, unknown> | null = null;
    let currentScript: ScriptSummary = CLEAN_SCRIPT;

    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        const method = init?.method ?? "GET";
        if (url === "/session/status") {
          return Promise.resolve(new Response(JSON.stringify({ csrf_token: "csrf-1" }), { status: 200 }));
        }
        if (url === "/api/v2/scripts" && method === "GET") {
          return Promise.resolve(
            new Response(
              JSON.stringify({
                scripts: [currentScript],
                can_write: true,
                can_run: true,
                can_read_output: true,
                runner_available: true,
                run_as_options: RUN_AS_OPTIONS,
                schedule_zone: "Europe/Istanbul",
              }),
              { status: 200 },
            ),
          );
        }
        if (url === `/api/v2/scripts/${CLEAN_SCRIPT.script_id}` && method === "GET") {
          return Promise.resolve(
            new Response(
              JSON.stringify({
                ...currentScript,
                can_write: true,
                can_run: true,
                can_read_output: true,
                runner_available: true,
                run_as_options: RUN_AS_OPTIONS,
              }),
              { status: 200 },
            ),
          );
        }
        if (url === `/api/v2/scripts/${CLEAN_SCRIPT.script_id}/runs` && method === "GET") {
          return Promise.resolve(new Response(JSON.stringify({ script_id: CLEAN_SCRIPT.script_id, runs: [] }), { status: 200 }));
        }
        if (url === "/credentials") {
          return Promise.resolve(new Response(JSON.stringify({ credentials: SAMPLE_CREDENTIALS }), { status: 200 }));
        }
        if (url === "/api/v2/scripts" && method === "POST") {
          createPayload = JSON.parse(String(init?.body ?? "{}")) as Record<string, unknown>;
          return Promise.resolve(new Response(JSON.stringify(CLEAN_SCRIPT), { status: 201 }));
        }
        if (url === `/api/v2/scripts/${CLEAN_SCRIPT.script_id}/versions` && method === "POST") {
          versionPayload = JSON.parse(String(init?.body ?? "{}")) as Record<string, unknown>;
          currentScript = {
            ...CLEAN_SCRIPT,
            current_version: 2,
            credential_refs: ["cred-ref-bravo"],
            versions: [
              {
                version: 2,
                sha256: "2222333344445555666677778888999900001111aaaabbbbccccddddeeeeffff",
                size_bytes: 160,
                secret_findings: { count: 0, line_numbers: [], findings: [] },
                credential_refs: ["cred-ref-bravo"],
                egress_device_targets: [],
                created_by: "USR-0a1b2c3d4e",
                created_at: "2026-11-18T11:00:00Z",
              },
              ...(CLEAN_SCRIPT.versions ?? []),
            ],
          };
          return Promise.resolve(new Response(JSON.stringify(currentScript), { status: 201 }));
        }
        return Promise.resolve(new Response("{}", { status: 200 }));
      }),
    );

    renderWithSession(SECURITY_ADMIN_SESSION);
    await screen.findByRole("table", { name: "Scripts table" });

    // Create via file upload (.sh)
    fireEvent.click(screen.getByRole("button", { name: "New script" }));
    const createDialog = await screen.findByRole("dialog", { name: "New script" });
    fireEvent.click(within(createDialog).getByRole("radio", { name: "File upload" }));
    const shFile = new File(["#!/bin/sh\necho ok\n"], "nightly-health-export.sh", { type: "text/plain" });
    fireEvent.change(within(createDialog).getByLabelText("Script file"), { target: { files: [shFile] } });

    await waitFor(() => expect(within(createDialog).getByRole("button", { name: "Save script" })).toBeEnabled());
    fireEvent.click(within(createDialog).getByRole("button", { name: "Save script" }));
    await waitFor(() => expect(createPayload).not.toBeNull());
    expect(createPayload).toMatchObject({
      name: "nightly-health-export",
      file_name: "nightly-health-export.sh",
      kind: "sh",
      content_base64: btoa("#!/bin/sh\necho ok\n"),
      run_as: "role:operator",
    });

    // Upload new version (v2)
    fireEvent.click(screen.getByRole("button", { name: "New version" }));
    const versionDialog = await screen.findByRole("dialog", { name: /Upload new version · nightly-health-export/ });
    expect(within(versionDialog).getByTestId("write-only-version-notice")).toHaveTextContent(/never shown again after save/i);
    fireEvent.change(within(versionDialog).getByLabelText("New version code"), {
      target: { value: "#!/bin/sh\necho v2\n" },
    });
    fireEvent.click(within(versionDialog).getByRole("checkbox", { name: /cred-ref-bravo/i }));
    fireEvent.click(within(versionDialog).getByRole("button", { name: "Save v2" }));

    await waitFor(() => expect(versionPayload).not.toBeNull());
    expect(versionPayload).toMatchObject({
      kind: "sh",
      body: "#!/bin/sh\necho v2\n",
      credential_refs: ["cred-ref-bravo"],
    });

    const versionsTable = await screen.findByRole("table", { name: "Script versions table" });
    expect(within(versionsTable).getByText("v2")).toBeInTheDocument();
    expect(within(versionsTable).getByText("v1")).toBeInTheDocument();
    expect(screen.queryByText(/echo v2/)).toBeNull();
  });
});

describe("Settings update, schedule_zone, run_as select, credential_refs versioning, and alias warnings", () => {
  it("shows schedule_zone next to next-five-fires, selects run_as from run_as_options, creates a new version when credential_refs change, and surfaces alias warnings", async () => {
    let patchBody: Record<string, unknown> | null = null;
    let enableCalled = false;
    let disableCalled = false;
    let activeScript: ScriptSummary = WARNING_SCRIPT;

    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        const method = init?.method ?? "GET";
        if (url === "/session/status") {
          return Promise.resolve(new Response(JSON.stringify({ csrf_token: "csrf-1" }), { status: 200 }));
        }
        if (url === "/api/v2/scripts" && method === "GET") {
          return Promise.resolve(
            new Response(
              JSON.stringify({
                scripts: [activeScript],
                can_write: true,
                can_run: true,
                can_read_output: true,
                runner_available: true,
                run_as_options: RUN_AS_OPTIONS,
                schedule_zone: "Europe/Istanbul",
              }),
              { status: 200 },
            ),
          );
        }
        if (url === `/api/v2/scripts/${WARNING_SCRIPT.script_id}` && method === "GET") {
          return Promise.resolve(
            new Response(
              JSON.stringify({
                ...activeScript,
                can_write: true,
                can_run: true,
                can_read_output: true,
                runner_available: true,
                run_as_options: RUN_AS_OPTIONS,
              }),
              { status: 200 },
            ),
          );
        }
        if (url === `/api/v2/scripts/${WARNING_SCRIPT.script_id}/runs` && method === "GET") {
          return Promise.resolve(new Response(JSON.stringify({ script_id: WARNING_SCRIPT.script_id, runs: [] }), { status: 200 }));
        }
        if (url === "/credentials") {
          return Promise.resolve(new Response(JSON.stringify({ credentials: SAMPLE_CREDENTIALS }), { status: 200 }));
        }
        if (url === `/api/v2/scripts/${WARNING_SCRIPT.script_id}` && method === "PATCH") {
          patchBody = JSON.parse(String(init?.body ?? "{}")) as Record<string, unknown>;
          activeScript = {
            ...activeScript,
            current_version: 3,
            schedule: "*/30 * * * *",
            timeout_s: 900,
            egress: ["198.51.100.25:443"],
            notify: "syslog",
            run_as: "role:replay_viewer",
            credential_refs: ["cred-ref-alpha", "cred-ref-bravo"],
            warnings: [...WARNING_SCRIPT.warnings, ALIAS_COLLISION_WARNING],
            versions: [
              {
                version: 3,
                sha256: WARNING_SCRIPT.sha256!,
                size_bytes: WARNING_SCRIPT.size_bytes,
                secret_findings: WARNING_SCRIPT.secret_findings,
                credential_refs: ["cred-ref-alpha", "cred-ref-bravo"],
                egress_device_targets: ["DEV-JULIET-06"],
                created_by: "USR-0a1b2c3d4e",
                created_at: "2026-11-18T12:00:00Z",
              },
              ...(WARNING_SCRIPT.versions ?? []),
            ],
          };
          return Promise.resolve(new Response(JSON.stringify(activeScript), { status: 200 }));
        }
        if (url === `/api/v2/scripts/${WARNING_SCRIPT.script_id}/disable` && method === "POST") {
          disableCalled = true;
          activeScript = { ...activeScript, enabled: false };
          return Promise.resolve(new Response(JSON.stringify(activeScript), { status: 200 }));
        }
        if (url === `/api/v2/scripts/${WARNING_SCRIPT.script_id}/enable` && method === "POST") {
          enableCalled = true;
          activeScript = { ...activeScript, enabled: true };
          return Promise.resolve(new Response(JSON.stringify(activeScript), { status: 200 }));
        }
        return Promise.resolve(new Response("{}", { status: 200 }));
      }),
    );

    renderWithSession(SECURITY_ADMIN_SESSION);
    await screen.findByRole("table", { name: "Scripts table" });

    fireEvent.click(screen.getByRole("button", { name: "dr-policy-audit" }));
    const detailPanel = await screen.findByTestId("script-detail-panel");

    // Next five fires preview renders all 5 items and schedule_zone beside the preview
    const nextFires = within(detailPanel).getByTestId("script-next-five-fires");
    expect(within(nextFires).getAllByRole("listitem")).toHaveLength(5);
    expect(within(nextFires).getByTestId("script-schedule-zone")).toHaveTextContent("Europe/Istanbul");

    const editor = within(detailPanel).getByTestId("script-settings-editor");
    expect(within(editor).queryByRole("textbox", { name: /Run as/i })).toBeNull();

    fireEvent.change(within(editor).getByLabelText(/Schedule/i), { target: { value: "*/30 * * * *" } });
    fireEvent.change(within(editor).getByLabelText(/Timeout/i), { target: { value: "900" } });
    fireEvent.change(within(editor).getByLabelText(/Egress/i), { target: { value: "198.51.100.25:443" } });

    // Select run_as from run_as_options
    fireEvent.mouseDown(within(editor).getByRole("combobox", { name: "Run as" }));
    const runAsMenu = await screen.findByRole("listbox");
    fireEvent.click(within(runAsMenu).getByRole("option", { name: /role:replay_viewer/ }));

    // Changing credential_refs shows explicit version-bump notice before save
    fireEvent.click(within(editor).getByRole("checkbox", { name: /cred-ref-bravo/i }));
    expect(within(editor).getByTestId("credential-refs-version-note")).toHaveTextContent(/v3/);

    fireEvent.click(within(editor).getByRole("button", { name: "Save settings" }));
    await waitFor(() => expect(patchBody).not.toBeNull());
    expect(patchBody).toMatchObject({
      schedule: "*/30 * * * *",
      clear_schedule: false,
      timeout_s: 900,
      egress: ["198.51.100.25:443"],
      run_as: "role:replay_viewer",
      credential_refs: ["cred-ref-alpha", "cred-ref-bravo"],
    });

    // Version bumped to v3 and alias collision warning rendered in detail + post-save banner
    expect(await within(detailPanel).findByText(/recorded new version v3/i)).toBeInTheDocument();
    expect(within(detailPanel).getByTestId("script-detail-warnings-alias-warnings")).toHaveTextContent(
      "NEXUS_CRED_OPS_SSH_2_USERNAME",
    );
    expect(screen.getByTestId("script-post-save-warnings-alias-warnings")).toHaveTextContent(
      "NEXUS_CRED_OPS_SSH_2_USERNAME",
    );

    // Disable then Enable via dedicated endpoints
    fireEvent.click(within(detailPanel).getByRole("button", { name: "Disable" }));
    await waitFor(() => expect(disableCalled).toBe(true));
    fireEvent.click(within(detailPanel).getByRole("button", { name: "Enable" }));
    await waitFor(() => expect(enableCalled).toBe(true));
  });
});

describe("Run now, run history, audited output download, RBAC gating, and AIView masking", () => {
  it("gates edit controls when can_write is false while enabling Run now, run inspection, and audited output download for Operator", async () => {
    const calls: string[] = [];
    let runsList: ScriptRunSummary[] = [SAMPLE_RUN];
    let downloadedUrl: string | null = null;

    const createObjectUrlSpy = vi.fn(() => "blob:script-output-test");
    const revokeObjectUrlSpy = vi.fn();
    vi.stubGlobal("URL", {
      ...URL,
      createObjectURL: createObjectUrlSpy,
      revokeObjectURL: revokeObjectUrlSpy,
    });
    const anchorClickSpy = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});

    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        const method = init?.method ?? "GET";
        calls.push(`${method} ${url}`);
        if (url === "/session/status") {
          return Promise.resolve(new Response(JSON.stringify({ csrf_token: "csrf-op" }), { status: 200 }));
        }
        if (url === "/api/v2/scripts" && method === "GET") {
          return Promise.resolve(
            new Response(
              JSON.stringify({
                scripts: [{ ...WARNING_SCRIPT, can_run: true, runner_available: true }],
                can_write: false,
                can_run: true,
                can_read_output: true,
                runner_available: true,
                run_as_options: RUN_AS_OPTIONS,
                schedule_zone: "Europe/Istanbul",
              }),
              { status: 200 },
            ),
          );
        }
        if (url === `/api/v2/scripts/${WARNING_SCRIPT.script_id}` && method === "GET") {
          return Promise.resolve(
            new Response(
              JSON.stringify({
                ...WARNING_SCRIPT,
                can_write: false,
                can_run: true,
                can_read_output: true,
                runner_available: true,
                run_as_options: RUN_AS_OPTIONS,
              }),
              { status: 200 },
            ),
          );
        }
        if (url === `/api/v2/scripts/${WARNING_SCRIPT.script_id}/runs` && method === "GET") {
          return Promise.resolve(
            new Response(JSON.stringify({ script_id: WARNING_SCRIPT.script_id, runs: runsList }), { status: 200 }),
          );
        }
        if (url === `/api/v2/scripts/${WARNING_SCRIPT.script_id}/run` && method === "POST") {
          const submitted: ScriptRunSummary = {
            ...SAMPLE_RUN,
            job_id: "run-202",
            run_id: "run-202",
            state: "COMPLETED",
            outcome: "SUCCESS",
          };
          runsList = [submitted, ...runsList];
          return Promise.resolve(new Response(JSON.stringify(submitted), { status: 202 }));
        }
        if (url === "/api/v2/scripts/runs/run-202" && method === "GET") {
          return Promise.resolve(
            new Response(
              JSON.stringify({
                ...SAMPLE_RUN,
                job_id: "run-202",
                run_id: "run-202",
              }),
              { status: 200 },
            ),
          );
        }
        if (url === "/api/v2/scripts/runs/run-202/output" && method === "GET") {
          downloadedUrl = url;
          return Promise.resolve(
            new Response("[stdout] full masked log output\n", {
              status: 200,
              headers: { "Content-Type": "text/plain" },
            }),
          );
        }
        return Promise.resolve(new Response("{}", { status: 404 }));
      }),
    );

    try {
      renderWithSession(OPERATOR_SESSION);
      const table = await screen.findByRole("table", { name: "Scripts table" });

      // can_write gating note is shown, edit controls are hidden, and /credentials is never called
      expect(screen.getByTestId("script-exec-readonly-note")).toHaveTextContent(/can_write required/i);
      expect(screen.queryByRole("button", { name: "New script" })).toBeNull();
      expect(screen.queryByRole("button", { name: "New version" })).toBeNull();
      expect(screen.queryByRole("button", { name: "Enable" })).toBeNull();
      expect(screen.queryByRole("button", { name: "Disable" })).toBeNull();
      expect(calls.some((c) => c.includes("/credentials"))).toBe(false);

      // Row Run now is enabled because can_run && runner_available
      const row = within(table).getByText("dr-policy-audit").closest("tr")!;
      expect(within(row).getByRole("button", { name: "Run now" })).toBeEnabled();

      // Open detail view -> loads GET /api/v2/scripts/{id}/runs
      fireEvent.click(screen.getByRole("button", { name: "dr-policy-audit" }));
      const detail = await screen.findByTestId("script-detail-panel");
      expect(within(detail).getByTestId("script-settings-readonly")).toBeInTheDocument();
      expect(within(detail).queryByTestId("script-settings-editor")).toBeNull();

      const runHistory = within(detail).getByTestId("script-run-history");
      const runsTable = await within(runHistory).findByRole("table", { name: "Script runs table" });
      expect(within(runsTable).getByText("run-101")).toBeInTheDocument();

      // Trigger Run now -> POST /api/v2/scripts/{id}/run, refreshes runs, loads GET /api/v2/scripts/runs/run-202
      fireEvent.click(within(runHistory).getByRole("button", { name: "Run now" }));
      const runDetail = await within(runHistory).findByTestId("script-run-detail");
      expect(runDetail).toHaveTextContent("Run run-202");
      expect(within(runDetail).getByTestId("script-run-output-tail")).toHaveTextContent(
        "checked FW-TANGO-04 (192.0.2.10): OK",
      );

      // Audited output download notice and GET /api/v2/scripts/runs/run-202/output
      expect(within(runDetail).getByTestId("script-output-audit-notice")).toHaveTextContent(/audited server-side/i);
      fireEvent.click(within(runDetail).getByRole("button", { name: "Download run output" }));
      await waitFor(() => expect(downloadedUrl).toBe("/api/v2/scripts/runs/run-202/output"));
      expect(createObjectUrlSpy).toHaveBeenCalledTimes(1);
      expect(await within(runDetail).findByText(/recorded in the server audit log/i)).toBeInTheDocument();
    } finally {
      anchorClickSpy.mockRestore();
    }
  });

  it("disables Run now when runner_available is false even if can_run is true, and hides output download when can_read_output is false", async () => {
    const calls: string[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        calls.push(`${init?.method ?? "GET"} ${url}`);
        if (url === "/api/v2/scripts") {
          return Promise.resolve(
            new Response(
              JSON.stringify({
                scripts: [{ ...WARNING_SCRIPT, can_run: false, runner_available: false }],
                can_write: false,
                can_run: false,
                can_read_output: false,
                runner_available: false,
                run_as_options: RUN_AS_OPTIONS,
                schedule_zone: "Europe/Istanbul",
              }),
              { status: 200 },
            ),
          );
        }
        if (url === `/api/v2/scripts/${WARNING_SCRIPT.script_id}`) {
          return Promise.resolve(
            new Response(
              JSON.stringify({
                ...WARNING_SCRIPT,
                can_write: false,
                can_run: false,
                can_read_output: false,
                runner_available: false,
                run_as_options: RUN_AS_OPTIONS,
              }),
              { status: 200 },
            ),
          );
        }
        if (url === `/api/v2/scripts/${WARNING_SCRIPT.script_id}/runs`) {
          return Promise.resolve(
            new Response(JSON.stringify({ script_id: WARNING_SCRIPT.script_id, runs: [SAMPLE_RUN] }), { status: 200 }),
          );
        }
        if (url === `/api/v2/scripts/runs/${SAMPLE_RUN.run_id}`) {
          return Promise.resolve(new Response(JSON.stringify(SAMPLE_RUN), { status: 200 }));
        }
        return Promise.resolve(new Response("{}", { status: 404 }));
      }),
    );

    renderWithSession(VIEWER_SESSION);
    await screen.findByRole("table", { name: "Scripts table" });

    fireEvent.click(screen.getByRole("button", { name: "dr-policy-audit" }));
    const detail = await screen.findByTestId("script-detail-panel");
    const runHistory = within(detail).getByTestId("script-run-history");
    expect(within(runHistory).getByRole("button", { name: "Run now" })).toBeDisabled();

    // Inspect run -> shows gated note instead of Download run output button and hides output_tail
    fireEvent.click(await within(runHistory).findByRole("button", { name: "Inspect run" }));
    const runDetail = await within(runHistory).findByTestId("script-run-detail");
    expect(within(runDetail).queryByTestId("script-run-output-tail")).toBeNull();
    expect(within(runDetail).queryByRole("button", { name: /Download run output/i })).toBeNull();
    expect(within(runDetail).getByTestId("script-output-gated-note")).toHaveTextContent(/can_read_output/i);
    expect(calls.some((c) => c.endsWith("/output"))).toBe(false);
  });

  it("renders masked projection for aiview (X-Nexus-Masked: true) across scripts and run history without edit, run, or output download actions", async () => {
    const maskedScript: ScriptSummary = {
      ...WARNING_SCRIPT,
      egress: ["192.0.2.10:443"],
      run_as: "role:operator",
      created_by_actor_fingerprint: "USR-0a1b2c3d4e",
      can_write: false,
      can_run: false,
      can_read_output: false,
      runner_available: true,
    };
    const maskedRun: ScriptRunSummary = {
      ...SAMPLE_RUN,
      requested_by: "USR-9f8e7d6c5b",
      egress: ["192.0.2.10:443"],
      output_tail: "[stdout] checked FW-TANGO-04 (192.0.2.10): OK\n",
    };
    const calls: string[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((input: RequestInfo | URL) => {
        const url = String(input);
        calls.push(url);
        const maskedJson = (body: unknown) =>
          Promise.resolve(
            new Response(JSON.stringify(body), {
              status: 200,
              headers: { "X-Nexus-Masked": "true" },
            }),
          );
        if (url === "/api/v2/scripts") {
          return maskedJson({
            scripts: [maskedScript],
            masked: true,
            can_write: false,
            can_run: false,
            can_read_output: false,
            runner_available: true,
            run_as_options: RUN_AS_OPTIONS,
            schedule_zone: "Europe/Istanbul",
          });
        }
        if (url === `/api/v2/scripts/${maskedScript.script_id}`) {
          return maskedJson(maskedScript);
        }
        if (url === `/api/v2/scripts/${maskedScript.script_id}/runs`) {
          return maskedJson({ script_id: maskedScript.script_id, runs: [maskedRun] });
        }
        if (url === `/api/v2/scripts/runs/${maskedRun.run_id}`) {
          return maskedJson(maskedRun);
        }
        return Promise.resolve(new Response("{}", { status: 403 }));
      }),
    );

    renderWithSession(AIVIEW_SESSION);
    await screen.findByRole("table", { name: "Scripts table" });

    expect(screen.getByTestId("script-exec-readonly-note")).toHaveTextContent(/masked projection/i);
    expect(screen.queryByRole("button", { name: "New script" })).toBeNull();
    expect(calls).not.toContain("/credentials");

    fireEvent.click(screen.getByRole("button", { name: "dr-policy-audit" }));
    const detail = await screen.findByTestId("script-detail-panel");
    expect(within(detail).getByText("192.0.2.10:443")).toBeInTheDocument();
    expect(within(detail).queryByTestId("script-settings-editor")).toBeNull();
    expect(within(detail).getByRole("button", { name: "Run now" })).toBeDisabled();

    fireEvent.click(await within(detail).findByRole("button", { name: "Inspect run" }));
    const runDetail = await within(detail).findByTestId("script-run-detail");
    expect(within(runDetail).getByText("USR-9f8e7d6c5b")).toBeInTheDocument();
    expect(within(runDetail).queryByTestId("script-run-output-tail")).toBeNull();
    expect(within(runDetail).queryByRole("button", { name: /Download run output/i })).toBeNull();
    expect(within(runDetail).getByTestId("script-output-gated-note")).toHaveTextContent(/masked inspection mode/i);
  });
});
