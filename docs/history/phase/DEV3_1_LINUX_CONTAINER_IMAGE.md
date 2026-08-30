# DEV.3.1 — Linux worker image + Compose (single-container first migration)

## Status

**DONE — AUTOMATED_VALIDATED 2026-08-30**

Product baseline: `0.7.6a AUTOMATED_VALIDATED`.

## Objective

Give SecurityExpert a first, minimal containerized deployment path: one
worker image running the existing sequential `main.py` pipeline unmodified,
plus nginx serving the rendered static report from a shared volume. No
collector, transport, retry, timeout or concurrency change — this only
relocates *where* the existing pipeline runs. Per-vendor worker containers
(DEV.3.4) and the distributed lock/job store (DEV.3.2/3.3) are later,
separately-gated work; this item does not attempt either.

## Scope

- `Dockerfile` — single worker image, `python:3.12-slim` base (matches the
  validated 3.12 baseline; `lxml` and `paramiko`'s `cryptography` dependency
  both ship manylinux wheels, so no build toolchain is installed). Idle by
  default (`CMD ["tail", "-f", "/dev/null"]`) — a full checkpoint,
  `--render-only` and `--scheduler-once` are all either credential/network-
  bearing or state-mutating, so none auto-run on container start.
- `.dockerignore` — keeps the image to application code + runtime deps only;
  explicitly excludes gitignored runtime state (`data/`, `output/`, `logs/`,
  `state/`, CAS dirs) and any local secret material.
  `docker-compose.yml` — `worker` (builds the image, mounts a named volume
  at `SECURITYEXPERT_RUNTIME_ROOT=/runtime`, optional `.env` via
  `env_file: {path: .env, required: false}` so a fresh checkout with no
  `.env` yet can still run `--repository-privacy-check`) + `nginx:1.27-
  alpine` (mounts the same volume read-only, serves `/runtime/output`).
- `deploy/nginx/default.conf` — static file server, `try_files $uri $uri/
  =404`, no application logic.
- `.env.example` — updated `SECURITYEXPERT_RUNTIME_ROOT` default/comment to
  `/runtime` and to mention the POSIX runtime-root default added by
  `dev_python_env_tooling_friction`.
- `nginx`'s port binds to `127.0.0.1:8080` only, deliberately: per
  `roadmap.json`'s `roadmap_notes`, `output/index.html` is a LOCAL OPERATOR
  SENSITIVE artifact, and serving it beyond loopback without the DEPLOY.1A
  authenticated-viewer boundary is not an accepted default here. Widening
  the bind address is a DEPLOY.1A decision, not a casual config change.

## Real-environment verification performed this session

This cloud sandbox's own TLS-intercepting agent proxy (documented in
`/root/.ccr/README.md`) transparently intercepts outbound HTTPS, which broke
a normal `docker build` (`pip install` failed the build's isolated network
namespace could not reach the proxy at `127.0.0.1:<port>`, and once reached
it presents a self-signed cert `pip` doesn't trust by default). This is a
property of *this sandbox only* and is explicitly not something the
committed `Dockerfile` should trust — a real deployment target has no such
proxy, and baking sandbox-specific CA trust into the shipped image would be
wrong there. So the committed `Dockerfile` was validated with a **throwaway,
non-committed** verification build only (`--network=host` plus a
build-scoped `PIP_CERT` pointed at a temporary copy of the sandbox's proxy
CA, both discarded afterward) — proving the actual application dependency
set installs and imports correctly, without changing the shipped artifact
one bit. Concretely, with the daemon started manually in this container
(`dockerd &`, since `service docker start` fails here on
`ulimit: Operation not permitted`):

1. Built the real dependency set inside a container from the committed
   `requirements.txt` and application source — succeeded (all packages
   resolve and install; `lxml`, `cryptography`, `paramiko` all installed
   from manylinux wheels, no compiler needed, confirming the `slim` base
   choice).
2. Ran `python main.py --repository-privacy-check` inside that container
   with `SECURITYEXPERT_RUNTIME_ROOT=/runtime` — passed (97 files scanned,
   0 findings, gate PASS), proving the app's own no-network-required mode
   runs correctly under the container's env-var-only configuration.
3. Brought the **actual, committed** `docker-compose.yml` up (retagging the
   verified image so `worker`'s `build: .` step didn't need the sandbox's
   proxy workaround), confirming both services start, the named volume
   mounts read-write on `worker` and read-only on `nginx`, and
   `docker compose exec worker ...` reaches the running container.
4. `docker compose exec worker python main.py --render-only` correctly
   refused with the expected bootstrap-required message (no prior
   `unified.json` exists in a fresh volume) rather than crashing — the
   correct behavior for a container that has never run a real checkpoint.
5. `curl http://127.0.0.1:8080/` against the empty `output/` volume
   returned `403` (nginx has no index file and no directory listing —
   expected, not a bug). Writing a placeholder `index.html` into the shared
   volume from the `worker` container and re-fetching returned it correctly
   via `nginx`, proving the read-only volume-sharing path end-to-end.
6. Torn down with `docker compose down -v` and removed both temporary
   images and the temporary CA file; nothing sandbox-specific was left in
   the repository.

**What remains unverified (real-env gap, same class as every other
`on_hardware_real_env_validation` item)**: a full `--only cp` / `--only
vsx` / `--only panorama` checkpoint against a real MDS/Panorama endpoint
inside the container, and `--render-only` against a production-scale
`unified.json`. This cloud environment has no device reachability (per
`AI_START_HERE.md`); that gap already existed outside containers and is
unchanged by this work.

## Acceptance criteria

- [x] Single worker image builds from the committed `Dockerfile` with no
      build toolchain required, using only manylinux wheels.
- [x] Runtime state stays outside the image and outside the copied repo
      tree (`SECURITYEXPERT_RUNTIME_ROOT=/runtime`, named volume) — DEV.0.3A's
      repo/runtime separation holds inside the container.
- [x] Non-credential modes (`--repository-privacy-check`) run correctly
      inside the container using only env-var configuration (DEV.2.1).
- [x] `docker-compose.yml` brings up worker + nginx sharing one volume,
      nginx read-only, loopback-bound.
- [x] No collector, transport, retry, timeout or concurrency semantic
      changed by this item.
- [x] No sandbox-specific trust/proxy configuration baked into any
      committed file.
- [ ] Real-device checkpoint inside the container — owed, `on_hardware_real_env_validation`.

## Definition of done

Shipped: `Dockerfile`, `.dockerignore`, `docker-compose.yml`,
`deploy/nginx/default.conf`, updated `.env.example`. Verified end-to-end in
this session via a throwaway (non-committed) build workaround for this
sandbox's own proxy, with the actual committed compose file exercised
directly against the verified image. `project/backlog.json` and
`project/build_history.json` updated accordingly.
