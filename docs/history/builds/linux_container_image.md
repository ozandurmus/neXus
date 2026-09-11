# linux_container_image — Linux worker image + Compose (DEV.3.1 single-container first migration)

## Summary

User request: build the first containerized deployment path. New Dockerfile (python:3.12-slim, no build toolchain needed -- lxml/cryptography/paramiko all install from manylinux wheels; idle CMD by default so no credential/network mode auto-runs on start), .dockerignore, docker-compose.yml (worker + nginx:1.27-alpine sharing one named runtime volume, nginx mounted read-only and bound to 127.0.0.1:8080 only pending DEPLOY.1A's authenticated-viewer boundary since output/index.html is LOCAL OPERATOR SENSITIVE per roadmap.json), deploy/nginx/default.conf, updated .env.example. No collector/transport/retry/timeout/concurrency semantic changed.

## Evidence

- **automated**: py -m pytest -q: 635 passed, 2 skipped, 2 failed (both pre-existing and unrelated, same two tests already documented against the unmodified baseline in every prior 0.6.x closure this session). Zero regressions from this build's own file set (no application source touched).
- **manual_container**: This cloud sandbox's own TLS-intercepting proxy blocked a plain docker build here (pip could not reach/trust it from inside the build's network namespace); verified with a throwaway, non-committed build workaround (--network=host + a temporary CA copy, both discarded) that touched no committed file. That verified image was then run through the actual committed docker-compose.yml: both services started, python main.py --repository-privacy-check passed inside worker using only env-var config, --render-only correctly refused without a prior checkpoint, and nginx served the shared volume read-only (403 on empty output/, 200 once an index.html was written from worker). See docs/history/phase/DEV3_1_LINUX_CONTAINER_IMAGE.md for full detail.
- **privacy_gate**: No credential/device-identity/IP literal introduced; nginx bind is explicitly loopback-only.
- **real_env**: Real-device checkpoint (--only cp/vsx/panorama) inside the container remains owed -- this cloud environment has no MDS/Panorama reachability, same gap class as every other on_hardware_real_env_validation item.
