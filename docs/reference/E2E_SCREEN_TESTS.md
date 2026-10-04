# Read-only aiview screen smoke tests

The in-cluster runner uses `aiview-e2e`; the human workstation mode below remains available.
Workstation mode uses the installed Google Chrome (`channel: "chrome"`),
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
  alerts, a visible canary identity or a Transcript button. Checks print
  classifications, not leaked values. If `NEXUS_E2E_CANARY_FILE` is absent,
  the canary check is skipped with a clear log note.

## In-cluster machine mode

`ui2/frontend/Dockerfile.e2e` copies the suite and frontend source into an image
built from `mcr.microsoft.com/playwright:v1.63.0-noble`; the engineering session
pins the base and built image by digest. The image installs the already locked
frontend dependencies at build time. `deploy/ui2/70-e2e-job.yaml` contains a
suspended Job template and a suspended CronJob (every four hours). After the
engineering session supplies image digests and Secrets, it enables the CronJob
and creates a Job after each rollout. Its logs use the list reporter; screenshots,
traces and video stay disabled.

The engineering session creates `ui2-e2e-machine-token` on HOST-A with a random
256-bit token under key `token` and its lowercase SHA-256 under key `sha256`.
It creates optional `ui2-e2e-canary` key `sha256` with one lowercase SHA-256
per line, derived locally from real address and serial values without printing
those values. Generate this payload offline with `python3 tools/e2e/e2e_canary_digests.py`,
feeding one identity value per line on stdin; only sorted unique digests go to stdout.
The helper excludes exactly unspecified/default (`0.0.0.0`), limited broadcast, loopback (`127/8`), link-local (`169.254/16`) and
multicast (`224/4`) values, including CIDR forms. These convey protocol/routing
scope rather than estate identities, matching the masker pass-through policy.
The engineering session remains responsible for refreshing the existing Secret;
this helper does not access the database, cluster or devices.

AIView IPv4 identity pseudonyms use reserved `240/4`; representable prefix
lengths and host offsets are retained, while prefixes broader than `/4` fail
closed as `[REDACTED_IP]`. Masked IP pseudonyms visible to aiview change once;
device pseudonyms (`FW-...`) do not. This range separates pseudonyms from the
estate's routed address space; it is not a claim of injective mapping over all IPv4.

Rotation replaces both token keys together, then rolls the service
and starts a fresh Job; removing the Secret disables machine login. No agent or
person copies the plain token into a repository or chat. The Job receives the
token as an environment variable; the service receives only its digest.

The machine login is available only on internal port 8086, never through the
Ingress or public port 8080. It creates an ordinary `aiview-e2e` session with
the exact viewer and replay-viewer roles, a 30-minute absolute lifetime and a
normal idle timeout. A new machine login supersedes that actor's previous
session. The server refuses all machine-session methods except GET and HEAD,
including session logout and collect/backup routes, with audited 403
`MACHINE_SESSION_READ_ONLY`; replay-viewer masking still applies. The browser
route guard independently enforces read-only requests. Storage state is written
mode 0600 to the Job's memory-backed temporary directory and scoped to the
in-cluster browser host; the normal Secure cookie setting on the server remains
unchanged.

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
every possible identity leak. Canary matching covers only supplied address and
serial digests. Existing background jobs/schedules are outside this
browser request guard. Empty estates, no standalone gateway, missing vendors,
no enabled backup target or no backup history fail explicitly.

Sandbox validation is limited to type-checking, test discovery, Vitest, build
and `python3 tools/privacy/repository_privacy_check.py` from the repository root.
Live Chrome execution and role/HTTP/data behavior must be verified by the
engineering session; offline discovery is not live acceptance.

## Running it (engineering session, 2026-09-27)
- `tools/e2e/hosta_e2e.sh` builds the runner image from the context the last deploy streamed
  (`deploy/ui2-image-build/32-e2e-build-job.yaml`), runs the `ui2-e2e` Job with that image, re-points the 4-hourly
  `ui2-e2e` CronJob at it, and prints the summary. Exit 0 = pass, 1 = test failures, 2 = could not run.
- `tools/delivery/standalone_orchestrate.py ship` runs it after every deploy and prints `{"e2e": "pass"|"FAIL", ...}`.
- Secrets on HOST-A: `ui2-e2e-machine-token` (token + sha256, created once, rotate by deleting it and restarting the
  service) and `ui2-e2e-canary` (SHA-256 of real interface/management addresses and serials; refresh when the estate
  changes). Neither is in the repository.
- First green run 2026-09-27: 22/22. It found a real defect on the way: a viewer-only session saw a load error on the
  Configuration tab instead of a role notice (fixed).
