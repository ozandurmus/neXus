# `CON.1` — Operator Console, read-only surface

## Status

**CONTRACT FROZEN 2026-08-31.** Produced as a `SCOPE → AUDIT → CONTRACT` pass
alongside `docs/design/OPERATOR_CONSOLE_ARCHITECTURE.md` (`CON.0`), which is the
architecture this contract binds to and does not restate. No source file changed
by the session that froze it. Ready for a fresh session to implement.

`project/backlog.json` `operator_console` (P1), roadmap track `CON.x`.

**Hard precondition:** `codebase_modularization` (frontend,
`docs/history/phase/CODEBASE_MODULARIZATION_FRONTEND.md`) must be **DONE**
before implementation starts. This contract changes `app_bootstrap.js`'s
initialization shape; doing that against the current 4,905-line flat
`static/app.js` would collide head-on with that build.

**Decisions this contract consumes:** `C-D1` (optional FastAPI/uvicorn
dependency) and `C-D2` (cookieless bearer authentication), both in `CON.0` §11.
Neither may be re-litigated here; if either is declined, this contract is
re-opened rather than worked around.

## Objective

Stand up the console as a **read-only** surface: an authenticated loopback HTTP
service that serves the existing UI modules and the existing report payloads,
live, with no action capability of any kind. It proves the four things every
later phase depends on — transport, authentication, content-security policy, and
the dual-mode UI — at **zero device risk**, because this phase imports no vendor
module, resolves no credential, and contacts no device.

If `CON.1` is the only phase that ever ships, the product has gained a live
local view of the last collection run. That is a real, standalone increment.

## Scope

### In scope

1. New `console/` package: ASGI application, authentication, payload assembly,
   server bootstrap.
2. New `templates/console.html` page shell (console mode only).
3. `static/console_actions.js` — **created empty-of-actions in this phase**: it
   owns mode detection and the payload fetch/refresh cycle only.
4. `utils/html_export.py`: extract the module-composition step into a reusable
   function so the console and the exporter compose the identical ordered module
   list from one implementation.
5. `app_bootstrap.js`: report initialization becomes `initializeReport(payloads)`
   (`CON.0` §6). Static mode calls it with the inline constants; behaviour of the
   exported report is unchanged.
6. `main.py --console [--console-port N]` maintenance-class mode, mutually
   exclusive with every collection/render/maintenance mode, requiring **no**
   credentials.
7. New optional `requirements-console.txt`, plus a fail-closed startup preflight
   with an actionable message when the dependency is absent.
8. `tests/test_con1_operator_console_read_only.py`.
9. `AI_START_HERE.md`: correct the "One Python CLI, no web server" line and add
   `--console` to the CLI table.

### Explicitly out of scope

- **Any mutating HTTP route.** No `POST`, `PUT`, `PATCH`, `DELETE` exists in
  this phase; AC-2 asserts it structurally.
- Any device contact, credential resolution, or vendor module import.
- The job engine, job store, SSE, or any action affordance in the UI (`CON.2`).
- The Recovery module (`CON.4`) and the scheduler view (`CON.5`).
- Any change to a payload *shape*, a collector, a command string, or CSS beyond
  what console-mode layout strictly requires.
- Any change to the exported static report's content, CSP, or behaviour.
- Server/container exposure: `docker-compose.yml` gains **no** console port
  mapping (`CON.0` `C-D5`).

## Design decisions

### `C1-1` — the console serves assets as files; only the report inlines them

The exported report keeps its single inline `<script>` and inline `<style>` and
therefore keeps `'unsafe-inline'` in its CSP. The console serves
`/assets/app.js` and `/assets/style.css` as separate responses, so its own CSP
is *stricter* than the report's:

```
default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self';
img-src 'self' data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'
```

Delivered as a real `Content-Security-Policy` **response header**, not a
`<meta>` tag — which is why `frame-ancestors` works here even though
`frontend_rendering_boundary` had to drop it from the report's meta-delivered
policy. The two policies are different by design and are asserted separately.

### `C1-2` — one composer, two consumers

`utils/html_export.py` gains:

```python
MODULE_ORDER: tuple[str, ...]                  # the frozen dependency order
def compose_modules(order=MODULE_ORDER) -> str # concatenated module source
```

`run_html_export` uses it to build the inline script; `console/app.py` uses it to
serve `/assets/app.js`. A drift between what the report runs and what the console
runs is then impossible by construction rather than by discipline. The order
itself is owned by `codebase_modularization`'s AC-3 static ordering check, which
must keep passing unchanged.

### `C1-3` — mode is set by the shell, before any module executes

```html
<script>window.SECURITYEXPERT_MODE = "console";</script>   <!-- templates/console.html -->
```

`templates/index.html` sets `"static"`. No module may infer its mode from the
presence of a global, a URL, or a `fetch` capability — one explicit flag, set in
one place per shell. Modules read it through a single accessor in `app_core.js`
so the read is greppable.

### `C1-4` — payload parity is a test, not a convention

`console/payloads.py` must build its response by calling **the same builder
functions** `utils/html_export.run_html_export` calls, with the same inputs. It
may not reshape, filter, enrich or reorder. AC-4 asserts byte-equal JSON between
the console's `/api/payloads` and the payloads embedded in an export of the same
fixture. This is the invariant that keeps the two surfaces from forking.

### `C1-5` — cookieless bearer, token in the fragment

Per `CON.0` §7.2. Concretely:

- `console/auth.py` generates a 256-bit URL-safe token per launch via
  `secrets.token_urlsafe(32)`; it is never written to a file, never logged, and
  registered with the redaction registry so it cannot appear in any log line.
- `server.py` prints exactly one line to stdout:
  `Operator console: http://127.0.0.1:<port>/#t=<token>`
- The shell reads `location.hash`, calls `history.replaceState` to strip it, and
  holds the token in a module-scoped variable. It is never written to
  `localStorage`, `sessionStorage`, or a cookie.
- Every `/api/*` request carries `Authorization: Bearer <token>`; comparison uses
  `hmac.compare_digest`.
- Additionally, `/api/*` requests are rejected with `403` unless
  `Sec-Fetch-Site` is `same-origin` (when present) and `Origin`, when present,
  matches the bound origin.

### `C1-6` — the shell is data-free, so it needs no token

`GET /` and `/assets/*` return repository code only and are unauthenticated;
this is what makes the fragment-token flow work at all (the first request cannot
carry a header). Every route that can return evidence is authenticated. AC-3
asserts the split route-by-route, so a future route cannot land on the wrong
side of it by accident.

### `C1-7` — no artifact discovery, no path input

`/api/payloads` reads from the resolved runtime paths only
(`utils.runtime_paths.resolve_runtime_paths`). No route accepts a path, a
filename, a run id, or a glob. There is no directory listing and no static file
route rooted at the runtime volume. The asset routes serve a fixed, hard-coded
map of names to repository files.

### `C1-8` — absent optional dependency fails clean

`main.py --console` performs an import preflight before doing anything else and
exits via `parser.error` with:
`--console requires the optional console dependencies: pip install -r requirements-console.txt`
— the same fail-closed startup-preflight pattern `DEV.3.3` established for the
PostgreSQL backends. `console/` is never imported by any other mode.

### `C1-9` — no autorefresh against devices, and none by default

The console refreshes payloads from **artifacts on disk**. A manual "refresh"
control re-fetches `/api/payloads`. Periodic auto-refresh is opt-in per session,
minimum interval 30 s, and is a disk read — never a collection. `CON.0` §7.10.

## Privacy and safety invariants

1. No credential, secret, token or management address is present in any response
   body, log line, or error message. The launch token is redaction-registered.
2. No vendor module (`checkpoint/*`, `panorama/*`, `configuration/*`) is
   imported by `console/`, transitively included. Asserted statically (AC-8).
3. The listener binds `127.0.0.1` only. The bind address is not configurable by
   flag or environment in this phase.
4. Nothing under the recovery root is readable through any route.
5. The exported static report is byte-identical to today's for the same inputs,
   except for the `app_bootstrap.js` initialization refactor, which must produce
   an identical rendered DOM.

## Acceptance criteria

- **AC-1** `py main.py --console` starts a loopback listener, prints exactly one
  URL line containing a fragment token, and serves the console shell; the page
  renders every module the static report renders, from live payloads, with zero
  browser console errors (real-Chromium check via the existing render harness
  tooling).
- **AC-2** The application exposes **no** route with a method other than `GET`
  or `HEAD`. Asserted by enumerating the ASGI route table, not by inspection.
- **AC-3** Every `/api/*` route returns `401` without a token and with a wrong
  token; `/` and `/assets/*` return `200` without one. Enumerated route-by-route
  so a new route must be classified deliberately.
- **AC-4** `/api/payloads` is byte-equal to the payload set embedded by
  `run_html_export` for the same `tests/fixtures/uitest` inputs.
- **AC-5** The console's CSP response header matches `C1-1` exactly; the
  exported report's CSP `<meta>` is unchanged from
  `frontend_rendering_boundary`'s frozen value. Both asserted independently.
- **AC-6** `Origin`/`Sec-Fetch-Site` mismatch on an `/api/*` request returns
  `403` even with a valid token.
- **AC-7** The launch token never appears in any log record produced during a
  console session (assert against the logger's captured output).
- **AC-8** `console/` imports no vendor/collector module — asserted by walking
  the import graph of `console.app` in a subprocess with the vendor packages
  replaced by import-failing stubs.
- **AC-9** With `requirements-console.txt` not installed, `--console` exits
  non-zero with the actionable message from `C1-8` and no traceback.
- **AC-10** The render harness is green, and the exported report contains no
  `console_actions.js` content and no action-affordance markup (asserted by
  string absence, not by eyeballing).
- **AC-11** Full suite at or above the current baseline; repository privacy gate
  `PASS / 0`.

## Implementation plan

1. Extract `MODULE_ORDER` / `compose_modules()` in `utils/html_export.py`; prove
   the exported report is unchanged (`C1-2`).
2. Refactor initialization to `initializeReport(payloads)` in
   `app_bootstrap.js`; static mode wires it to the inline constants. Render
   harness green before going further.
3. `console/auth.py` (token, comparison, header checks) with its unit tests.
4. `console/payloads.py` — reuse the builders; AC-4 test first, then the code.
5. `console/app.py` — routes, CSP header, auth dependency; AC-2/AC-3 tests.
6. `templates/console.html` + `static/console_actions.js` (mode + fetch/refresh
   only).
7. `console/server.py` + `main.py --console` wiring, mode exclusivity, `C1-8`
   preflight.
8. `requirements-console.txt`; `AI_START_HERE.md` CLI table and the
   "no web server" line.
9. AC-1/AC-10 real-Chromium walk; full suite; privacy gate; project metadata.

## Validation and merge gate

Full suite at or above baseline, repository privacy gate `PASS / 0`, render
harness green including the real-Chromium path, plus the console's own
real-Chromium walk. **Merge to `main` is approved on automated evidence**; this
phase contacts no device, so there is no real-environment gate — but the status
it reaches is `AUTOMATED_VALIDATED`, and a human interactive open on a real
workstation is the cheap follow-up that moves it to `DONE`.

## Risks

- **The initialization refactor is the risky change**, not the HTTP code: it
  touches the file every module depends on. Do it second, alone, with the render
  harness green before anything else lands on top of it.
- **Route drift.** AC-2/AC-3 are written as enumerations precisely so that a
  later phase adding a route must consciously classify it. Do not weaken them
  into "spot-check a few routes".
- **Dependency surface.** FastAPI/uvicorn are new supply-chain surface. They are
  optional, absent from the base image, and unreachable from every other mode
  (`C1-8`) — keep it that way.
- **"It's only localhost."** The single most likely way this phase ships insecure
  is someone deciding the token is unnecessary. It is not: on a corporate laptop
  other local software and other browser tabs share loopback.

## Rollback

The phase is additive: deleting `console/`, `templates/console.html`,
`static/console_actions.js`, `requirements-console.txt` and the `--console` CLI
block returns the product to its current state. The two non-additive edits
(`compose_modules` extraction and `initializeReport`) are behaviour-preserving
refactors covered by the existing render harness and are safe to keep.

## Definition of done

AC-1…AC-11 pass; `AI_START_HERE.md` is accurate again; `project/backlog.json`,
`project/feature_registry.json`, `project/build_history.json` and
`CURRENT_STATE.md` updated; `AI_HANDOVER.md` rewritten for `CON.2`.

## Next movement / model

`IMPLEMENTATION` at **`Sonnet 5, normal`**. This is deterministic work against a
frozen contract with concrete file boundaries; extended thinking is not needed
and would be more than the step requires. Escalate only if the initialization
refactor turns out to interact with `codebase_modularization`'s final module
boundaries in a way this contract did not anticipate — in which case stop and
re-freeze rather than improvise.
