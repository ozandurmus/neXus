Repo github.com/ozandurmus/neXus. Setup: git fetch origin cloud-base/1105 && git checkout -b cloud/pathdiagram-unmask-1105 origin/cloud-base/1105. Read AGENTS.md first (binding). No access to the live system. Keep it small: finish within about 15 minutes.

TASK (small code): the path diagram (ui2/frontend/src/screens/pathanalysis/PathDiagram.tsx) re-masks every non-masked IPv4 client-side (safeAddressLabel → '[masked]'), so raw-view roles (security_admin) see '[masked]' in the diagram while the hop list shows real values.
The server projection is the single masking authority (AGENTS.md masking law): remove the client-side re-masking and render values exactly as the API returns them. Update the tests: keep a test that aiview fixtures render masked values, and remove the assertions that raw values become '[masked]'.
Run `cd ui2/frontend && npm ci && npx vitest run && npx tsc --noEmit`.
Commit (messages end with 'Co-Authored-By: Claude'), push cloud/pathdiagram-unmask-1105, no PR.