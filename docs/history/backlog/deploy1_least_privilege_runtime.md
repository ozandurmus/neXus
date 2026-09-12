# Least-privilege worker and viewer runtime

status: planned · target: DEV.4.4 / DEPLOY.1

Run images as non-root with a read-only root filesystem where practical, dropped capabilities, no Docker socket, explicit resource/health limits, pinned base-image digests, and strict endpoint trust in the server overlay.
