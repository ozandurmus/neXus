# Least-privilege worker and viewer runtime

status: done · target: DEV.4.4 / DEPLOY.1

Run images as non-root with a read-only root filesystem where practical, dropped capabilities, no Docker socket, explicit resource/health limits, pinned base-image digests, and strict endpoint trust in the server overlay.

2026-09-13 realignment: closed as superseded. B1_01C fixes the least-privilege runtime for UI 2.0 and the deployment slice is automated-validated. The database-role half of least privilege continues as ui2_database_roles_and_data_ownership.
