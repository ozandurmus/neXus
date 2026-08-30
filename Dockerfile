# DEV.3.1 — single worker image, first containerization step (roadmap.json
# "Containerization sequencing", 2026-08-28 architecture review). This image
# runs the existing sequential main.py pipeline unmodified -- no collector,
# transport, retry, timeout or concurrency change. Per-vendor worker
# containers (DEV.3.4) are a later, separately-gated optimization.
#
# python:3.12-slim matches the validated 3.12 baseline documented across this
# repo's dev tooling; lxml and paramiko's cryptography dependency both ship
# manylinux wheels for this base, so no build toolchain (gcc, libxml2-dev,
# ...) is installed here.
FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

# Runtime state (data/output/logs, the HMAC key, trust material) must live
# outside the image and outside the repository tree copied above --
# DEV.0.3A's repo/runtime separation guarantee holds inside the container
# exactly as it does on a bare-metal checkout. docker-compose.yml mounts a
# named volume at this path; do not bake runtime state into the image.
ENV SECURITYEXPERT_RUNTIME_ROOT=/runtime
ENV PYTHONUNBUFFERED=1

# Idle by default. A full checkpoint, --render-only and --scheduler-once are
# all either credential/network-bearing or state-mutating -- none of them
# auto-run on container start. This build's own contract keeps the actual
# scheduling trigger ("an external timer invokes --scheduler-once") outside
# this image; invoke explicitly, e.g.:
#   docker compose exec worker python main.py --scheduler-once
#   docker compose exec worker python main.py --render-only
CMD ["tail", "-f", "/dev/null"]
