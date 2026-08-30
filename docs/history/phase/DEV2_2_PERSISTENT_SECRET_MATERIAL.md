# DEV.2.2 — Persistent Runtime Volume Contract (HMAC key + mounted trust material)

## Status

**DONE — AUTOMATED_VALIDATED 2026-08-30**

Product baseline: `0.7.6a AUTOMATED_VALIDATED`. Engineering baseline: `DEV.2.1
AUTOMATED_VALIDATED`.

## Objective

Backlog `deploy_persistent_secret_material` / roadmap `DEV.2.2`: give the
DEV.3.1 container deployment a verifiable contract that (1) the support-bundle
HMAC identity key survives a container restart instead of drifting, and (2)
CP strict host-key trust (`SECURITYEXPERT_CP_MDS_STRICT_HOST_KEY`) and PAN CA
bundle trust (`SECURITYEXPERT_PAN_CA_BUNDLE`) move from opt-in compatibility
defaults to mounted, production-grade material on the server, without
changing their opt-in default off the server.

## What was already true before this build

`data/.support_hmac.key` persistence was **already structurally correct**:
`utils/support_bundle.run_support_bundle(..., data_root=...)` derives the key
file path from `data_root`, and every `main.py` call site passes
`runtime_paths.data_root` (from `resolve_runtime_paths`, DEV.0.3A), which in
turn is always `runtime_root / "data"` — and `docker-compose.yml` (DEV.3.1)
already mounts `SECURITYEXPERT_RUNTIME_ROOT=/runtime` onto a named,
restart-surviving volume. There was no code path writing the key back into
the container's ephemeral filesystem. This build makes that contract
explicit and machine-checkable rather than leaving it as an inference from
reading four files.

## Scope

- **`utils/persistent_secret_material.py`** (new) — offline contract check.
  Given resolved `RuntimePaths`, reports whether `.support_hmac.key` already
  exists on `data_root` (persistent by construction, since
  `resolve_runtime_paths` fails closed if the runtime root is not physically
  separate from the repository — DEV.0.3A), and preflights CP strict
  host-key trust / PAN CA bundle trust **by reusing the exact production
  code paths** (`utils.cp_ssh_trust.apply_strict_host_key_policy`,
  `utils.pan_tls_trust.preflight_pan_tls_ca_bundle`) rather than
  reimplementing the check. Value-free: never prints key material, file
  paths, host identities or credentials — only booleans/status enums and a
  small findings list, matching the existing gate contract style
  (`--repository-privacy-check`).
- **`main.py`** — new `--persistent-secret-material-check` mode, same family
  as `--repository-privacy-check`: local/offline, mutually exclusive with
  collection/render/storage modes, resolves the runtime root (needed to
  locate `data_root`) but performs no network access and prompts for no
  credentials. Exit 0 on PASS, exit 1 on FAIL (strict host-key enabled with
  no usable host keys, or a configured CA bundle that is missing/unreadable);
  a not-yet-hardened but internally consistent state (nothing enabled) is
  PASS with an advisory note, not a failure — enforcing "required" is a
  DEPLOY.1 server-acceptance decision, not a blanket CLI gate before that
  boundary exists.
- **`docker-compose.prod.yml`** (new) — production overlay (not a
  replacement for `docker-compose.yml`, which stays the generic/dev-friendly
  base per DEV.3.1): sets `SECURITYEXPERT_CP_MDS_STRICT_HOST_KEY=1` and
  `SECURITYEXPERT_PAN_CA_BUNDLE`, and bind-mounts
  `deploy/secrets/known_hosts` read-only to `/root/.ssh/known_hosts`
  (`paramiko.SSHClient.load_system_host_keys()` with no explicit filename
  reads `~/.ssh/known_hosts` for the connecting user, which is `root` in this
  image) and `deploy/secrets/pan-ca-bundle.pem` read-only to
  `/run/secrets/pan-ca-bundle.pem`.
- **`deploy/secrets/README.md` + `known_hosts.example`** (new) — operator
  instructions (`ssh-keyscan` + out-of-band fingerprint verification) and a
  synthetic (RFC 5737) placeholder. The real `known_hosts` / `*.pem` files
  are gitignored by the existing `.gitignore` patterns (`known_hosts*`,
  `*.pem`), the same mechanism that already keeps `.env` out of the
  repository — no new ignore rules were needed. A `.pem` example placeholder
  was deliberately **not** added: `utils/repository_privacy.py`'s
  `FORBIDDEN_SUFFIXES` blocks any `.pem` file unconditionally (no
  `.example`-suffix carve-out like `known_hosts.example` has), so a
  committed CA-bundle placeholder would trip the privacy gate; the README
  documents the format instead.
- **`docker-compose.yml`**, **`.env.example`** — cross-references to the new
  check command and the production overlay; no behavior change to the
  DEV.3.1 base file.

No collector, transport, retry, timeout or concurrency change. No change to
`utils/cp_ssh_trust.py` or `utils/pan_tls_trust.py` — this build reuses their
existing, already real-environment-validated (`pan_tls_ca`, 0.6.5) trust
logic rather than modifying it.

## Verification performed this session

- `py -m pytest -q` (venv-installed `requirements.txt` + `pytest`, since this
  sandbox has no preinstalled interpreter matching this repo's baseline):
  `640 passed, 3 skipped, 2 failed` (645 collected) — the 6 new
  `tests/test_dev2_2_persistent_secret_material.py` cases (advisory-only PASS
  with nothing enabled; HMAC key found across a simulated restart via a
  second independent `resolve_runtime_paths` call against the same runtime
  root; PAN CA bundle missing → FAIL; PAN CA bundle present/readable → PASS;
  CP strict enabled with an isolated empty `HOME` → FAIL; every
  disabled-value spelling → NOT_ENABLED) all pass, plus the full existing
  suite. Two pre-existing failures
  (`test_run_html_export_embeds_discovery_payload_without_leftover_placeholder`,
  `test_checkpoint_render_appends_one_record`) reproduce identically on a
  clean baseline (`git stash -u`, which also removes this build's own
  untracked files) — `634 passed, 3 skipped`, same 2 failures; unrelated to
  this build (test-order-dependent state bleed in two unrelated modules),
  not introduced or fixed here.
- `python main.py --persistent-secret-material-check` run directly against a
  throwaway `SECURITYEXPERT_RUNTIME_ROOT`: printed the expected PASS/advisory
  output with no key material, path or credential in the output, exit 0.
  Argument-guard errors (`--apply`, `--render-only` combined with the new
  flag) verified to `parser.error` with exit 2, matching the existing
  `--repository-privacy-check` guard pattern.
- `docker compose -f docker-compose.yml -f docker-compose.prod.yml config`
  — the overlay merges correctly: `worker` gains the two hardening env vars
  and the two read-only bind mounts, `nginx` and the named volume are
  unaffected. (This sandbox's outbound-TLS-intercepting proxy blocks a full
  `docker build`/`up`, the same constraint recorded in DEV.3.1; this config
  merge check does not need network access and fully validates the compose
  YAML this build ships.)
- `python main.py --repository-privacy-check` — PASS, 0 findings, confirming
  the new files (including the synthetic `known_hosts.example`) do not trip
  the privacy gate.

## Acceptance criteria

- [x] `data/.support_hmac.key` persistence contract made explicit and
      machine-checkable (was already structurally correct via `data_root`).
- [x] Offline, value-free check for the DEV.2.2 contract, reusing existing
      strict-trust preflight code rather than duplicating it.
- [x] CP known_hosts and PAN CA bundle have a documented, compose-driven
      mounted-and-required path for the server, while staying opt-in
      off the server.
- [x] No collector/transport/trust-logic semantic change.
- [x] No sandbox-specific workaround baked into any committed file.
- [ ] Real MDS/Panorama endpoint validation of the mounted material inside a
      running container — owed, same class of gap as
      `cp_ssh_trust_r2_prod_server` (DEPLOY.1, already tracked separately in
      `project/backlog.json`) and every other `on_hardware_real_env_validation`
      item; this build's own scope is the offline contract + compose wiring.

## Definition of done

Shipped: `utils/persistent_secret_material.py`,
`tests/test_dev2_2_persistent_secret_material.py`, `main.py`
(`--persistent-secret-material-check`), `docker-compose.prod.yml`,
`deploy/secrets/README.md` + `known_hosts.example`, updated
`docker-compose.yml` and `.env.example` comments. `project/backlog.json`,
`project/roadmap.json` and `CURRENT_STATE.md` updated accordingly.
