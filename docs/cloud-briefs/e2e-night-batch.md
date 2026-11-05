Repo github.com/ozandurmus/neXus. Setup: git fetch origin cloud-base/1105 && git checkout -b cloud/e2e-night-batch-1105 origin/cloud-base/1105. Read AGENTS.md first (binding: masking law with synthetic FW-TANGO-04 / 192.0.2.x data only, English artifacts, UNKNOWN over guessing). No access to the live system or devices.

TASK: add automated UI coverage for the 2026-11-05 night batch (commit 4914df91):
- the device Collect menu and Collect logs now;
- the System health fleet log action;
- the path diagram toggle;
- server-side sorting headers;
- job target labels;
- the stuck-loader bounded states.
Find the e2e framework in the repo (grep for playwright or the tools/e2e scripts and their spec files) and the frontend vitest integration tests. Add e2e specs that run against the preview with masked data, following the existing spec patterns and selectors. Where e2e cannot run here, add vitest integration tests instead. Do not change production code unless a test reveals a real bug: fix it and say so.
Run `cd ui2/frontend && npm ci && npx vitest run && npx tsc --noEmit`.
Commit (messages end with 'Co-Authored-By: Claude'), push cloud/e2e-night-batch-1105, no PR.