# SecurityExpert — AI Development Protocol (detailed reference)

This document is a **detail reference**, not a second constitution. The
session-lifecycle, SESSION START/CLOSE schemas, movement types, and
reasoning-tier rules live in `AGENTS.md` and `AI_START_HERE.md` — do not
restate them here or anywhere else; this file covers only what has no other
home: the network-device command gate, approval boundaries, the HTML render
harness mechanics, and exact test-execution commands. This protocol applies
to any coding/reasoning agent working in this repository — Claude, Copilot,
ChatGPT, or a human — equally; tool-specific tier names live only in that
tool's own delta file (`CLAUDE.md`, `.github/copilot-instructions.md`).

## Test execution economy (mandatory)

Use one-shot, file-backed local test runs to prevent repeated token/credit burn:

- Full suite (parallel): `py -m pytest -q -n auto --dist worksteal > pytest_result.log 2>&1`
  (requires `pip install -r requirements-dev.txt`; ~44s on 16 cores vs ~110s
  serial). `scripts/pytest_one_shot.ps1` does this by default; pass `-Serial`
  (or `-n0`) to run serially when debugging a single failure. Run the suite
  serially at least once before closing a build (`AGENTS.md`/`AI_START_HERE.md`
  validation ladder) — a parallel run has previously hidden a real
  shared-state leak that only reproduced serially two-thirds of the time.
- Read evidence from file (prefer Unicode read on Windows):
  `Get-Content pytest_result.log -Encoding Unicode -Tail 40`
- Re-run full suite only when source changes after that evidence.
- When the development runtime is already validated (workspace-standing
  fact, not a per-session check — `AGENTS.md` "Context/token discipline"),
  do not re-bootstrap PATH/interpreter repeatedly. Only apply minimal
  correction on an actual command failure and continue.

## Testing tiers

Tier 1: affected tests, syntax, security invariants.

Tier 2: affected subsystem/vendor regression.

Tier 3: full regression for shared-core changes, phase closure and release
candidates.

Do not run expensive real-device collection for UI-only or documentation work.

## CI validation policy (canonical — risk-based PR CI)

`.github/workflows/validation.yml` implements the tiers above as two jobs.
This is the one canonical statement of the policy; other files reference it
rather than restating it.

- **`validate`** (pull_request): the fast PR gate. Import/compileall
  sanity, the repository privacy gate, project-state consistency
  (`tests/test_architecture_convergence.py`), the build-history index
  check, a small fixed PR smoke/safety-regression set (credential
  redaction, the privacy gate's own tests, known safety gaps, the
  frontend-rendering shared-state-leak guard), and the whitespace/
  conflict-marker check. Deliberately **not** a path→test classifier —
  bounded feature PRs are expected to pay for this job, not the full suite.
- **`full-regression`** (push to `main`, `workflow_dispatch`): the same
  gates plus the full `pytest -q` suite, serial (Test execution economy
  above explains why serial). This is the post-merge integration safety
  net and the on-demand full-regression path.

A PR that trips one of the **full-regression triggers** below still needs a
full regression before merge — run it locally
(`py -m pytest -q > pytest_result.log 2>&1`, or the parallel one-shot
`scripts/pytest_one_shot.ps1`) or via `workflow_dispatch`, and say so in the
PR. Triggers: dependency/requirements changes; shared test infrastructure;
schema/storage/migrations; concurrency/global shared state; a security or
authentication boundary; broad common domain behavior; CI/test
infrastructure itself; a release/integration milestone; an explicit
PO/contract requirement. This list is deliberately not an automatic
classifier — the agent applies it by judgment per change, the same way the
rest of the validation ladder is applied.

If the post-merge `full-regression` run on `main` fails, treat `main`'s
integration baseline as unhealthy per `AGENTS.md` "Mandatory build
lifecycle": report it immediately, and do not merge further feature PRs
until the regression is understood or a PO explicitly waives it for a
proven infrastructure-only cause.

## Build size

Default build: one coherent objective, roughly 3–10 relevant source files,
focused tests and one preferred real-environment validation path.

A larger build is acceptable when producer/consumer consistency or shared-core
atomicity requires it; explain why before implementation.

Avoid bundling unrelated architecture, UI, collector and storage work.

## HTML render harness (mandatory for any UI / payload change)

Any build that changes `templates/index.html`, `static/app.js`,
`static/style.css`, or a payload builder (`configuration_ui` / `compliance` /
`crypto` / `discovery` / `project_plan`) must show the render harness green
alongside the full suite **and the repository privacy gate**
(`AGENTS.md` "Project-state update rule"):

```
py -V:3.12 scripts/render_uitest.py --out <dir>
node tools/render-harness/check-render.mjs <dir>/output/index.html
# or, when Node/happy-dom is unavailable or broken (a real Chromium instead):
py -V:3.12 tools/render-harness/check_render_playwright.py <dir>/output/index.html
```

`check-render.mjs` needs real Node.js, not Bun, to run
(`render_harness_happydom_pin`, root-caused 2026-08-31): happy-dom executes
each Window's script inside a `node:vm` context, and Bun's `vm` module does
not implement that correctly — under Bun, `window.eval` comes back as
`undefined` (not a version issue: reproduces identically on every happy-dom
major from 16.x through the currently pinned 20.x) and even built-ins like
`Map`/`Error` resolve to `undefined` inside a script run there. The exact
same happy-dom version works correctly under real Node.js. `bun install` in
`tools/render-harness/` is still fine (only Bun's `vm` module is implicated,
not its package resolution) — just execute the check itself with `node`.

`tests/test_html_render_harness.py` runs all three checks (the JSON-validity
half needs no JS engine at all; the two headless-navigation halves each skip
cleanly when their own toolchain is absent — a JS runtime + happy-dom deps
for one, `playwright` + a resolvable Chromium for the other — preferring
`node` over `bun` to run the former, per the paragraph above). Both
navigation checks perform the same walk (parse, execute, click every nav
module + inner tab, assert no console error). Treat check-render.mjs as the
primary/faster check when its toolchain is healthy; check_render_playwright.py
is the fallback, not a replacement — keep both green when both toolchains are
available. `playwright install chromium` is a one-time setup step, same role
as `bun install` in `tools/render-harness/`.
The generated report is one inline `<script>` — if it fails to parse or throws
before the nav listeners attach, every button is dead while the page still looks
loaded (the `0.7.4a` failure). If the change touches a payload field or UI
module, also extend `tests/fixtures/uitest/` (see its README growth rule) so the
harness actually exercises the new path.

## Human / agent responsibility split

AI may perform coherent source edits and local tests. Git must expose exact
changes. Human performs or explicitly approves real-environment/network
acceptance and sensitive operational actions.

Avoid the workflow where the AI instructs the human to manually edit file after
file. Prefer agent changes + diff + tests + human validation.

## Network-device command gate

Before adding/changing a device command, document:

1. why it is required,
2. its `utils/action_taxonomy.py` class (0 read / 1 controlled recovery write /
   2 operational state change / 3 configuration write / 4 policy-remediation),
3. vendor/platform/shell/context,
4. timeout,
5. retry,
6. maximum execution frequency per endpoint,
7. existing-session reuse,
8. unsupported behavior,
9. secret-bearing output risk,
10. safe telemetry.

No new class 2, 3 or 4 command at the current product maturity. A new class 1
command requires the recovery contracts in
`docs/design/RECOVERY_OPERATIONAL_WRITE_LEDGER.md` **and** an approved gate
entry — `RB.3b`'s `add backup local` is the only precedent. A parse-scope
extension of a command the collector already runs (same command, session,
timeout and frequency) is not a command addition and needs no gate entry;
`OP.0a`'s cluster-mode parse of the already-executed `cphaprob stat` is the
worked example.

## Approval boundaries

Generally allowed without additional approval after scope is accepted:
source edits, local unit tests, render-only validation, static analysis,
documentation and explicitly requested read-only local checks.

Require explicit human approval: dependency additions/upgrades, schema/storage
migration, destructive local-data operations, full-fleet collection when not
already requested, new network-access patterns, Git push/merge (Corporate
Git push/merge remains human-controlled — `AGENTS.md` "Architectural
invariants" — this is a standing rule, not conditioned on any build).

Prohibited at current maturity (taxonomy classes 2-4): firewall configuration
writes, policy install, commit, reboot/shutdown, forced failover,
interface/routing change, credential change and automatic remediation. Class 1
controlled recovery writes are permitted only through their existing contracts
and are never exposed on an HTTP surface.
