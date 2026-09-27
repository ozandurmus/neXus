# Read-only aiview screen smoke tests

Run this suite from an engineering workstation that can reach the deployment,
after each deploy. It uses the installed Google Chrome (`channel: "chrome"`),
the existing frontend dependencies, and no downloaded browser. Node 22 with
`--experimental-strip-types` support or newer is required for the login helper.

## Login and run

From the repository root:

```sh
cd ui2/frontend
read -r -s NEXUS_E2E_BASE_URL
export NEXUS_E2E_BASE_URL
# Enter the deployment origin at the prompt, without a path or credentials.
# Optional: export NEXUS_E2E_STATE to an absolute path outside every checkout.
npm run e2e:login
npm run e2e
npx playwright show-report e2e-report
```

The helper opens headed Chrome. The human signs in as aiview; the helper never
reads login fields, passwords or request bodies. It waits up to five minutes,
checks `/session/status`, and saves storageState only for an active
`role:replay_viewer` session. Default: `~/.nexus-e2e/aiview.json`; directory mode
0700, file mode 0600. An existing state directory must already have mode 0700;
the helper never changes an unrelated directory's permissions. A path inside this checkout (including through an existing
symlink) is refused. Keep this session file private and outside all repositories.
The helper keeps product screens unmounted while authenticating. A wrong role,
password-change requirement or expired session fails; repeat login when needed.

There is no default deployment host. `NEXUS_E2E_BASE_URL` is required even for
`npx playwright test --list`. For offline discovery only, use a synthetic origin:

```sh
NEXUS_E2E_BASE_URL=https://example.invalid npx playwright test --list
npx tsc --noEmit -p .
npm test
npm run build
```

`--list` neither reads the session file nor opens a browser or contacts a server.
Vitest includes only `tests/**/*.test.{ts,tsx}`, excluding the browser suite.

## Coverage and safety

- Seven routed screen IDs: Overview, Inventory, legacy Configuration (redirects
  to Devices), Compliance, Backups, Operations and Administration. Each has a
  populated-screen smoke test and an independently named privacy test.
- Inventory opens an enrolled standalone gateway and visits every exposed
  detail tab; Interfaces and Configuration are required. Operations Jobs must
  list a job; Backups must list an enabled target.
- Four independent compliance checks require a gateway and assigned controls
  for Check Point, Palo Alto, FortiGate and Cisco ASA. The current Compliance UI
  is aggregate, so each check reads stored device compliance and locates one of
  its assigned control IDs in the visible aggregate table. Missing vendors or
  missing controls fail; no fixture or skip hides a coverage gap.
- A real backup job from the displayed Jobs response must return exactly 403
  from `GET /jobs/<id>/transcript`. Its response body is never read, even if the
  server incorrectly grants access.
- The run's first network request is `GET /session/status`; an inactive or
  non-aiview session fails before a screen opens. Every test rechecks the role,
  including filtered runs. Subsequent browser session checks are guarded too.
- Every test installs `context.route("**/*")` before navigation. Only same-origin
  GET/HEAD app requests and passive external font/stylesheet GET/HEAD reads
  (already used by `index.html`) are allowed. There are currently **no** session-maintenance
  POSTs in `src/auth/`: `/session/status` is a GET and `/session/logout` is not
  maintenance. All POST/PUT/PATCH/DELETE/OPTIONS requests are aborted and fail
  the test, including accidental app-generated writes. Service workers and
  WebSockets are blocked. Tests use browser fetch for GET probes, not Playwright's
  unguarded API request fixture. Login is separate; only its human-operated
  `/login` and `/login/resolve` POSTs are allowed.
- No submit, collect, delete, enroll, run, export/download or change control is
  clicked. One worker, no retries. Each visited screen/tab waits for its current
  read batch and fails on HTTP 5xx, uncaught page errors, load/API error text,
  alerts, visible RFC 1918 addresses or a Transcript button. RFC 5737 synthetic
  ranges are allowed. Checks print classifications, not leaked values.

## Results and limits

Console output names each check; `e2e-report/index.html` is the HTML report and
`e2e-results/` holds runner artifacts. Both directories are ignored by Git.
Screenshots, videos, traces and automatic DOM snapshots are disabled: a privacy
failure must not capture leaked text, cookies or raw API bodies. Do not enable
tracing for a credentialed run. If separately authorized visual evidence is
needed, review and sanitize it locally before sharing; keep it in these ignored
directories. Never commit session state, reports or environment values.

Passing proves the tested screens load stored data under aiview with these
read-only/privacy checks. It does not prove every number is correct, every
device/tab/subfeature is covered, vendor output semantics, collection success,
recovery behavior, backend authorization for every endpoint, or absence of
every possible identity leak. RFC 1918 detection is not a general hostname,
serial or secret detector. Existing background jobs/schedules are outside this
browser request guard. Empty estates, no standalone gateway, missing vendors,
no enabled backup target or no backup history fail explicitly.

Sandbox validation is limited to type-checking, test discovery, Vitest, build
and `python3 scripts/repository_privacy_check.py` from the repository root.
Live Chrome execution and role/HTTP/data behavior must be verified by the
engineering session; offline discovery is not live acceptance.
